package com.dkmz.photo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dkmz.photo.dto.PayGoods;
import com.dkmz.photo.entity.PhotoPayOrder;
import com.dkmz.photo.entity.PhotoPayRefund;
import com.dkmz.photo.mapper.PhotoPayOrderMapper;
import com.dkmz.photo.mapper.PhotoPayRefundMapper;
import com.dkmz.photo.support.Logs;
import com.dkmz.photo.support.PaySignUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 虚拟支付（道具直购 short_series_goods）。
 *
 * 与本项目其它模块的关系：
 *   规格库下发（SpecService）让用户做对照片；本服务负责「解锁无水印高清」这一付费动作，
 *   发货后由 {@link EntitlementService} 发放权益（张数 / 会员天数）。
 *
 * 关键实现要点（均为 exam-countdown 线上踩坑后的结论，勿随意改动）：
 *   ① 签名：paySig = HMAC_SHA256(AppKey, "requestVirtualPayment&" + signData)；
 *      signature = HMAC_SHA256(session_key, signData)，session_key 必须用前端 code 换。
 *   ② signData 字段顺序即签名内容，用 LinkedHashMap 固定顺序，序列化只做一次原样下发。
 *   ③ 发货幂等两道闸：订单已 DELIVERED / 同一微信平台单号已发过货。
 *   ④ 发货用 CAS（UPDATE ... WHERE status IN ('INIT','PAID')）抢占，只有抢到的通道才发权益，
 *      避免「微信推送」与「兜底查单」并发时重复发货。
 *   ⑤ 退款必须回收权益（微信不会替我们扣回），且 REFUNDED 的订单绝不能再发货。
 *   ⑥ 无签名推送不能直接拒（云托管转发的推送不带签名），改为调用查单核验是否真已支付。
 */
@Service
public class PhotoPayService {

    private static final Logger log = LoggerFactory.getLogger(PhotoPayService.class);

    public static final String MODE_GOODS = "short_series_goods";

    private static final String URI_REQUEST_PAYMENT = "requestVirtualPayment";
    private static final String URI_QUERY_ORDER = "/xpay/query_order";

    /** 查单接口地址（两条通道都要能在日志里认出来）；不带 query —— pay_sig / access_token 绝不落日志 */
    private static final String API_QUERY_ORDER_LABEL =
            "http://api.weixin.qq.com" + URI_QUERY_ORDER + " 或 https://api.weixin.qq.com" + URI_QUERY_ORDER;
    /** 登录换 openid 的接口地址；query 里有 secret 与 js_code，日志只用这个不带 query 的地址 */
    private static final String API_CODE2SESSION = "https://api.weixin.qq.com/sns/jscode2session";

    private static final long QUERY_BACKOFF_MS = 5 * 60_000L;
    private static final int QUERY_ERR_ORDER_NOT_FOUND = 268490002;
    private static final int ORDER_STATUS_REFUNDED = 5;
    private static final int ORDER_STATUS_USER_REFUNDED = 8;
    private static final DateTimeFormatter ORDER_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final ObjectMapper om = new ObjectMapper();

    private final PhotoPayOrderMapper payOrderMapper;
    private final PhotoPayRefundMapper payRefundMapper;
    private final EntitlementService entitlementService;
    private final TokenManager tokenManager;
    private final TransactionTemplate transaction;

    @Value("${wx.appid:}")
    private String appid;

    @Value("${wx.secret:}")
    private String secret;

    @Value("${wx.cloud-call:true}")
    private boolean cloudCallFirst;

    @Value("${wx.pay.enabled:false}")
    private boolean enabled;

    @Value("${wx.pay.offer-id:}")
    private String offerId;

    @Value("${wx.pay.appkey:}")
    private String appKeyProd;

    @Value("${wx.pay.appkey-sandbox:}")
    private String appKeySandbox;

    @Value("${wx.pay.env:0}")
    private int env;

    /** 商品配置：goodsId:priceFen:quota:vipDays:name，多项用 ; 分隔 */
    @Value("${wx.pay.goods:}")
    private String goodsRaw;

    private volatile List<PayGoods> goodsCache;
    private volatile long queryBackoffUntil = 0L;

