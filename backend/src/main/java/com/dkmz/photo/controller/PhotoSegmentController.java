package com.dkmz.photo.controller;

import com.dkmz.photo.service.BdaSegmentService;
import com.dkmz.photo.service.PortraitProcessingService;
import com.dkmz.photo.service.PortraitCompositeService;
import com.dkmz.photo.service.PhotoUserVerifier;
import com.dkmz.photo.service.PhotoCheckTicketService;
import com.dkmz.photo.service.WxSecCheckService;
import com.dkmz.photo.support.AlphaExtractor;
import com.dkmz.photo.support.BackgroundColorEstimator;
import com.dkmz.photo.support.FlowLog;
import com.dkmz.photo.support.Logs;
import com.dkmz.photo.support.OpenidSupport;
import com.dkmz.photo.support.SegmentRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 云端制作：验证 wx.login 身份，共享持久化限额，按用户短时复用分割，再换底合成。 */
@RestController
@RequestMapping("/api/photo")
public class PhotoSegmentController {

    private static final Logger log = LoggerFactory.getLogger(PhotoSegmentController.class);

    private final BdaSegmentService segmentService;
    private final PortraitProcessingService processing;
    private final PhotoUserVerifier users;
    private final PortraitCompositeService compositeService;
    private final SegmentRateLimiter rateLimiter;
    private final WxSecCheckService secCheck;
    private final PhotoCheckTicketService checkTickets;

    public PhotoSegmentController(BdaSegmentService segmentService, PortraitProcessingService processing,
            PhotoUserVerifier users, PortraitCompositeService compositeService, SegmentRateLimiter rateLimiter,
            WxSecCheckService secCheck, PhotoCheckTicketService checkTickets) {
        this.segmentService = segmentService; this.processing = processing; this.users = users;
        this.compositeService = compositeService; this.rateLimiter = rateLimiter; this.secCheck = secCheck;
        this.checkTickets = checkTickets;
    }

    /** 第一段：整张原图单独送检；检测通过才签发绑定当前用户的一次性凭证。 */
    @PostMapping("/segment/precheck")
    public Map<String, Object> precheck(@RequestBody(required = false) Map<String, Object> body,
                                         @RequestHeader(value = "X-WX-FROM-OPENID", required = false) String fromOpenid,
                                         @RequestHeader(value = "X-WX-OPENID", required = false) String openid) {
        Map<String, Object> out = new LinkedHashMap<>();
        String user;
        try {
            Object code = body == null ? null : body.get("loginCode");
            user = users.verify(code instanceof String ? (String) code : null,
                    OpenidSupport.resolve(fromOpenid, openid, null));
        } catch (Exception e) {
            out.put("ok", false); out.put("code", "LOGIN_REQUIRED"); out.put("msg", "请重新进入后重试");
            return out;
        }
        try {
            if (!checkTickets.allowPrecheck(user)) {
                out.put("ok", false); out.put("code", "RATE_LIMITED");
                out.put("msg", "操作过于频繁，请稍后再试");
                return out;
            }
        } catch (Exception e) {
            log.warn("预检限流存储不可用 user={}", userTag(user));
            out.put("ok", false); out.put("code", "SEC_CHECK_UNAVAILABLE");
            out.put("msg", "内容安全检测暂不可用，请稍后重试");
            return out;
        }
        Object raw = body == null ? null : body.get("checkImage");
        if (!(raw instanceof String image) || image.isBlank()) {
            out.put("ok", false); out.put("code", "BAD_REQUEST"); out.put("msg", "缺少完整原图送检数据");
            return out;
        }
        try {
            if (image.length() > 140_000) throw new IllegalArgumentException("送检图尺寸不合适");
            byte[] bytes = decodeImage(image);
            if (bytes.length == 0 || bytes.length > 100_000) throw new IllegalArgumentException("送检图尺寸不合适");
            if (secCheck.isEnabled()) {
                secCheck.checkOrThrow(bytes, user, "完整原图", FlowLog.tag(FlowLog.SEC_CHECK));
            }
            out.put("ok", true);
            out.put("checkTicket", checkTickets.issue(user));
            return out;
        } catch (WxSecCheckService.RiskyContentException e) {
            out.put("ok", false); out.put("code", "RISKY_CONTENT");
            out.put("msg", "图片内容涉嫌违规，请更换照片后重试");
        } catch (Exception e) {
            log.warn("预检失败 user={} 原因={}", userTag(user), Logs.safe(e.toString()));
            out.put("ok", false); out.put("code", "SEC_CHECK_UNAVAILABLE");
            out.put("msg", "内容安全检测暂不可用，请稍后重试");
        }
        return out;
    }

