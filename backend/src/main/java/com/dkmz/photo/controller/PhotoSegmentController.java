package com.dkmz.photo.controller;

import com.dkmz.photo.service.BdaSegmentService;
import com.dkmz.photo.service.PortraitProcessingService;
import com.dkmz.photo.service.PortraitCompositeService;
import com.dkmz.photo.service.PhotoUserVerifier;
import com.dkmz.photo.service.WxSecCheckService;
import com.dkmz.photo.support.AlphaExtractor;
import com.dkmz.photo.support.BackgroundColorEstimator;
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

    public PhotoSegmentController(BdaSegmentService segmentService, PortraitProcessingService processing,
            PhotoUserVerifier users, PortraitCompositeService compositeService, SegmentRateLimiter rateLimiter,
            WxSecCheckService secCheck) {
        this.segmentService = segmentService; this.processing = processing; this.users = users;
        this.compositeService = compositeService; this.rateLimiter = rateLimiter; this.secCheck = secCheck;
    }

    @PostMapping("/segment")
    public Map<String, Object> segment(@RequestBody(required = false) Map<String, Object> body,
                                       @RequestHeader(value = "X-WX-FROM-OPENID", required = false) String fromOpenid,
                                       @RequestHeader(value = "X-WX-OPENID", required = false) String openid) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 真实身份来自一次性登录凭证；平台请求头只用于交叉校验。
        String user;
        try {
            Object code = body == null ? null : body.get("loginCode");
            user = users.verify(code instanceof String ? (String) code : null,
                    OpenidSupport.resolve(fromOpenid, openid, null));
        } catch (Exception e) {
            out.put("ok", false); out.put("code", "LOGIN_REQUIRED"); out.put("msg", e.getMessage());
            return out;
        }
        String denied = rateLimiter.tryAcquire(user);
        if (denied != null) {
            log.warn("segment 限流 user={}", userTag(user));
            out.put("ok", false);
            out.put("code", "RATE_LIMITED");
            out.put("msg", denied);
            return out;
        }
        if (!segmentService.isEnabled()) {
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
        boolean wantBeauty = body != null && Boolean.TRUE.equals(body.get("beauty"));
        // bgHex 存在 ⇒ 成品图模式（后端连换底一起做，端上直接用）
        String bgHex = body == null ? null : (String) body.get("bgHex");
        boolean wantFinished = bgHex != null && !bgHex.trim().isEmpty();
        // alphaOnly ⇒ 「只替换背景」：只回 alpha 灰度图，不美肤、不换底
        boolean alphaOnly = body != null && Boolean.TRUE.equals(body.get("alphaOnly"));
        int outTop = toInt(body == null ? null : body.get("outTop"), 0);
        if (image == null || image.isEmpty()) {
            out.put("ok", false);
            out.put("code", "BAD_REQUEST");
            out.put("msg", "缺少 image 参数");
            return out;
        }

        // ---- 内容安全检测（审核整改项：UGC 图片必须先过微信 mediaCheckAsync）----
        // fail-closed：检测通道异常 / 结果超时未回 → 拒绝处理，绝不放过风险图。
        // 送检素材必须是**完整原图**（checkImage），不是裁剪后的工作图：
        // 证件照构图只保留头肩，裁剪图会"洗掉"落在框外的违规内容（旧实现的实际漏检原因）。
        // openid 用 verify() 得到的真实 openid（mediaCheckAsync 必填），不用可能为空的请求头。
        boolean secChecked = false;
        if (secCheck.isEnabled()) {
            try {
                boolean hasWholePicture = checkImage != null && !checkImage.isEmpty();
                if (!hasWholePicture) {
                    // 端上未上传完整原图 ⇒ 只能退而检测裁剪工作图，裁剪框外的内容检不到。
                    // 生产端上必须传 checkImage（前后端同批发布）；这里留 WARN 便于发现版本不同步。
                    log.warn("segment 未收到 checkImage（完整原图），退化为检测裁剪工作图 user={}", userTag(user));
                }
                secCheck.checkOrThrow(decodeImage(hasWholePicture ? checkImage : image), user,
                        hasWholePicture ? "完整原图" : "裁剪工作图(缺 checkImage!)");
                secChecked = true;
            } catch (WxSecCheckService.RiskyContentException e) {
                log.warn("segment 拦截违规图片 user={} suggest={}", userTag(user), e.getSuggest());
                out.put("ok", false);
                out.put("code", "RISKY_CONTENT");
                out.put("msg", "图片内容涉嫌违规，请更换照片后重试");
                return out;
            } catch (Exception e) {
                log.warn("segment 内容检测不可用 user={}: {}", userTag(user), e.toString());
                out.put("ok", false);
                out.put("code", "SEC_CHECK_UNAVAILABLE");
                out.put("msg", "内容安全检测暂不可用，请稍后重试");
                return out;
            }
        }

        long t0 = System.currentTimeMillis();

        // ---- 出口①：只替换背景（不美颜）→ 只回 alpha 灰度图 ----
        // 优先级最高：端上明确只要 alpha 时，不做美肤也不再传 RGB（流量降一个数量级）
        if (alphaOnly) {
            try {
                String png = processing.process(user, image, false, null).png();
                AlphaExtractor.Result a = AlphaExtractor.extract(png);
                long cost = System.currentTimeMillis() - t0;
                // 只记尺寸/体积/耗时，绝不落图片内容
                log.info("segment alphaOnly user={} {}x{} alphaKB={} cost={}ms",
                        userTag(openid), a.width, a.height,
                        Math.round(a.base64.length() / 1024.0), cost);
                out.put("ok", true);
                out.put("alpha", a.base64);
                out.put("width", a.width);
                out.put("height", a.height);
                out.put("beautyApplied", false);
                out.put("costMs", cost);
                if (secChecked) out.put("secChecked", true);
                return out;
            } catch (Exception e) {
                return fail(out, e);
            }
        }

        try {
            PortraitProcessingService.Result portrait = processing.process(user, image, wantBeauty, null);
            boolean beautyApplied = portrait.beautyApplied();
            String beautySkippedReason = portrait.beautySkippedReason();
            String input = portrait.input();
            String png = portrait.png();
            String payload = wantFinished ? compositeService.composite(png, bgHex, outTop, input) : png;
            long cost = System.currentTimeMillis() - t0;
            // 只记 openid 前缀与耗时，不落任何图片内容
            log.info("segment 调用 user={} beauty={} finished={} cost={}ms",
                    userTag(openid), beautyApplied, wantFinished, cost);
            out.put("ok", true);
            out.put("image", payload);
            if (wantFinished) out.put("finished", true);
            // 内容安全检测已通过（端上「处理明细」会展示这一环）
            if (secChecked) out.put("secChecked", true);

            // 原背景色：只有「兼容模式」（返回透明底、端上自己换底）才需要。
            // 成品图模式下后端已铺好底色，端上不再做去色边，算它也是白算。
            // 统计对象必须是透明底 PNG，不是成品图。
            if (!wantFinished) {
                BackgroundColorEstimator.Result bg = BackgroundColorEstimator.estimateDetailed(png);
                if (bg != null) {
                    out.put("origBg", bg.toRgb());
                    log.info("segment origBg {}（覆盖率是透明像素占比，非图片内容）", bg);
                } else {
                    log.info("segment origBg 样本不足，交由端上兜底");
                }
            }

            out.put("beautyApplied", beautyApplied);
            if (beautySkippedReason != null) out.put("beautySkippedReason", beautySkippedReason);
            out.put("costMs", cost);
            return out;
        } catch (Exception e) {
            return fail(out, e);
        }
    }

    /** 统一的失败响应：按异常类型映射错误码，只记消息不记图片 */
    private Map<String, Object> fail(Map<String, Object> out, Exception e) {
        out.put("ok", false);
        if (e instanceof IllegalArgumentException) {
            out.put("code", "BAD_IMAGE");
            out.put("msg", e.getMessage());
        } else if (e instanceof IllegalStateException) {
            out.put("code", "BDA_FAIL");
            out.put("msg", e.getMessage());
        } else {
            log.error("segment 异常: {}", e.toString());
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