    public PhotoPayService(PhotoPayOrderMapper payOrderMapper,
                           PhotoPayRefundMapper payRefundMapper,
                           EntitlementService entitlementService,
                           TokenManager tokenManager,
                           PlatformTransactionManager transactionManager) {
        this.payOrderMapper = payOrderMapper;
        this.payRefundMapper = payRefundMapper;
        this.entitlementService = entitlementService;
        this.tokenManager = tokenManager;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ==========================================================
    // 商品
    // ==========================================================

    public List<PayGoods> goods() {
        List<PayGoods> cached = goodsCache;
        if (cached != null) {
            return cached;
        }
        List<PayGoods> list = new ArrayList<>();
        if (goodsRaw != null && !goodsRaw.isBlank()) {
            for (String seg : goodsRaw.split(";")) {
                String s = seg.trim();
                if (s.isEmpty()) {
                    continue;
                }
                String[] p = s.split(":", 5);
                if (p.length < 4) {
                    log.warn("商品配置格式错误（需 goodsId:priceFen:quota:vipDays:name）: {}", s);
                    continue;
                }
                try {
                    String id = p[0].trim();
                    int priceFen = Integer.parseInt(p[1].trim());
                    int quota = Integer.parseInt(p[2].trim());
                    int vipDays = Integer.parseInt(p[3].trim());
                    String name = p.length > 4 ? p[4].trim() : id;
                    list.add(new PayGoods(id, name, priceFen, quota, vipDays));
                } catch (NumberFormatException e) {
                    log.warn("商品配置数字解析失败: {}", s);
                }
            }
        }
        goodsCache = list;
        return list;
    }

    private PayGoods findGoods(String goodsId) {
        if (goodsId == null) {
            return null;
        }
        for (PayGoods g : goods()) {
            if (goodsId.trim().equals(g.getGoodsId())) {
                return g;
            }
        }
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private String currentAppKey() {
        return env == 1 ? appKeySandbox : appKeyProd;
    }

    // ==========================================================
    // 下单
    // ==========================================================

    /**
     * 创建订单并生成前端拉起支付所需的三件套（signData / paySig / signature）。
     *
     * @param openid  可信 openid（云托管注入的请求头）
     * @param goodsId 道具 ID
     * @param code    wx.login() 拿到的一次性 code，用于换 session_key（用户态签名必需）
     */
    public Map<String, Object> createOrder(String openid, String goodsId, String code) {
        if (!enabled) {
            return fail("PAY_DISABLED", "支付功能尚未开通");
        }
        if (offerId == null || offerId.isBlank()) {
            return fail("PAY_NOT_CONFIGURED", "支付参数未配置（OfferID）");
        }
        String appKey = currentAppKey();
        if (appKey == null || appKey.isBlank()) {
            return fail("PAY_NOT_CONFIGURED", "支付参数未配置（AppKey）");
        }
        PayGoods goods = findGoods(goodsId);
        if (goods == null) {
            return fail("GOODS_NOT_FOUND", "商品不存在，请刷新后重试");
        }
        if (code == null || code.isBlank()) {
            return fail("CODE_REQUIRED", "缺少登录凭证，请重新进入小程序后重试");
        }

        Session session;
        try {
            session = code2session(code);
        } catch (Exception e) {
            log.error("外部接口 | GET {} | 用途=下单换 openid | 结果=失败：{}",
                    API_CODE2SESSION, Logs.safe(e));
            return fail("SESSION_FAILED", "获取登录态失败，请重新进入小程序后重试");
        }

        String payOpenid = (openid != null && !openid.isBlank()) ? openid.trim() : session.openid;
        if (session.openid != null && !session.openid.equals(payOpenid)) {
            log.warn("下单 openid 与 code 身份不一致: header={} code={}", payOpenid, session.openid);
        }

        String outTradeNo = genOrderNo();
        String attach = payOpenid + "|" + goods.getGoodsId() + "|" + goods.getQuota() + "|" + goods.getVipDays();

        // ⚠️ 字段顺序即签名内容，顺序与内容都不可改（LinkedHashMap 保序）
        LinkedHashMap<String, Object> signData = new LinkedHashMap<>();
        signData.put("offerId", offerId);
        signData.put("buyQuantity", 1);
        signData.put("env", env);
        signData.put("currencyType", "CNY");
        signData.put("productId", goods.getGoodsId());
        signData.put("goodsPrice", goods.getPriceFen());
        signData.put("outTradeNo", outTradeNo);
        signData.put("attach", attach);

        String signDataJson;
        try {
            signDataJson = om.writeValueAsString(signData);
        } catch (Exception e) {
            log.error("signData 序列化失败: {}", e.toString());
            return fail("SIGN_FAILED", "下单失败，请稍后重试");
        }

        String paySig = PaySignUtil.hmacSha256Hex(appKey, URI_REQUEST_PAYMENT + "&" + signDataJson);
        String signature = PaySignUtil.hmacSha256Hex(session.sessionKey, signDataJson);

        PhotoPayOrder order = new PhotoPayOrder();
        order.setOutTradeNo(outTradeNo);
        order.setOpenid(payOpenid);
        order.setProductId(goods.getGoodsId());
        order.setGoodsName(goods.getName());
        order.setQuotaDelta(goods.getQuota());
        order.setVipDays(goods.getVipDays());
        order.setQuantity(1);
        order.setPriceFen(goods.getPriceFen());
        order.setEnv(env);
        order.setStatus("INIT");
        order.setAttach(attach);
        order.setNotifyCount(0);
        order.setCreatedAt(LocalDateTime.now());
        payOrderMapper.insert(order);
        log.info("虚拟支付下单 openid={} goods={} order={} priceFen={} quota={} vipDays={}",
                payOpenid, goods.getGoodsId(), outTradeNo, goods.getPriceFen(),
                goods.getQuota(), goods.getVipDays());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("orderId", outTradeNo);
        out.put("mode", MODE_GOODS);
        out.put("env", env);
        out.put("signData", signDataJson);
        out.put("paySig", paySig);
        out.put("signature", signature);
        out.put("goods", goods);
        return out;
    }

    /** 查询本地订单状态（前端支付成功回调后确认到账用） */
    public Map<String, Object> localOrder(String outTradeNo, String openid) {
        PhotoPayOrder order = payOrderMapper.selectById(outTradeNo);
        Map<String, Object> out = new LinkedHashMap<>();
        if (order == null) {
            out.put("ok", false);
            out.put("code", "ORDER_NOT_FOUND");
            out.put("msg", "订单不存在");
            return out;
        }
        if (openid != null && !openid.isBlank() && !openid.trim().equals(order.getOpenid())) {
            log.warn("查询订单 openid 不一致: req={} order={}", openid, order.getOpenid());
        }
        out.put("ok", true);
        out.put("orderId", order.getOutTradeNo());
        out.put("status", order.getStatus());
        out.put("quotaDelta", order.getQuotaDelta());
        out.put("vipDays", order.getVipDays());
        out.put("delivered", "DELIVERED".equals(order.getStatus()));
        return out;
    }

    /** 订单中心列表（最近 50 笔，倒序） */
    public Map<String, Object> myOrders(String openid) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (openid == null || openid.isBlank()) {
            out.put("ok", false);
            out.put("code", "NO_OPENID");
            out.put("msg", "未获取到用户身份");
            return out;
        }
        List<PhotoPayOrder> list = payOrderMapper.selectList(new QueryWrapper<PhotoPayOrder>()
                .eq("openid", openid)
                .orderByDesc("created_at")
                .last("limit 50"));
        List<Map<String, Object>> orders = new ArrayList<>();
        for (PhotoPayOrder o : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", o.getOutTradeNo());
            m.put("goodsName", o.getGoodsName());
            m.put("productId", o.getProductId());
            m.put("priceFen", o.getPriceFen());
            m.put("quotaDelta", o.getQuotaDelta());
            m.put("vipDays", o.getVipDays());
            m.put("status", o.getStatus());
            m.put("createdAt", o.getCreatedAt() == null ? null : o.getCreatedAt().toString());
            m.put("deliveredAt", o.getDeliveredAt() == null ? null : o.getDeliveredAt().toString());
            orders.add(m);
        }
        out.put("ok", true);
        out.put("orders", orders);
        return out;
    }

    // ==========================================================
    // 发货（微信道具发货推送）
    // ==========================================================

    /**
     * 处理 xpay_goods_deliver_notify。
     *
     * @param trusted 推送是否已通过签名/内网通道验证。为 false 时先查单核验是否真已支付，
     *                查单明确「未支付」则拒绝（防伪造推送白拿权益）；查单不可用则不阻断，靠兜底补发。
     * @return true = 已确认发货（应答 0，微信不再重推）
     */
    public boolean deliver(JsonNode notify, boolean trusted) {
        String outTradeNo = text(notify, "OutTradeNo");
        String notifyOpenid = text(notify, "OpenId");
        Integer notifyEnv = intVal(notify, "Env");
        JsonNode goodsInfo = notify.path("GoodsInfo");
        JsonNode payInfo = notify.path("WeChatPayInfo");
        String wxOrderId = text(payInfo, "MchOrderNo");
        String transactionId = text(payInfo, "TransactionId");
        Integer actualFen = intVal(goodsInfo, "ActualPrice");
        Integer quantity = intVal(goodsInfo, "Quantity");

        log.info("收到道具发货推送: outTradeNo={} openid={} env={} wxOrderId={} productId={} qty={} actualFen={}",
                outTradeNo, notifyOpenid, notifyEnv, wxOrderId, text(goodsInfo, "ProductId"), quantity, actualFen);

        if (outTradeNo == null || outTradeNo.isBlank()) {
            log.error("发货推送缺少 OutTradeNo，无法处理: {}", notify);
            return false;
        }
        if (notifyEnv != null && notifyEnv != env) {
            log.warn("发货推送 env={} 与当前配置 env={} 不一致（若为沙箱推送请调 XPAY_ENV）", notifyEnv, env);
        }

        PhotoPayOrder order = payOrderMapper.selectById(outTradeNo);
        if (order == null) {
            log.error("发货推送找不到订单 outTradeNo={}，将让微信重试", outTradeNo);
            return false;
        }
        if ("DELIVERED".equals(order.getStatus())) {
            log.info("订单已发货，幂等跳过: {}", outTradeNo);
            return true;
        }
        if ("REFUNDED".equals(order.getStatus())) {
            log.warn("订单已退款，拒绝发货: {}", outTradeNo);
            return true;
        }
        if (wxOrderId != null && !wxOrderId.isBlank()) {
            Long dup = payOrderMapper.selectCount(new QueryWrapper<PhotoPayOrder>()
                    .eq("wx_order_id", wxOrderId)
                    .eq("status", "DELIVERED"));
            if (dup != null && dup > 0) {
                log.warn("同一微信平台单号已发过货，跳过: wxOrderId={} outTradeNo={}", wxOrderId, outTradeNo);
                return true;
            }
        }

        if (!trusted) {
            Boolean paid = queryPaid(outTradeNo, notifyOpenid == null ? order.getOpenid() : notifyOpenid,
                    notifyEnv == null ? env : notifyEnv);
            if (Boolean.FALSE.equals(paid)) {
                log.error("未签名推送且查单确认未支付，拒绝发货: {}", outTradeNo);
                return false;
            }
            if (paid == null) {
                log.warn("未签名推送，查单无法确认（放行发货，仅依赖本地订单校验）: {}", outTradeNo);
            } else {
                log.info("未签名推送，已查单确认支付: {}", outTradeNo);
            }
        }

        claimAndDeliver(outTradeNo, order.getOpenid(),
                order.getQuotaDelta() == null ? 0 : order.getQuotaDelta(),
                order.getVipDays() == null ? 0 : order.getVipDays(),
                wxOrderId, transactionId, actualFen, "push");
        return true;
    }

    /**
     * 原子占用订单并发货：CAS（`UPDATE ... WHERE status IN ('INIT','PAID')`）成功的那一方才发权益。
     * 为什么：发货有两条通道 —— ① 微信推送（实时）② recheckPending 兜底查单，
     * 两者可能同时读到同一笔 INIT 订单并同时通过状态判断，不做 CAS 就会重复发权益。
     */
    private boolean claimAndDeliver(String outTradeNo, String openid, int quotaDelta, int vipDays,
                                    String wxOrderId, String transactionId, Integer actualFen,
                                    String source) {
        Boolean delivered = transaction.execute(status -> claimAndDeliverInTransaction(
                outTradeNo, openid, quotaDelta, vipDays, wxOrderId, transactionId, actualFen, source));
        return Boolean.TRUE.equals(delivered);
    }

    private boolean claimAndDeliverInTransaction(String outTradeNo, String openid, int quotaDelta, int vipDays,
                                                 String wxOrderId, String transactionId, Integer actualFen,
                                                 String source) {
        UpdateWrapper<PhotoPayOrder> uw = new UpdateWrapper<PhotoPayOrder>()
                .eq("out_trade_no", outTradeNo)
                .in("status", "INIT", "PAID")
                .set("status", "DELIVERED")
                .set("delivered_at", LocalDateTime.now())
                .setSql("notify_count = IFNULL(notify_count, 0) + 1")
                .setSql("paid_at = IFNULL(paid_at, NOW())");
        if (wxOrderId != null && !wxOrderId.isBlank()) {
            uw.set("wx_order_id", wxOrderId);
        }
        if (transactionId != null && !transactionId.isBlank()) {
            uw.set("transaction_id", transactionId);
        }
        if (actualFen != null) {
            uw.set("actual_fen", actualFen);
        }
        if (payOrderMapper.update(null, uw) != 1) {
            log.info("订单已被其他通道发货，跳过（并发保护）order={} via={}", outTradeNo, source);
            return false;
        }
        if (quotaDelta > 0) {
            entitlementService.addPaidQuota(openid, quotaDelta);
        }
        if (vipDays > 0) {
            entitlementService.addVipDays(openid, vipDays);
        }
        log.info("发货完成 order={} openid={} +{} 张 +{} 天会员 via={}", outTradeNo, openid, quotaDelta, vipDays, source);
        return true;
    }

    // ==========================================================
    // 退款（xpay_refund_notify）
    // ==========================================================

    /**
     * 处理退款通知：置订单 REFUNDED 并**回收权益**。
     * 微信不会替我们扣回已发放的权益，必须自己做；否则用户退完款还能继续用。
     *
     * @return true = 已确认处理（应答 0）
     */
    public boolean refund(JsonNode notify) {
        String outTradeNo = firstNonBlank(text(notify, "MchOrderId"), text(notify, "OutTradeNo"));
        String notifyOpenid = text(notify, "OpenId");
        String wxRefundId = text(notify, "WxRefundId");
        String mchRefundId = text(notify, "MchRefundId");
        String wxOrderId = text(notify, "WxOrderId");
        Integer refundFee = intVal(notify, "RefundFee");
        Integer retCode = intVal(notify, "RetCode");
        String retMsg = text(notify, "RetMsg");
        Long succTs = longVal(notify, "RefundSuccTimestamp");

        log.info("收到退款推送: outTradeNo={} openid={} wxRefundId={} mchRefundId={} refundFee={} retCode={} retMsg={}",
                outTradeNo, notifyOpenid, wxRefundId, mchRefundId, refundFee, retCode, retMsg);

        if (outTradeNo == null || outTradeNo.isBlank()) {
            log.error("退款推送缺少订单号（MchOrderId / OutTradeNo 均为空），将让微信重试: {}", notify);
            return false;
        }

        PhotoPayOrder order = payOrderMapper.selectById(outTradeNo);
        if (order == null) {
            log.error("退款推送找不到订单 outTradeNo={}，将让微信重试", outTradeNo);
            return false;
        }

        int rc = retCode == null ? 0 : retCode;
        if (rc != 0) {
            log.warn("退款未成功（RetCode={} {}），仅留痕不回收权益: {}", rc, retMsg, outTradeNo);
            recordRefund(order, wxRefundId, mchRefundId, wxOrderId, refundFee, rc, retMsg, succTs, 0, 0);
            return true;
        }

        int claim = claimRefund(outTradeNo);
        if (claim == 0) {
            log.info("订单已处于退款状态（或状态不可占用），幂等跳过: {}", outTradeNo);
            recordRefund(order, wxRefundId, mchRefundId, wxOrderId, refundFee, rc, retMsg, succTs, 0, 0);
            return true;
        }

        int quotaReduced = 0;
        int vipReduced = 0;
        if (claim == 1) {
            // 原本已发货 -> 权益已发放，回收
            int q = order.getQuotaDelta() == null ? 0 : order.getQuotaDelta();
            int v = order.getVipDays() == null ? 0 : order.getVipDays();
            if (q > 0) {
                entitlementService.reducePaidQuota(order.getOpenid(), q);
                quotaReduced = q;
            }
            if (v > 0) {
                entitlementService.reduceVipDays(order.getOpenid(), v);
                vipReduced = v;
            }
        } else {
            log.info("订单退款时尚未发货，无权益需回收: order={}", outTradeNo);
        }
        recordRefund(order, wxRefundId, mchRefundId, wxOrderId, refundFee, rc, retMsg, succTs, quotaReduced, vipReduced);
        log.info("退款处理完成 order={} openid={} 回收张数={} 回收会员天数={} wxRefundId={}",
                outTradeNo, order.getOpenid(), quotaReduced, vipReduced, wxRefundId);
        return true;
    }

    /**
     * 原子占用退款（CAS）：1 = 原本已发货（需回收权益）/ 2 = 原本未发货（无需回收）/ 0 = 已是 REFUNDED。
     * 把状态判断写进 SQL 的 WHERE，由数据库保证「判定 + 写入」原子，避免与发货通道竞态。
     */
    private int claimRefund(String outTradeNo) {
        UpdateWrapper<PhotoPayOrder> delivered = new UpdateWrapper<PhotoPayOrder>()
                .eq("out_trade_no", outTradeNo)
                .eq("status", "DELIVERED")
                .set("status", "REFUNDED");
        if (payOrderMapper.update(null, delivered) == 1) {
            return 1;
        }
        UpdateWrapper<PhotoPayOrder> pending = new UpdateWrapper<PhotoPayOrder>()
                .eq("out_trade_no", outTradeNo)
                .in("status", "INIT", "PAID")
                .set("status", "REFUNDED");
        if (payOrderMapper.update(null, pending) == 1) {
            return 2;
        }
        return 0;
    }

    private void recordRefund(PhotoPayOrder order, String wxRefundId, String mchRefundId, String wxOrderId,
                              Integer refundFee, int retCode, String retMsg, Long succTs,
                              int quotaReduced, int vipReduced) {
        if (wxRefundId != null && !wxRefundId.isBlank()) {
            Long exists = payRefundMapper.selectCount(new QueryWrapper<PhotoPayRefund>()
                    .eq("wx_refund_id", wxRefundId)
                    .eq("ret_code", retCode));
            if (exists != null && exists > 0) {
                return;
            }
        }
        PhotoPayRefund r = new PhotoPayRefund();
        r.setOutTradeNo(order.getOutTradeNo());
        r.setOpenid(order.getOpenid());
        r.setWxRefundId(wxRefundId);
        r.setMchRefundId(mchRefundId);
        r.setWxOrderId(wxOrderId);
        r.setRefundFee(refundFee == null ? 0 : refundFee);
        r.setRetCode(retCode);
        r.setRetMsg(retMsg == null ? null : (retMsg.length() > 250 ? retMsg.substring(0, 250) : retMsg));
        r.setQuotaReduced(quotaReduced);
        r.setVipDaysReduced(vipReduced);
        r.setSuccAt(succTs == null || succTs <= 0
                ? null
                : LocalDateTime.ofEpochSecond(succTs, 0, ZoneOffset.ofHours(8)));
        r.setCreatedAt(LocalDateTime.now());
        try {
            payRefundMapper.insert(r);
        } catch (Exception e) {
            log.warn("退款流水写入失败 order={}: {}", order.getOutTradeNo(), e.toString());
        }
    }

    /**
     * iOS「退款问询」应答决策：Apple 连续问询 3 次，需 3 秒内给意见。
     * 按官方建议给凭据：已发货 → 拒绝退款（虚拟内容已交付）；未发货 → 建议退款。
     */
    public Map<String, Object> decideIosRefundQuery(JsonNode notify) {
        String payOrderId = firstNonBlank(text(notify, "pay_order_id"),
                text(notify, "MchOrderId"), text(notify, "OutTradeNo"));
        boolean delivered = false;
        if (payOrderId != null && !payOrderId.isBlank()) {
            PhotoPayOrder order = payOrderMapper.selectById(payOrderId);
            delivered = order != null && "DELIVERED".equals(order.getStatus());
        }
        log.info("iOS 退款问询 payOrderId={} 已发货={} -> {}", payOrderId, delivered, delivered ? "拒绝退款" : "建议退款");
        Map<String, Object> resp = new LinkedHashMap<>();
        if (delivered) {
            resp.put("result_code", 1);
            resp.put("result_info", "拒绝退款");
            resp.put("evidence", "该商品为虚拟内容（证件照高清无水印导出），支付成功后权益已即时发放到用户账户，"
                    + "数字内容一经发放不支持退款。");
        } else {
            resp.put("result_code", 0);
            resp.put("result_info", "建议退款");
            resp.put("evidence", "该订单尚未发货，用户未获得任何虚拟内容，建议退款。");
        }
        return resp;
    }

    // ==========================================================
    // 兜底查单
    // ==========================================================

    /**
     * 扫描近 6 小时内仍为 INIT 的订单，主动查单补发；并扫已发货订单查是否已退款（回收权益）。
     * 本项目不做常驻定时任务，由云托管「定时任务」定期调 POST /api/photo/pay/recheck 触发。
     */
    public Map<String, Object> recheckPending() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled) {
            out.put("ok", false);
            out.put("msg", "支付未开通");
            return out;
        }
        LocalDateTime now = LocalDateTime.now();
        List<PhotoPayOrder> pending = payOrderMapper.selectList(new QueryWrapper<PhotoPayOrder>()
                .eq("status", "INIT")
                .eq("env", env)
                .ge("created_at", now.minusHours(6))
                .le("created_at", now.minusMinutes(1))
                .orderByAsc("created_at")
                .last("limit 50"));