    @PostMapping("/segment")
    public Map<String, Object> segment(@RequestBody(required = false) Map<String, Object> body,
                                       @RequestHeader(value = "X-WX-FROM-OPENID", required = false) String fromOpenid,
                                       @RequestHeader(value = "X-WX-OPENID", required = false) String openid) {
        Map<String, Object> out = new LinkedHashMap<>();
        long flowStart = System.currentTimeMillis();

        // ---- 1/6 身份校验：真实身份来自一次性登录凭证；平台请求头只用于交叉校验 ----
        long t = System.currentTimeMillis();
        String user;
        try {
            Object code = body == null ? null : body.get("loginCode");
            user = users.verify(code instanceof String ? (String) code : null,
                    OpenidSupport.resolve(fromOpenid, openid, null));
        } catch (Exception e) {
            log.warn("{} | 结果=失败 {} | 耗时={}ms", FlowLog.tag(FlowLog.LOGIN),
                    Logs.safe(e.toString()), System.currentTimeMillis() - t);
            out.put("ok", false); out.put("code", "LOGIN_REQUIRED"); out.put("msg", e.getMessage());
            return out;
        }
        log.info("{} | user={} | 结果=ok | 耗时={}ms", FlowLog.tag(FlowLog.LOGIN),
                userTag(user), System.currentTimeMillis() - t);

        // ---- 2/6 限流校验：全站共享的持久化限额 ----
        t = System.currentTimeMillis();
        String denied = rateLimiter.tryAcquire(user);
        if (denied != null) {
            log.warn("{} | user={} | 结果=拒绝（{}）| 耗时={}ms", FlowLog.tag(FlowLog.RATE_LIMIT),
                    userTag(user), denied, System.currentTimeMillis() - t);
            out.put("ok", false);
            out.put("code", "RATE_LIMITED");
            out.put("msg", denied);
            return out;
        }
        log.info("{} | user={} | 结果=放行 | 耗时={}ms", FlowLog.tag(FlowLog.RATE_LIMIT),
                userTag(user), System.currentTimeMillis() - t);

        if (!segmentService.isEnabled()) {
            log.warn("{} | user={} | 结果=中止（云端精修未开通 BDA_DISABLED）", FlowLog.tag(FlowLog.PORTRAIT),
                    userTag(user));
            out.put("ok", false);
            out.put("code", "BDA_DISABLED");
            out.put("msg", "云端精修尚未开通");
            return out;
        }
        String image = body == null ? null : (String) body.get("image");
        // checkImage：内容安全检测专用的**完整原图**（端上整幅缩放后上传，不裁剪）。
        // 绝不能拿 image（已按证件照构图裁好的工作图）去送检——裁剪会把头部以下的
        // 违规内容裁掉，微信只能判 pass（实测一张裸照裁剪后过检）。
        String checkImage = body == null ? null : (String) body.get("checkImage");
        String checkTicket = body == null ? null : (String) body.get("checkTicket");
        boolean wantBeauty = body != null && Boolean.TRUE.equals(body.get("beauty"));
        // bgHex 存在 ⇒ 成品图模式（后端连换底一起做，端上直接用）
        String bgHex = body == null ? null : (String) body.get("bgHex");
        boolean wantFinished = bgHex != null && !bgHex.trim().isEmpty();
        // alphaOnly ⇒ 「只替换背景」：只回 alpha 灰度图，不美肤、不换底
        boolean alphaOnly = body != null && Boolean.TRUE.equals(body.get("alphaOnly"));
        int outTop = toInt(body == null ? null : body.get("outTop"), 0);
        if (image == null || image.isEmpty()) {
            log.warn("{} | user={} | 结果=中止（缺少 image 参数，无图可送检）",
                    FlowLog.tag(FlowLog.SEC_CHECK), userTag(user));
            out.put("ok", false);
            out.put("code", "BAD_REQUEST");
            out.put("msg", "缺少 image 参数");
            return out;
        }

        // ---- 3/6 内容安全检测（审核整改项：UGC 图片必须先过微信 mediaCheckAsync）----
        // fail-closed：检测通道异常 / 结果超时未回 → 拒绝处理，绝不放过风险图。
        // 送检素材必须是**完整原图**（预检凭证对应的 checkImage，或旧版请求内的 checkImage），不是裁剪后的工作图：
        // 证件照构图只保留头肩，裁剪图会"洗掉"落在框外的违规内容（旧实现的实际漏检原因）。
        // openid 用 verify() 得到的真实 openid（mediaCheckAsync 必填），不用可能为空的请求头。
        // 本阶段的汇总行（含接口地址/结果/判定）由 WxSecCheckService 直接带序号输出，
        // 这里不再重复打一行，避免同一件事在日志里出现两次。
        boolean secChecked = false;
        if (secCheck.isEnabled()) {
            try {
                if (checkTicket != null && !checkTicket.isBlank()) {
                    if (!checkTickets.consume(checkTicket, user)) {
                        out.put("ok", false); out.put("code", "SEC_CHECK_REQUIRED");
                        out.put("msg", "照片检测已过期，请重新制作");
                        return out;
                    }
                    log.info("{} | user={} | 结果=已通过（一次性凭证）", FlowLog.tag(FlowLog.SEC_CHECK), userTag(user));
                } else if (checkImage != null && !checkImage.isEmpty()) {
                    // 兼容旧版小程序：同一请求内先检测完整原图，再做云端抠图。
                    secCheck.checkOrThrow(decodeImage(checkImage), user, "完整原图", FlowLog.tag(FlowLog.SEC_CHECK));
                } else {
                    out.put("ok", false); out.put("code", "SEC_CHECK_REQUIRED");
                    out.put("msg", "缺少完整原图安全检测，请重新制作");
                    return out;
                }
                secChecked = true;
            } catch (WxSecCheckService.RiskyContentException e) {
                // 拦截 / 不可用的汇总行已由 WxSecCheckService 带序号输出（含 label 与判定），此处只回响应
                out.put("ok", false);
                out.put("code", "RISKY_CONTENT");
                out.put("msg", "图片内容涉嫌违规，请更换照片后重试");
                return out;
            } catch (Exception e) {
                out.put("ok", false);
                out.put("code", "SEC_CHECK_UNAVAILABLE");
                out.put("msg", "内容安全检测暂不可用，请稍后重试");
                return out;
            }
        } else {
            log.info("{} | 结果=跳过（SEC_CHECK_ENABLED=false，仅限本地开发）", FlowLog.tag(FlowLog.SEC_CHECK));
        }

        long t0 = System.currentTimeMillis();

        // ---- 出口①：只替换背景（不美颜）→ 只回 alpha 灰度图 ----
        // 优先级最高：端上明确只要 alpha 时，不做美肤也不再传 RGB（流量降一个数量级）
        if (alphaOnly) {
            try {
                String png = processing.process(user, image, false, null).png();
                log.info("{} | user={} | 结果=ok（不美肤，仅分割）| 耗时={}ms", FlowLog.tag(FlowLog.PORTRAIT),
                        userTag(user), System.currentTimeMillis() - t0);
                AlphaExtractor.Result a = AlphaExtractor.extract(png);
                long cost = System.currentTimeMillis() - t0;
                log.info("{} | 结果=跳过（只替换背景，不铺底色）", FlowLog.tag(FlowLog.COMPOSE));
                // 只记尺寸/体积/耗时，绝不落图片内容
                log.info("{} | user={} | 结果=ok（alpha 灰度图）| 尺寸={}x{} alphaKB={} | 处理={}ms 总={}ms",
                        FlowLog.tag(FlowLog.OUTPUT), userTag(user), a.width, a.height,
                        Math.round(a.base64.length() / 1024.0), cost, System.currentTimeMillis() - flowStart);
                out.put("ok", true);
                out.put("alpha", a.base64);
                out.put("width", a.width);
                out.put("height", a.height);
                out.put("beautyApplied", false);
                out.put("costMs", cost);
                if (secChecked) out.put("secChecked", true);
                return out;
            } catch (Exception e) {
                return fail(out, e, FlowLog.PORTRAIT);
            }
        }

        try {
            PortraitProcessingService.Result portrait = processing.process(user, image, wantBeauty, null);
            boolean beautyApplied = portrait.beautyApplied();
            String beautySkippedReason = portrait.beautySkippedReason();
            String input = portrait.input();
            String png = portrait.png();
            long cost = System.currentTimeMillis() - t0;
            log.info("{} | user={} | beauty={} | 结果=ok{} | 耗时={}ms", FlowLog.tag(FlowLog.PORTRAIT),
                    userTag(user), beautyApplied,
                    beautySkippedReason == null ? "" : "（美肤降级：" + beautySkippedReason + "）", cost);

            // ---- 5/6 换底合成：仅成品图模式（端上直接拿成品），透明底出口跳过 ----
            String payload;
            if (wantFinished) {
                long t5 = System.currentTimeMillis();
                payload = compositeService.composite(png, bgHex, outTop, input);
                log.info("{} | bg={} outTop={} | 结果=ok | 耗时={}ms", FlowLog.tag(FlowLog.COMPOSE),
                        bgHex, outTop, System.currentTimeMillis() - t5);
            } else {
                payload = png;
                log.info("{} | 结果=跳过（透明底输出，端上自行换底）", FlowLog.tag(FlowLog.COMPOSE));
            }

            // ---- 6/6 输出 ----
            // 原背景色：只有「兼容模式」（返回透明底、端上自己换底）才需要。
            // 成品图模式下后端已铺好底色，端上不再做去色边，算它也是白算。
            // 统计对象必须是透明底 PNG，不是成品图。
            String bgTag = "不适用（端上不需要）";
            if (!wantFinished) {
                BackgroundColorEstimator.Result bg = BackgroundColorEstimator.estimateDetailed(png);
                if (bg != null) {
                    int[] rgb = bg.toRgb();
                    out.put("origBg", rgb);
                    bgTag = rgb[0] + "," + rgb[1] + "," + rgb[2];
                } else {
                    bgTag = "样本不足（端上兜底）";
                }
            }
            log.info("{} | user={} | 结果=ok finished={} origBg={} | 响应体={}KB(base64) | 处理={}ms 总={}ms",
                    FlowLog.tag(FlowLog.OUTPUT), userTag(user), wantFinished, bgTag,
                    Math.round(payload.length() / 1024.0), cost, System.currentTimeMillis() - flowStart);

            out.put("ok", true);
            out.put("image", payload);
            if (wantFinished) out.put("finished", true);
            // 内容安全检测已通过（端上「处理明细」会展示这一环）
            if (secChecked) out.put("secChecked", true);

            out.put("beautyApplied", beautyApplied);
            if (beautySkippedReason != null) out.put("beautySkippedReason", beautySkippedReason);
            out.put("costMs", cost);
            return out;
        } catch (Exception e) {
            return fail(out, e, FlowLog.PORTRAIT);
        }
    }

