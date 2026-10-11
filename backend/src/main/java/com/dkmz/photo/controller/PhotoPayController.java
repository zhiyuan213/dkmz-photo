package com.dkmz.photo.controller;

import com.dkmz.photo.dto.PayGoods;
import com.dkmz.photo.service.EntitlementService;
import com.dkmz.photo.service.PhotoPayService;
import com.dkmz.photo.support.OpenidSupport;
import com.dkmz.photo.support.PushSignUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟支付 + 用户权益接口。
 *
 * 商品与权益：
 *   GET  /api/photo/pay/goods                 商品列表（单张 / 套装 / 年会员）
 *   POST /api/photo/pay/order                 下单，返回 signData / paySig / signature
 *   GET  /api/photo/pay/order/{no}            查本地订单状态（支付后确认到账）
 *   GET  /api/photo/pay/my-orders             订单中心列表（提审时登记订单中心 path 用）
 *   GET  /api/photo/entitlement               当前用户权益（剩余张数 / 会员到期）
 *   POST /api/photo/entitlement/consume       导出前消耗一次（clientNo 幂等）
 *
 * 推送与运维（token / 内网）：
 *   GET  /api/photo/pay/notify                微信推送 URL 校验（原样回 echostr）
 *   POST /api/photo/pay/notify                发货 / 退款 / iOS 退款问询 推送入口
 *   POST /api/photo/pay/recheck?token=        兜底查单（补发 + 回收），挂云托管定时任务
 *   GET  /api/photo/pay/admin/overview?token= 订单总览
 *   POST /api/photo/pay/admin/entitlement?token=&openid=&paidQuota=&vipDays=  权益修数
 */
@RestController
@RequestMapping("/api/photo")
public class PhotoPayController {

    private static final Logger log = LoggerFactory.getLogger(PhotoPayController.class);

    private final ObjectMapper om = new ObjectMapper();
    private final PhotoPayService payService;
    private final EntitlementService entitlementService;

    @Value("${wx.pay.push-token:}")
    private String pushToken;

    @Value("${photo.job-token:change-me}")
    private String jobToken;

    public PhotoPayController(PhotoPayService payService, EntitlementService entitlementService) {
        this.payService = payService;
        this.entitlementService = entitlementService;
    }

    // ==========================================================
    // 商品 / 下单 / 查单
    // ==========================================================