        int checked = 0;
        int delivered = 0;
        for (PhotoPayOrder o : pending) {
            checked++;
            try {
                Boolean paid = queryPaid(o.getOutTradeNo(), o.getOpenid(), o.getEnv() == null ? 0 : o.getEnv());
                if (Boolean.TRUE.equals(paid)) {
                    boolean d = claimAndDeliver(o.getOutTradeNo(), o.getOpenid(),
                            o.getQuotaDelta() == null ? 0 : o.getQuotaDelta(),
                            o.getVipDays() == null ? 0 : o.getVipDays(),
                            null, null, null, "recheck");
                    if (d) {
                        delivered++;
                    }
                }
            } catch (Exception e) {
                log.warn("外部接口 | POST {} | 用途=补单扫描查单 | order={} | 结果=异常：{}",
                        API_QUERY_ORDER_LABEL, o.getOutTradeNo(), Logs.safe(e));
            }
        }

        List<PhotoPayOrder> deliveredOrders = payOrderMapper.selectList(new QueryWrapper<PhotoPayOrder>()
                .eq("status", "DELIVERED")
                .eq("env", env)
                .ge("delivered_at", now.minusHours(6))
                .orderByAsc("delivered_at")
                .last("limit 50"));
        int refunded = 0;
        for (PhotoPayOrder o : deliveredOrders) {
            try {
                if (sweepRefund(o)) {
                    refunded++;
                }
            } catch (Exception e) {
                log.warn("外部接口 | POST {} | 用途=退款兜底查单 | order={} | 结果=异常：{}",
                        API_QUERY_ORDER_LABEL, o.getOutTradeNo(), Logs.safe(e));
            }
        }