    /** 统一的失败响应：按异常类型映射错误码，只记消息不记图片。step 用于带出失败的流程阶段序号 */
    private Map<String, Object> fail(Map<String, Object> out, Exception e, int step) {
        out.put("ok", false);
        if (e instanceof IllegalArgumentException) {
            log.warn("{} | 结果=失败（坏图）{}", FlowLog.tag(step), Logs.safe(e.getMessage()));
            out.put("code", "BAD_IMAGE");
            out.put("msg", e.getMessage());
        } else if (e instanceof IllegalStateException) {
            log.warn("{} | 结果=失败 {}", FlowLog.tag(step), Logs.safe(e.getMessage()));
            out.put("code", "BDA_FAIL");
            out.put("msg", e.getMessage());
        } else {
            log.error("{} | 结果=异常: {}", FlowLog.tag(step), Logs.safe(e.toString()));
            out.put("code", "BDA_FAIL");
            out.put("msg", "服务暂时不可用，请稍后重试");
        }
        return out;
    }

    /** 日志用的用户标识：只取 openid 前 8 位，绝不整串落日志 */
    private static String userTag(String openid) {
        return openid == null ? "anonymous" : openid.substring(0, Math.min(8, openid.length())) + "…";
    }

    /** base64 → 字节。容忍 data URL 前缀与换行；解码失败视为坏图 */
    private static byte[] decodeImage(String base64) {
        String s = base64.trim();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        return Base64.getMimeDecoder().decode(s);
    }

    /** JSON 数字/字符串都能吃，非法值退回默认值 */
    private static int toInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignore) {
                return def;
            }
        }
        return def;
    }
}
