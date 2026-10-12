package com.dkmz.photo.controller;

import com.dkmz.photo.service.BdaSegmentService;
import com.dkmz.photo.service.CustomSegmentService;
import com.dkmz.photo.service.SkinBeautyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 云端精修（腾讯云 BDA 人像分割 + 可选美肤）接口。
 *
 *   POST /api/photo/segment   body: { image: "<base64/dataURL>", beauty: true|false }
 *   返回: { ok:true, image:"<透明底 PNG base64>", beautyApplied:bool, costMs }
 *         { ok:false, code:"BDA_DISABLED"|"BAD_REQUEST"|"IMAGE_TOO_LARGE"|"BDA_FAIL", msg }
 *
 * 处理顺序（beauty=true 且美肤开关开启时）：
 *   1. 自定义分割拿「面部皮肤」mask（失败则跳过美肤，不阻断）
 *   2. 在 RGB 原图上做保守提亮+磨皮（必须先于抠图——透明底图进 Java ImageIO 会被铺黑）
 *   3. SegmentPortraitPic 抠出透明底 PNG
 * 每次美肤+抠图消耗 2 次 BDA 调用（与免费额度共享）。
 *
 * 设计原则（不落盘）：
 *   - 只收 base64，处理完立即返回，全程内存
 *   - 不写文件、不写数据库、不写缓存
 *   - 任何日志不打印图片内容（一个 log.info(base64) 就会把用户人脸写进日志）
 */
@RestController
@RequestMapping("/api/photo")
public class PhotoSegmentController {

    private static final Logger log = LoggerFactory.getLogger(PhotoSegmentController.class);

    private final BdaSegmentService segmentService;
    private final CustomSegmentService customSegmentService;
    private final SkinBeautyService skinBeautyService;

    /** 美肤总开关（每次美肤多消耗 1 次 BDA 调用，独立控制成本） */
    @Value("${photo.bda.beauty-enabled:false}")
    private boolean beautyEnabled;

    public PhotoSegmentController(BdaSegmentService segmentService,
                                  CustomSegmentService customSegmentService,
                                  SkinBeautyService skinBeautyService) {
        this.segmentService = segmentService;
        this.customSegmentService = customSegmentService;
        this.skinBeautyService = skinBeautyService;
    }

    @PostMapping("/segment")
    public Map<String, Object> segment(@RequestBody(required = false) Map<String, Object> body,
                                       @RequestHeader(value = "X-WX-OPENID", required = false) String openid) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!segmentService.isEnabled()) {
            out.put("ok", false);
            out.put("code", "BDA_DISABLED");
            out.put("msg", "云端精修尚未开通");
            return out;
        }
        String image = body == null ? null : (String) body.get("image");
        boolean wantBeauty = body != null && Boolean.TRUE.equals(body.get("beauty"));
        if (image == null || image.isEmpty()) {
            out.put("ok", false);
            out.put("code", "BAD_REQUEST");
            out.put("msg", "缺少 image 参数");
            return out;
        }

        long t0 = System.currentTimeMillis();
        boolean beautyApplied = false;
        String input = image;
        if (wantBeauty && beautyEnabled) {
            try {
                String mask = customSegmentService.getFaceSkinMask(image);
                input = skinBeautyService.beautify(image, mask);
                beautyApplied = true;
            } catch (Exception e) {
                // 美肤失败不阻断抠图主流程，退化为纯抠图
                log.warn("美肤跳过: {}", e.getMessage());
            }
        }

        try {
            String png = segmentService.segment(input);
            long cost = System.currentTimeMillis() - t0;
            // 只记 openid 前缀与耗时，不落任何图片内容
            log.info("segment 调用 user={} beauty={} cost={}ms",
                    openid == null ? "anonymous" : openid.substring(0, Math.min(8, openid.length())) + "…",
                    beautyApplied, cost);
            out.put("ok", true);
            out.put("image", png);
            out.put("beautyApplied", beautyApplied);
            out.put("costMs", cost);
            return out;
        } catch (IllegalArgumentException e) {
            out.put("ok", false);
            out.put("code", "BAD_IMAGE");
            out.put("msg", e.getMessage());
            return out;
        } catch (IllegalStateException e) {
            out.put("ok", false);
            out.put("code", "BDA_FAIL");
            out.put("msg", e.getMessage());
            return out;
        } catch (Exception e) {
            log.error("segment 异常: {}", e.toString());
            out.put("ok", false);
            out.put("code", "BDA_FAIL");
            out.put("msg", "服务暂时不可用，请稍后重试");
            return out;
        }
    }
}
