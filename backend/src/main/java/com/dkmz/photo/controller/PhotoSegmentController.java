package com.dkmz.photo.controller;

import com.dkmz.photo.service.BdaSegmentService;
import com.dkmz.photo.service.CustomSegmentService;
import com.dkmz.photo.service.PortraitCompositeService;
import com.dkmz.photo.service.SkinBeautyService;
import com.dkmz.photo.support.AlphaExtractor;
import com.dkmz.photo.support.BackgroundColorEstimator;
import com.dkmz.photo.support.OpenidSupport;
import com.dkmz.photo.support.SegmentRateLimiter;
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
 *     alphaOnly: true               // 可选，只替换背景（不美颜）时用：只回 alpha 灰度图
 *   }
 *
 * 返回（三选一，按入参决定）：
 *   ① alphaOnly（只替换背景，不美颜）：
 *      { ok:true, alpha:"<8 位灰度 PNG base64，灰度值=alpha>", width, height, beautyApplied:false, costMs }
 *      服务端只做一件事——抠图；换底由端上用「本地裁剪图 + alpha」完成。
 *      一次 BDA 调用、响应 10~30KB（对比透明底 RGBA 的 200~400KB）。
 *   ② 成品图模式（带 bgHex，替换背景+美颜走这条）：
 *      { ok:true, image:"<不透明 JPEG base64>", finished:true, beautyApplied, costMs }
 *   ③ 兼容模式（既不带 bgHex 也不带 alphaOnly，老端上）：
 *      { ok:true, image:"<透明底 PNG base64>", origBg:[r,g,b], beautyApplied, costMs }
 *   失败：{ ok:false, code:"BDA_DISABLED"|"BAD_REQUEST"|"BAD_IMAGE"|"BDA_FAIL", msg }
 *
 * origBg = 服务端从抠图结果「透明区」统计出的原背景色，供端上换底去色边使用：
 *   out = C + (1 - alpha) × (新底色 - 原背景色)
 * 端上自己用原图四角采样取中位数并不可靠 —— 四角里若有两个角是深色（衣服/阴影/家具），
 * 中位数会落在两簇之间的废值上（实测墙色 RGB(233,217,204) 被取成 RGB(147,137,130)），
 * 去色边修正量随之整条错位，发际线/脸颊/下巴出现一圈亮色光晕，比不去色边还差。
 * 成品图模式下后端已铺好底色，端上不需要去色边，故不返回该字段；
 * 透明区样本不足时该字段也可能缺失，端上按自己的兜底逻辑处理即可。
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
 * 「alphaOnly 模式」的意义（对应产品功能「只替换背景」）：既然不美颜，
 * 端上本地像素就是最终像素，服务端多传一份 RGB 毫无信息量 ⇒ 只回 alpha 灰度图。
 * 该模式下不做美肤、不做换底，响应体量降一个数量级，端上用现有换底链
 * （反推去色边 + 头发白霜修复）合成，观感与质量都不降级。
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
    private final SegmentRateLimiter rateLimiter;

    /** 美肤总开关（每次美肤多消耗 1 次 BDA 调用，独立控制成本） */
    @Value("${photo.bda.beauty-enabled:false}")
    private boolean beautyEnabled;

    public PhotoSegmentController(BdaSegmentService segmentService,
                                  CustomSegmentService customSegmentService,
                                  SkinBeautyService skinBeautyService,
                                  PortraitCompositeService compositeService,
                                  SegmentRateLimiter rateLimiter) {
        this.segmentService = segmentService;
        this.customSegmentService = customSegmentService;
        this.skinBeautyService = skinBeautyService;
        this.compositeService = compositeService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/segment")
    public Map<String, Object> segment(@RequestBody(required = false) Map<String, Object> body,
                                       @RequestHeader(value = "X-WX-FROM-OPENID", required = false) String fromOpenid,
                                       @RequestHeader(value = "X-WX-OPENID", required = false) String openid) {
        Map<String, Object> out = new LinkedHashMap<>();
        // ---- 防刷第一道：必须带微信侧注入的用户身份 ----
        // 云托管只对「小程序 callContainer / 微信内网通道」的请求注入 X-WX-OPENID 等头；
        // 公网域名直连（curl / 脚本）拿不到 ⇒ 直接拒绝，公网爬虫连图片都不用处理就被挡掉。
        // （攻击者仍可伪造该头，但伪造身份会被单用户限流 + 全局限流兜底，损失有上界；
        //   彻底解法是在云托管控制台关闭「公网访问」，小程序走内网通道不受影响。）
        String user = OpenidSupport.resolve(fromOpenid, openid, null);
        if (user == null || user.isBlank()) {
            out.put("ok", false);
            out.put("code", "NO_OPENID");
            out.put("msg", "仅支持小程序内调用");
            return out;
        }
        // ---- 防刷第二道：滑动窗口限流（全局 + 单用户；阈值见 application.yml）----
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
        String beautySkippedReason = wantBeauty && !beautyEnabled ? "美肤服务未启用" : null;
        String input = image;
        if (wantBeauty && beautyEnabled) {
            try {
                String mask = customSegmentService.getFaceSkinMask(image);
                input = skinBeautyService.beautify(image, mask);
                beautyApplied = true;
            } catch (Exception e) {
                // 美肤失败不阻断抠图主流程，退化为纯抠图
                log.warn("美肤跳过: {}", e.getMessage());
                beautySkippedReason = "面部皮肤处理失败，本次仅替换背景";
            }
        }

        try {
            String png = segmentService.segment(input);
            String payload = wantFinished ? compositeService.composite(png, bgHex, outTop, input) : png;
            long cost = System.currentTimeMillis() - t0;
            // 只记 openid 前缀与耗时，不落任何图片内容
            log.info("segment 调用 user={} beauty={} finished={} cost={}ms",
                    userTag(openid), beautyApplied, wantFinished, cost);
            out.put("ok", true);
            out.put("image", payload);
            if (wantFinished) out.put("finished", true);

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
