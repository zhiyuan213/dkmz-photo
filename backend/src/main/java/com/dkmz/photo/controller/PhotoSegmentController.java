package com.dkmz.photo.controller;

import com.dkmz.photo.service.BdaSegmentService;
import com.dkmz.photo.service.CustomSegmentService;
import com.dkmz.photo.service.IdPhotoBeautyService;
import com.dkmz.photo.service.PortraitCompositeService;
import com.dkmz.photo.service.SkinBeautyService;
import com.dkmz.photo.support.AlphaExtractor;
import com.dkmz.photo.support.BackgroundColorEstimator;
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
 * 云端精修（腾讯云 BDA 人像分割 + 可选美肤 + 可选底色合成）接口。
 *
 *   POST /api/photo/segment
 *   body: {
 *     image:  "<base64/dataURL>",   // 必填，与输出同尺寸的裁剪图（端上 480×640 量级）
 *     beauty: true|false,           // 可选，是否做保守美肤（多消耗 1 次 BDA 调用）
 *     level:  "id|normal|portrait", // 可选，美肤档位；不传用配置默认（见 beauty-level）
 *     bgHex:  "#FFFFFF",            // 可选，目标底色；给了就走「成品图」模式（历史兼容）
 *     outTop: 12                    // 可选，裁剪框越出原图的顶部行数（仅成品图模式用）
 *     alphaOnly: true               // 可选，只替换背景（不美颜）时用：只回 alpha 灰度图
 *   }
 *
 * 返回（按入参决定）：
 *   ① alphaOnly（产品功能「只替换背景」）：
 *      { ok:true, alpha:"<8 位灰度 PNG base64，灰度值=alpha>", width, height, beautyApplied:false, costMs }
 *      服务端只做一件事——抠图；换底由端上用「本地裁剪图 + alpha」完成。
 *      一次 BDA 调用、响应 10~30KB（对比透明底 RGBA 的 200~400KB）。
 *   ② 美肤模式（beauty=true，不带 bgHex）—— 产品功能「替换背景 + 美颜」：
 *      { ok:true, image:"<透明底 PNG base64，RGB 已美白 + alpha>", origBg:[r,g,b], beautyApplied, costMs }
 *      Java 只负责「把人变好看」（抠图 / 皮肤 mask / 美白磨皮），**换底一律端上做**
 *      （见《JS与Java职责对照说明》：换底是纯像素运算，端上零成本，且用户切底色不必再调云端）。
 *      端上必须用这份 PNG 的 RGB 当像素源（而不是只取 alpha 去混本地未美白图），
 *      否则就是《BDA美颜-修改记录》P1「美白结果被丢弃」那个致命数据流问题。
 *   ③ 成品图模式（带 bgHex，历史兼容，新端不再使用）：
 *      { ok:true, image:"<不透明 JPEG base64>", finished:true, beautyApplied, costMs }
 *   失败：{ ok:false, code:"BDA_DISABLED"|"BAD_REQUEST"|"BAD_IMAGE"|"BDA_FAIL", msg }
 *
 * origBg = 服务端从抠图结果「透明区」统计出的原背景色，供端上换底去色边使用：
 *   out = C + (1 - alpha) × (新底色 - 原背景色)
 * 端上自己用原图四角采样取中位数并不可靠 —— 四角里若有两个角是深色（衣服/阴影/家具），
 * 中位数会落在两簇之间的废值上（实测墙色 RGB(233,217,204) 被取成 RGB(147,137,130)），
 * 去色边修正量随之整条错位，发际线/脸颊/下巴出现一圈亮色光晕，比不去色边还差。
 * 另外，端上若把透明底 PNG 画到画布再采样，alpha≈0 的像素 RGB 会被预乘抹成 0，
 * 采样值直接失真 ⇒ **服务端 origBg 是端上换底的必要输入**，不是可选优化。
 * 透明区样本不足时该字段缺失，端上按自己的兜底逻辑处理即可。
 *
 * 处理顺序（beauty=true 且美肤开关开启时）：
 *   1. 自定义分割拿「面部皮肤」mask（失败则跳过美肤，不阻断）
 *   2. 在 RGB 原图上做提亮+磨皮（必须先于抠图——透明底图进 Java ImageIO 会被铺黑）
 *   3. SegmentPortraitPic 抠出透明底 PNG（RGB 已美白）→ 直接返回，不换底
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
        // level ⇒ 美肤档位（id 办证档 / normal 均衡档 / portrait 形象档）；不传用配置默认
        String level = body == null ? null : (String) body.get("level");
        // bgHex 存在 ⇒ 成品图模式（历史兼容；新端不再传，换底由端上做）
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

        long t0 = System.currentTimeMillis();

        // ---- 出口①：只替换背景（不美颜）→ 只回 alpha 灰度图 ----
        // 优先级最高：端上明确只要 alpha 时，不做美肤也不再传 RGB（流量降一个数量级）
        if (alphaOnly) {
            try {
                String png = segmentService.segment(image);
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
                return out;
            } catch (Exception e) {
                return fail(out, e);
            }
        }

        boolean beautyApplied = false;
        String input = image;
        if (wantBeauty && beautyEnabled) {
            try {
                String mask = customSegmentService.getFaceSkinMask(image);
                // 显式档位优先（端上按用途传：考试报名一类走办证档 id），否则用配置默认
                input = (level == null || level.trim().isEmpty())
                        ? skinBeautyService.beautify(image, mask)
                        : skinBeautyService.beautify(image, mask,
                                IdPhotoBeautyService.Level.of(level.trim()));
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
                    userTag(openid), beautyApplied, wantFinished, cost);
            out.put("ok", true);
            out.put("image", payload);
            if (wantFinished) out.put("finished", true);

            // 原背景色：端上换底去色边必须用它（成品图模式除外 —— 后端已铺好底，
            // 端上不再去色边，算了也是白算）。统计对象必须是透明底 PNG，不是成品图。
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