    @GetMapping("/pay/goods")
    public Map<String, Object> goods() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<PayGoods> list = payService.goods();
        out.put("ok", true);
        out.put("enabled", payService.isEnabled() && !list.isEmpty());
        out.put("goods", list);
        return out;
    }

    @PostMapping("/pay/order")
    public Map<String, Object> createOrder(
            @RequestHeader(value = "X-WX-FROM-OPENID", required = false) String fromOpenid,
            @RequestHeader(value = "X-WX-OPENID", required = false) String cloudOpenid,
            @RequestBody(required = false) Map<String, Object> body) {
        String openid = OpenidSupport.resolve(fromOpenid, cloudOpenid,
                body == null ? null : str(body.get("openid")));
        String goodsId = body == null ? null : str(body.get("goodsId"));
        String code = body == null ? null : str(body.get("code"));
        Map<String, Object> res = payService.createOrder(openid, goodsId, code);
        // 下单是支付链路起点：这行日志能直接区分「openid 缺失 / 商品不存在 / 签名失败」
        log.info("下单结果 openid={} goodsId={} hasCode={} ok={} msg={}",
                openid, goodsId, code != null && !code.isBlank(), res.get("ok"), res.get("msg"));
        return res;
    }

    @GetMapping("/pay/order/{outTradeNo}")
    public Map<String, Object> order(@PathVariable String outTradeNo,
                                     @RequestHeader(value = "X-WX-FROM-OPENID", required = false) String fromOpenid,
                                     @RequestHeader(value = "X-WX-OPENID", required = false) String cloudOpenid) {
        return payService.localOrder(outTradeNo, OpenidSupport.resolve(fromOpenid, cloudOpenid, null));
    }

    /** 订单中心：当前用户订单列表（微信要求交易类小程序必须提供） */
    @GetMapping("/pay/my-orders")
    public Map<String, Object> myOrders(
            @RequestHeader(value = "X-WX-FROM-OPENID", required = false) String fromOpenid,
            @RequestHeader(value = "X-WX-OPENID", required = false) String cloudOpenid) {
        return payService.myOrders(OpenidSupport.resolve(fromOpenid, cloudOpenid, null));
    }

    // ==========================================================
    // 权益
    // ==========================================================

    @GetMapping("/entitlement")
    public Map<String, Object> entitlement(
            @RequestHeader(value = "X-WX-FROM-OPENID", required = false) String fromOpenid,
            @RequestHeader(value = "X-WX-OPENID", required = false) String cloudOpenid) {
        String openid = OpenidSupport.resolve(fromOpenid, cloudOpenid, null);
        if (openid == null || openid.isBlank()) {
            return Map.of("ok", false, "code", "NO_OPENID", "msg", "未获取到用户身份");
        }
        return entitlementService.view(openid);
    }

    /**
     * 导出前消耗一次。前端在「保存无水印高清」时调用，clientNo 由前端生成（幂等）。
     * 返回 ok=false + code=QUOTA_EXHAUSTED 时引导购买。
     */
    @PostMapping("/entitlement/consume")
    public Map<String, Object> consume(
            @RequestHeader(value = "X-WX-FROM-OPENID", required = false) String fromOpenid,
            @RequestHeader(value = "X-WX-OPENID", required = false) String cloudOpenid,
            @RequestBody(required = false) Map<String, Object> body) {
        String openid = OpenidSupport.resolve(fromOpenid, cloudOpenid,
                body == null ? null : str(body.get("openid")));
        if (openid == null || openid.isBlank()) {
            return Map.of("ok", false, "code", "NO_OPENID", "msg", "未获取到用户身份");
        }
        String clientNo = body == null ? null : str(body.get("clientNo"));
        String specId = body == null ? null : str(body.get("specId"));
        Boolean specOk = body == null || body.get("specOk") == null
                ? null
                : Boolean.parseBoolean(str(body.get("specOk")));
        return entitlementService.consume(openid, clientNo, specId, specOk);
    }

    // ==========================================================
    // 运维
    // ==========================================================

    @PostMapping("/pay/recheck")
    public Map<String, Object> recheck(@RequestParam(required = false) String token) {
        if (!jobToken.equals(token)) {
            return Map.of("ok", false, "msg", "token 无效");
        }
        return payService.recheckPending();
    }

    @GetMapping("/pay/admin/overview")
    public Map<String, Object> adminOverview(@RequestParam(required = false) String token) {
        if (!jobToken.equals(token)) {
            return Map.of("ok", false, "msg", "token 无效");
        }
        return payService.adminOverview();
    }

    @PostMapping("/pay/admin/entitlement")
    public Map<String, Object> adminSetEntitlement(@RequestParam(required = false) String token,
                                                   @RequestParam(required = false) String openid,
                                                   @RequestParam(required = false) Integer paidQuota,
                                                   @RequestParam(required = false) Integer vipDays) {
        if (!jobToken.equals(token)) {
            return Map.of("ok", false, "msg", "token 无效");
        }
        if (openid == null || openid.isBlank()) {
            return Map.of("ok", false, "msg", "参数无效：需要 openid");
        }
        return entitlementService.adminSet(openid, paidQuota, vipDays);
    }

    // ==========================================================
    // 发货推送
    // ==========================================================

    /** URL 校验：微信配置推送地址时会 GET 一次，需原样返回 echostr */
    @GetMapping(value = "/pay/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public String verify(@RequestParam(required = false) String signature,
                         @RequestParam(required = false) String timestamp,
                         @RequestParam(required = false) String nonce,
                         @RequestParam(required = false) String echostr) {
        if (pushToken != null && !pushToken.isBlank() && !PushSignUtil.check(pushToken, timestamp, nonce, signature)) {
            log.warn("推送 URL 校验签名不通过（timestamp={} nonce={}）", timestamp, nonce);
            return "signature check failed";
        }
        log.info("推送 URL 校验通过");
        return echostr == null ? "ok" : echostr;
    }

    /**
     * 道具发货 / 退款推送。微信按配置格式发 JSON 或 XML，两种都要能解析并按格式应答。
     * 应答不合法微信最多重推 15 次，因此这里必须「能处理就返回成功」。
     */
    @PostMapping(value = "/pay/notify", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public ResponseEntity<String> notify(@RequestBody(required = false) String raw,
                                         @RequestHeader(value = "x-wx-sources", required = false) String wxSources,
                                         @RequestParam(required = false) String signature,
                                         @RequestParam(required = false) String timestamp,
                                         @RequestParam(required = false) String nonce) {
        boolean xml = raw != null && raw.trim().startsWith("<");
        log.info("收到支付消息推送: {} (x-wx-sources={})", raw, wxSources);

        // 云托管转发的微信推送实测既不带 signature 也不带 x-wx-sources：
        // 「无签名」不能判失败（曾因此漏发货），改由 deliver() 查单核验保真。
        boolean viaWxInternal = wxSources != null && !wxSources.isBlank();
        boolean sigPresent = signature != null && !signature.isBlank();
        boolean tokenConfigured = pushToken != null && !pushToken.isBlank();
        boolean sigVerified = viaWxInternal
                || (tokenConfigured && sigPresent && PushSignUtil.check(pushToken, timestamp, nonce, signature));
        if (tokenConfigured && sigPresent && !sigVerified) {
            log.warn("推送签名校验不通过，拒绝处理");
            return reply(xml, false, "signature check failed");
        }

        JsonNode node;
        try {
            node = xml ? PushSignUtil.xmlToJson(om, raw) : om.readTree(raw);
        } catch (Exception e) {
            log.error("推送报文解析失败: {}", e.toString());
            return reply(xml, false, "parse failed");
        }

        String event = node.path("Event").asText("");

        if ("xpay_goods_deliver_notify".equals(event)) {
            boolean ok;
            try {
                ok = payService.deliver(node, sigVerified);
            } catch (Exception e) {
                log.error("发货处理异常: {}", e.toString());
                ok = false;
            }
            return reply(xml, ok, ok ? "success" : "deliver failed");
        }

        if ("xpay_refund_notify".equals(event)) {
            boolean ok;
            try {
                ok = payService.refund(node);
            } catch (Exception e) {
                log.error("退款处理异常: {}", e.toString());
                ok = false;
            }
            return reply(xml, ok, ok ? "success" : "refund failed");
        }

        if ("xpay_subscribe_ios_refund_query_notify".equals(event)) {
            Map<String, Object> decision;
            try {
                decision = payService.decideIosRefundQuery(node);
            } catch (Exception e) {
                log.error("iOS 退款问询处理异常: {}", e.toString());
                decision = Map.of("result_code", 1, "result_info", "拒绝退款",
                        "evidence", "系统繁忙，暂时无法受理退款申请，请稍后重试。");
            }
            return iosRefundReply(decision);
        }

        log.info("非发货/退款事件（Event={}），直接确认", event);
        return reply(xml, true, "success");
    }

    private ResponseEntity<String> iosRefundReply(Map<String, Object> decision) {
        String decisionJson;
        try {
            decisionJson = om.writeValueAsString(decision);
        } catch (Exception e) {
            decisionJson = "{\"result_code\":1,\"result_info\":\"拒绝退款\",\"evidence\":\"系统异常\"}";
        }
        String body = "{\"ErrCode\":0,\"ErrMsg\":\"success\",\"IosRefundQueryResponse\":" + decisionJson + "}";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<String> reply(boolean xml, boolean ok, String msg) {
        String body;
        MediaType type;
        if (xml) {
            type = MediaType.APPLICATION_XML;
            body = ok
                    ? "<xml><Errcode>0</Errcode><ErrMsg><![CDATA[success]]></ErrMsg></xml>"
                    : "<xml><Errcode>1</Errcode><ErrMsg><![CDATA[" + msg + "]]></ErrMsg></xml>";
        } else {
            type = MediaType.APPLICATION_JSON;
            body = "{\"ErrCode\":" + (ok ? 0 : 1) + ",\"ErrMsg\":\"" + msg + "\"}";
        }
        return ResponseEntity.ok().contentType(type).body(body);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