        out.put("ok", true);
        out.put("checked", checked);
        out.put("delivered", delivered);
        out.put("refunded", refunded);
        return out;
    }

    /** 退款兜底：查单发现微信侧已退款（状态 5 / 8）则回收权益 */
    private boolean sweepRefund(PhotoPayOrder order) {
        Integer st = queryOrderStatus(order.getOutTradeNo(), order.getOpenid(),
                order.getEnv() == null ? 0 : order.getEnv());
        if (st == null || !isRefundedStatus(st)) {
            return false;
        }
        if (claimRefund(order.getOutTradeNo()) != 1) {
            return false;
        }
        int q = order.getQuotaDelta() == null ? 0 : order.getQuotaDelta();
        int v = order.getVipDays() == null ? 0 : order.getVipDays();
        int qr = 0;
        int vr = 0;
        if (q > 0) {
            entitlementService.reducePaidQuota(order.getOpenid(), q);
            qr = q;
        }
        if (v > 0) {
            entitlementService.reduceVipDays(order.getOpenid(), v);
            vr = v;
        }
        recordRefund(order, null, null, null, order.getActualFen(), 0,
                "退款兜底查单回收（微信状态 " + st + "）", null, qr, vr);
        log.info("退款兜底生效：查单发现已退款 order={} 回收张数={} 会员天数={}", order.getOutTradeNo(), qr, vr);
        return true;
    }

    // ==========================================================
    // 查单通道
    // ==========================================================

    /** true=已支付 / false=明确未支付 / null=无法确认 */
    private Boolean queryPaid(String outTradeNo, String openid, int orderEnv) {
        Integer st = queryOrderStatus(outTradeNo, openid, orderEnv);
        if (st == null) {
            return null;
        }
        if (st < 0) {
            log.info("外部接口 | POST {} | 用途=查单核验 | order={} | 结果=微信侧无此订单（未支付）",
                    API_QUERY_ORDER_LABEL, outTradeNo);
            return Boolean.FALSE;
        }
        return isPaidStatus(st);
    }

    /** >=0 微信侧状态；-1 微信侧无此订单；null 无法确认（已退避） */
    private Integer queryOrderStatus(String outTradeNo, String openid, int orderEnv) {
        if (System.currentTimeMillis() < queryBackoffUntil) {
            return null;
        }
        try {
            JsonNode r = queryOrder(outTradeNo, openid, orderEnv);
            if (r == null) {
                backoff("无响应");
                return null;
            }
            int errcode = r.path("errcode").asInt(0);
            if (errcode == QUERY_ERR_ORDER_NOT_FOUND) {
                return -1;
            }
            if (errcode != 0) {
                log.warn("外部接口 | POST {} | 用途=查单核验 | order={} | 结果=业务错误 errcode={} {}",
                        API_QUERY_ORDER_LABEL, outTradeNo, errcode, r.path("errmsg").asText(""));
                return null;
            }
            JsonNode st = r.path("order").path("status");
            if (!st.isNumber()) {
                backoff("返回体缺少 order.status");
                return null;
            }
            return st.asInt();
        } catch (Exception e) {
            log.warn("外部接口 | POST {} | 用途=查单核验 | 结果=异常（云调用与 https 两条通道均不可用）：{}",
                    API_QUERY_ORDER_LABEL, Logs.safe(e));
            backoff(e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 调 /xpay/query_order。两条通道：
     *   ① 云托管开放接口服务内网通道 http://api.weixin.qq.com（免鉴权、不走 TLS）
     *   ② 直连 https（access_token + pay_sig）降级
     * pay_sig 必须放在 query string（官方要求）。
     */
    private JsonNode queryOrder(String outTradeNo, String openid, int orderEnv) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("openid", openid);
        body.put("env", orderEnv);
        body.put("order_id", outTradeNo);
        String bodyJson = om.writeValueAsString(body);
        String paySig = PaySignUtil.hmacSha256Hex(currentAppKey(), URI_QUERY_ORDER + "&" + bodyJson);

        if (cloudCallFirst) {
            String cloudUrl = "http://api.weixin.qq.com" + URI_QUERY_ORDER;
            try {
                // pay_sig 在 query 里，日志只用不带 query 的地址
                return postJson(cloudUrl + "?pay_sig=" + paySig, bodyJson);
            } catch (Exception e) {
                log.warn("外部接口 | POST {}（云托管内网通道）查单失败，降级直连 {}：{}",
                        cloudUrl, "https://api.weixin.qq.com" + URI_QUERY_ORDER, Logs.safe(e));
            }
        }
        String url = "https://api.weixin.qq.com" + URI_QUERY_ORDER
                + "?access_token=" + tokenManager.get() + "&pay_sig=" + paySig;
        return postJson(url, bodyJson);
    }

    private JsonNode postJson(String url, String bodyJson) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(4))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return om.readTree(resp.body());
    }

    /** 2=已支付待发货 3=发货中 4=已发货（5~10 为退款链路） */
    private static boolean isPaidStatus(int status) {
        return status >= 2 && status <= 4;
    }

    private static boolean isRefundedStatus(int status) {
        return status == ORDER_STATUS_REFUNDED || status == ORDER_STATUS_USER_REFUNDED;
    }

    private void backoff(String reason) {
        queryBackoffUntil = System.currentTimeMillis() + QUERY_BACKOFF_MS;
        log.warn("查单核验暂不可用（{}），{} 分钟内跳过查单核验", reason, QUERY_BACKOFF_MS / 60_000);
    }

    /** jscode2session：换 openid + session_key（用户态签名必需） */
    private Session code2session(String code) throws Exception {
        String query = String.format("appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                appid, secret, code);
        String body;
        if (cloudCallFirst) {
            try {
                body = get("http://api.weixin.qq.com/sns/jscode2session?" + query);
            } catch (Exception e) {
                log.warn("外部接口 | GET http://api.weixin.qq.com/sns/jscode2session（云托管内网通道）失败，"
                        + "降级直连 {}：{}", API_CODE2SESSION, Logs.safe(e));
                body = get(API_CODE2SESSION + "?" + query);
            }
        } else {
            body = get(API_CODE2SESSION + "?" + query);
        }
        JsonNode node = om.readTree(body);
        if (!node.hasNonNull("session_key")) {
            throw new IllegalStateException("code2session 未返回 session_key: " + body);
        }
        Session s = new Session();
        s.openid = node.path("openid").asText(null);
        s.sessionKey = node.get("session_key").asText();
        return s;
    }

    private String get(String url) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    public String genOrderNo() {
        return "P" + LocalDateTime.now().format(ORDER_NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    // ==========================================================
    // 管理端
    // ==========================================================

    /** 订单总览（对账/排查用） */
    public Map<String, Object> adminOverview() {
        Map<String, Object> out = new LinkedHashMap<>();
        Long total = payOrderMapper.selectCount(null);
        Long delivered = payOrderMapper.selectCount(new QueryWrapper<PhotoPayOrder>().eq("status", "DELIVERED"));
        Long init = payOrderMapper.selectCount(new QueryWrapper<PhotoPayOrder>().eq("status", "INIT"));
        Long refunded = payOrderMapper.selectCount(new QueryWrapper<PhotoPayOrder>().eq("status", "REFUNDED"));
        out.put("ok", true);
        out.put("enabled", enabled);
        out.put("env", env);
        out.put("orderTotal", total);
        out.put("orderDelivered", delivered);
        out.put("orderInit", init);
        out.put("orderRefunded", refunded);
        out.put("goods", goods());

        List<Map<String, Object>> orders = new ArrayList<>();
        for (PhotoPayOrder o : payOrderMapper.selectList(new QueryWrapper<PhotoPayOrder>()
                .orderByDesc("created_at").last("limit 30"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", o.getOutTradeNo());
            m.put("openid", maskOpenid(o.getOpenid()));
            m.put("goods", o.getProductId());
            m.put("priceFen", o.getPriceFen());
            m.put("quotaDelta", o.getQuotaDelta());
            m.put("vipDays", o.getVipDays());
            m.put("status", o.getStatus());
            m.put("createdAt", o.getCreatedAt() == null ? null : o.getCreatedAt().toString());
            m.put("deliveredAt", o.getDeliveredAt() == null ? null : o.getDeliveredAt().toString());
            orders.add(m);
        }
        out.put("orders", orders);
        return out;
    }

    private static String maskOpenid(String openid) {
        if (openid == null || openid.length() <= 8) {
            return openid;
        }
        return openid.substring(0, 4) + "****" + openid.substring(openid.length() - 4);
    }

    private static Map<String, Object> fail(String code, String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("code", code);
        out.put("msg", msg);
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }

    private static Integer intVal(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull() || !v.isNumber()) ? null : v.asInt();
    }

    private static Long longVal(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull() || !v.isNumber()) ? null : v.asLong();
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static class Session {
        String openid;
        String sessionKey;
    }
}
