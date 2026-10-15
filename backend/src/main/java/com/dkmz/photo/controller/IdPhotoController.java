package com.dkmz.photo.controller;

import com.dkmz.photo.service.BdaSegmentService;
import com.dkmz.photo.service.PortraitProcessingService;
import com.dkmz.photo.service.PhotoUserVerifier;
import com.dkmz.photo.service.IdPhotoBeautyService;
import com.dkmz.photo.service.WxSecCheckService;
import com.dkmz.photo.support.SegmentRateLimiter;
import com.dkmz.photo.support.BackgroundColorEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 兼容透明 PNG 出口：需 wx.login code，与主制作接口共享用户及全站限额。 */
@RestController
@RequestMapping("/idphoto")
public class IdPhotoController {

    private static final Logger log = LoggerFactory.getLogger(IdPhotoController.class);

    private final BdaSegmentService segmentService;
    private final PortraitProcessingService processing;
    private final SegmentRateLimiter rateLimiter;
    private final PhotoUserVerifier users;
    private final WxSecCheckService secCheck;

    /** 可选共享口令；非空时强制校验请求头 X-Photo-Token（公网直连接口的防刷闸门） */
    @Value("${photo.api-token:}")
    private String apiToken;

    public IdPhotoController(BdaSegmentService segmentService, PortraitProcessingService processing,
            PhotoUserVerifier users, SegmentRateLimiter rateLimiter, WxSecCheckService secCheck) {
        this.segmentService = segmentService; this.processing = processing;
        this.users = users; this.rateLimiter = rateLimiter; this.secCheck = secCheck;
    }

    @PostMapping("/process")
    public Map<String, Object> process(@RequestBody(required = false) Map<String, Object> body,
                                       @RequestHeader(value = "X-Photo-Token", required = false) String token) {
        Map<String, Object> out = new LinkedHashMap<>();

        if (apiToken != null && !apiToken.isEmpty() && !apiToken.equals(token)) {
            log.warn("idphoto/process 口令校验失败");
            out.put("message", "接口口令校验失败");
            return out;
        }
        String user;
        try {
            Object code = body == null ? null : body.get("loginCode");
            user = users.verify(code instanceof String ? (String) code : null, null);
        } catch (Exception e) { out.put("message", e.getMessage()); return out; }
        String denied = rateLimiter.tryAcquire(user);
        if (denied != null) { out.put("message", denied); return out; }
        if (!segmentService.isEnabled()) {
            out.put("message", "云端抠图尚未开通");
            return out;
        }

        String image = body == null ? null : (String) body.get("image");
        String level = body == null ? null : (String) body.get("level");
        boolean acne = body != null && Boolean.TRUE.equals(body.get("acne"));
        if (image == null || image.isEmpty()) {
            out.put("message", "缺少 image 参数");
            return out;
        }

        // ---- 内容安全检测（UGC 合规，与 /api/photo/segment 同策略：fail-closed）----
        if (secCheck.isEnabled()) {
            try {
                secCheck.checkOrThrow(decodeImage(image), null);
            } catch (WxSecCheckService.RiskyContentException e) {
                log.warn("idphoto/process 拦截违规图片 user={} suggest={}", user, e.getSuggest());
                out.put("code", "RISKY_CONTENT");
                out.put("message", "图片内容涉嫌违规，请更换照片后重试");
                return out;
            } catch (Exception e) {
                log.warn("idphoto/process 内容检测不可用: {}", e.toString());
                out.put("code", "SEC_CHECK_UNAVAILABLE");
                out.put("message", "内容安全检测暂不可用，请稍后重试");
                return out;
            }
        }

        boolean portrait = "portrait".equalsIgnoreCase(level == null ? "" : level.trim());
        long t0 = System.currentTimeMillis();
        try {
            PortraitProcessingService.Result result = processing.process(user, image, true,
                    portrait ? IdPhotoBeautyService.Level.PORTRAIT : IdPhotoBeautyService.Level.ID);
            boolean beautyApplied = result.beautyApplied();
            String png = result.png();
            long cost = System.currentTimeMillis() - t0;
            log.info("idphoto/process ok level={} beauty={} acne={} cost={}ms",
                    portrait ? "portrait" : "id", beautyApplied, acne, cost);

            out.put("image", png);

            // 原背景色：端上换底去色边要用它（取不到就不返回该字段，端上回退自己采样）
            BackgroundColorEstimator.Result bg = BackgroundColorEstimator.estimateDetailed(png);
            if (bg != null) {
                out.put("origBg", bg.toRgb());
                log.info("idphoto origBg {}（覆盖率是透明像素占比，非图片内容）", bg);
            } else {
                log.info("idphoto origBg 样本不足，交由端上兜底");
            }

            out.put("beautyApplied", beautyApplied);
            if (acne) out.put("acneApplied", false);
            out.put("costMs", cost);
            return out;
        } catch (IllegalArgumentException e) {
            out.put("message", e.getMessage());
            return out;
        } catch (Exception e) {
            log.error("idphoto/process 异常: {}", e.toString());
            out.put("message", "服务暂时不可用，请稍后重试");
            return out;
        }
    }

    /** base64 → 字节。容忍 data URL 前缀与换行；解码失败交由上层按坏图处理 */
    private static byte[] decodeImage(String base64) {
        String s = base64.trim();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        return Base64.getMimeDecoder().decode(s);
    }
}
