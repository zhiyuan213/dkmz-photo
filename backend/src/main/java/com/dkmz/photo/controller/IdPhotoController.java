package com.dkmz.photo.controller;

import com.dkmz.photo.service.BdaSegmentService;
import com.dkmz.photo.service.PortraitProcessingService;
import com.dkmz.photo.service.PhotoUserVerifier;
import com.dkmz.photo.service.IdPhotoBeautyService;
import com.dkmz.photo.service.WxSecCheckService;
import com.dkmz.photo.support.BackgroundColorEstimator;
import com.dkmz.photo.support.FlowLog;
import com.dkmz.photo.support.Logs;
import com.dkmz.photo.support.SegmentRateLimiter;
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
        long flowStart = System.currentTimeMillis();

        if (apiToken != null && !apiToken.isEmpty() && !apiToken.equals(token)) {
            log.warn("idphoto/process 口令校验失败");
            out.put("message", "接口口令校验失败");
            return out;
        }

        // ---- 1/6 身份校验 ----
        long t = System.currentTimeMillis();
        String user;
        try {
            Object code = body == null ? null : body.get("loginCode");
            user = users.verify(code instanceof String ? (String) code : null, null);
        } catch (Exception e) {
            log.warn("{} | 结果=失败 {} | 耗时={}ms", FlowLog.tag(FlowLog.LOGIN),
                    Logs.safe(e.toString()), System.currentTimeMillis() - t);
            out.put("message", e.getMessage());
            return out;
        }
        log.info("{} | user={} | 结果=ok | 耗时={}ms", FlowLog.tag(FlowLog.LOGIN),
                userTag(user), System.currentTimeMillis() - t);

        // ---- 2/6 限流校验 ----
        t = System.currentTimeMillis();
        String denied = rateLimiter.tryAcquire(user);
        if (denied != null) {
            log.warn("{} | user={} | 结果=拒绝（{}）| 耗时={}ms", FlowLog.tag(FlowLog.RATE_LIMIT),
                    userTag(user), denied, System.currentTimeMillis() - t);
            out.put("message", denied);
            return out;
        }
        log.info("{} | user={} | 结果=放行 | 耗时={}ms", FlowLog.tag(FlowLog.RATE_LIMIT),
                userTag(user), System.currentTimeMillis() - t);

        if (!segmentService.isEnabled()) {
            log.warn("{} | user={} | 结果=中止（云端精修未开通 BDA_DISABLED）", FlowLog.tag(FlowLog.PORTRAIT),
                    userTag(user));
            out.put("message", "云端抠图尚未开通");
            return out;
        }

        String image = body == null ? null : (String) body.get("image");
        // 与 /api/photo/segment 同策略：送检用完整原图（checkImage），不用裁剪后的工作图
        String checkImage = body == null ? null : (String) body.get("checkImage");
        String level = body == null ? null : (String) body.get("level");
        boolean acne = body != null && Boolean.TRUE.equals(body.get("acne"));
        if (image == null || image.isEmpty()) {
            log.warn("{} | user={} | 结果=中止（缺少 image 参数，无图可送检）",
                    FlowLog.tag(FlowLog.SEC_CHECK), userTag(user));
            out.put("message", "缺少 image 参数");
            return out;
        }

        // ---- 3/6 内容安全检测（UGC 合规，与 /api/photo/segment 同策略：fail-closed）----
        // 送检完整原图；缺失时退化为裁剪图并留 WARN（裁剪会把框外违规内容洗掉）
        // openid 用 verify() 得到的真实 openid（mediaCheckAsync 必填）
        // 汇总行（含接口/结果/判定）由 WxSecCheckService 带序号直接输出，这里不重复打
        if (secCheck.isEnabled()) {
            try {
                boolean hasWholePicture = checkImage != null && !checkImage.isEmpty();
                if (!hasWholePicture) {
                    log.warn("idphoto/process 未收到 checkImage（完整原图），退化为检测裁剪工作图 user={}",
                            userTag(user));
                }
                secCheck.checkOrThrow(decodeImage(hasWholePicture ? checkImage : image), user,
                        hasWholePicture ? "完整原图" : "裁剪工作图(缺 checkImage!)",
                        FlowLog.tag(FlowLog.SEC_CHECK));
            } catch (WxSecCheckService.RiskyContentException e) {
                out.put("code", "RISKY_CONTENT");
                out.put("message", "图片内容涉嫌违规，请更换照片后重试");
                return out;
            } catch (Exception e) {
                out.put("code", "SEC_CHECK_UNAVAILABLE");
                out.put("message", "内容安全检测暂不可用，请稍后重试");
                return out;
            }
        } else {
            log.info("{} | 结果=跳过（SEC_CHECK_ENABLED=false，仅限本地开发）", FlowLog.tag(FlowLog.SEC_CHECK));
        }

        boolean portrait = "portrait".equalsIgnoreCase(level == null ? "" : level.trim());
        long t0 = System.currentTimeMillis();
        try {
            // ---- 4/6 人像处理（美肤 + 分割）----
            PortraitProcessingService.Result result = processing.process(user, image, true,
                    portrait ? IdPhotoBeautyService.Level.PORTRAIT : IdPhotoBeautyService.Level.ID);
            boolean beautyApplied = result.beautyApplied();
            String png = result.png();
            long cost = System.currentTimeMillis() - t0;
            log.info("{} | user={} | level={} beauty={} acne={} | 结果=ok | 耗时={}ms",
                    FlowLog.tag(FlowLog.PORTRAIT), userTag(user), portrait ? "portrait" : "id",
                    beautyApplied, acne, cost);

            // ---- 5/6 换底合成：本出口只回透明底 PNG，换底由端上完成 ----
            log.info("{} | 结果=跳过（透明底出口，端上自行换底）", FlowLog.tag(FlowLog.COMPOSE));

            // ---- 6/6 输出 ----
            out.put("image", png);

            // 原背景色：端上换底去色边要用它（取不到就不返回该字段，端上回退自己采样）
            BackgroundColorEstimator.Result bg = BackgroundColorEstimator.estimateDetailed(png);
            String bgTag;
            if (bg != null) {
                int[] rgb = bg.toRgb();
                out.put("origBg", rgb);
                bgTag = rgb[0] + "," + rgb[1] + "," + rgb[2];
            } else {
                bgTag = "样本不足（端上兜底）";
            }
            log.info("{} | user={} | 结果=ok origBg={} | 响应体={}KB(base64) | 处理={}ms 总={}ms",
                    FlowLog.tag(FlowLog.OUTPUT), userTag(user), bgTag,
                    Math.round(png.length() / 1024.0), cost, System.currentTimeMillis() - flowStart);

            out.put("beautyApplied", beautyApplied);
            if (acne) out.put("acneApplied", false);
            out.put("costMs", cost);
            return out;
        } catch (IllegalArgumentException e) {
            log.warn("{} | user={} | 结果=失败（坏图）{}", FlowLog.tag(FlowLog.PORTRAIT),
                    userTag(user), Logs.safe(e.getMessage()));
            out.put("message", e.getMessage());
            return out;
        } catch (Exception e) {
            log.error("{} | user={} | 结果=异常: {}", FlowLog.tag(FlowLog.PORTRAIT),
                    userTag(user), Logs.safe(e.toString()));
            out.put("message", "服务暂时不可用，请稍后重试");
            return out;
        }
    }

    /** 日志用的用户标识：只取 openid 前 8 位，绝不整串落日志 */
    private static String userTag(String openid) {
        return openid == null ? "anonymous" : openid.substring(0, Math.min(8, openid.length())) + "…";
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
