package com.dkmz.photo.controller;

import com.dkmz.photo.service.BdaSegmentService;
import com.dkmz.photo.service.CustomSegmentService;
import com.dkmz.photo.service.PortraitCompositeService;
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
 * 云端精修（腾讯云 BDA 人像分割 + 可选美肤 + 底色合成）接口。
 *
 *   POST /api/photo/segment
 *   body: {
 *     image:  "<base64/dataURL>",   // 必填，与输出同尺寸的裁剪图（端上 480×640 量级）
 *     beauty: true|false,           // 可选，是否做保守美肤（多消耗 1 次 BDA 调用）
 *     bgHex:  "#FFFFFF",            // 可选，目标底色；给了就走「成品图」模式
 *     outTop: 12                    // 可选，裁剪框越出原图的顶部行数（补白底，强制透明）
 *   }
 *
 * 返回：
 *   成品图模式（带 bgHex）：{ ok:true, image:"<不透明 JPEG base64>", finished:true, beautyApplied, costMs }
 *   兼容模式（不带 bgHex）：{ ok:true, image:"<透明底 PNG base64>", beautyApplied, costMs }
 *   失败：{ ok:false, code:"BDA_DISABLED"|"BAD_REQUEST"|"BAD_IMAGE"|"BDA_FAIL", msg }
 *
 * 处理顺序（beauty=true 且美肤开关开启时）：
 *   1. 自定义分割拿「面部皮肤」mask（失败则跳过美肤，不阻断）
 *   2. 在 RGB 原图上做提亮+磨皮（必须先于抠图——透明底图进 Java ImageIO 会被铺黑）
 *   3. SegmentPortraitPic 抠出透明底 PNG
 *   4. 边缘去污染 + 白墙残影清理 + 铺目标底色 → 成品 JPEG（见 PortraitCompositeService）
 * 每次美肤+抠图消耗 2 次 BDA 调用（与免费额度共享）。
 *
 * 「成品图模式」的意义：端上不再需要拿 alpha 去和本地裁剪图混合，美白的像素
 * 才真正进得了成片（历史上端上只读 alpha、像素用本地图，导致美白等于没做）。
 * 不带 bgHex 时依旧返回透明底 PNG，老版本端上行为完全不变。
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
    private final PortraitCompositeService compositeService;

    /** 美肤总开关（每次美肤多消耗 1 次 BDA 调用，独立控制成本） */
    @Value("${photo.bda.beauty-enabled:false}")
    private boolean beautyEnabled;

    public PhotoSegmentController(BdaSegmentService segmentService,
                                  CustomSegmentService customSegmentService,
                                  SkinBeautyService skinBeautyService,
                                  PortraitCompositeService compositeService) {
        this.segmentService = segmentService;
        this.customSegmentService = customSegmentService;
        this.skinBeautyService = skinBeautyService;
        this.compositeService = compositeService;
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
        // bgHex 存在 ⇒ 成品图模式（后端连换底一起做，端上直接用）
        String bgHex = body == null ? null : (String) body.get("bgHex");
        boolean wantFinished = bgHex != null && !bgHex.trim().isEmpty();
        int outTop = toInt(body == null ? null : body.get("outTop"), 0);
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
            String payload = wantFinished ? compositeService.composite(png, bgHex, outTop) : png;
            long cost = System.currentTimeMillis() - t0;
            // 只记 openid 前缀与耗时，不落任何图片内容
            log.info("segment 调用 user={} beauty={} finished={} cost={}ms",
                    openid == null ? "anonymous" : openid.substring(0, Math.min(8, openid.length())) + "…",
                    beautyApplied, wantFinished, cost);
            out.put("ok", true);
            out.put("image", payload);
            if (wantFinished) out.put("finished", true);
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
