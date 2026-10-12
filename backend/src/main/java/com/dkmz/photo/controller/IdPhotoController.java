package com.dkmz.photo.controller;

import com.dkmz.photo.service.BdaSegmentService;
import com.dkmz.photo.service.CustomSegmentService;
import com.dkmz.photo.service.SkinBeautyService;
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
 * 「完整版」端（idphoto 架构）约定的抠图接口。
 *
 *   POST /idphoto/process
 *   body: { image:"<base64>", level:"id"|"portrait", acne:true|false }
 *   返回: { image:"<透明底 PNG base64>", origBg:[r,g,b], beautyApplied, costMs }
 *         { message:"<可直接展示的中文>" }   ← 失败（HTTP 仍 200，端上读 message）
 *
 * 与 /api/photo/segment 的关系：同一个 BDA 抠图能力，两种端上架构的出口。
 *   - segment 走「成品图模式」：后端连换底一起做完，回不透明 JPEG；
 *   - 本接口走「端上换底」：只回透明底 + 原背景色，端上自己切 N 种底色，
 *     抠一次只花一次钱（完整版的核心卖点：「抠一次，本地随便换」）。
 *
 * 三处与 segment 的契约差异（完整版 utils/api.js 依赖）：
 *   1. 成功不返回 ok 字段，只认 image 是否存在；
 *   2. 失败用 message（不是 msg / code），端上直接弹给用户；
 *   3. 必带 origBg —— 端上优先用它去色边，取不到才回退自己采样。
 *
 * origBg 的意义（去色边公式 out = C + (1-alpha) × (新底色 - 原背景色)）：
 *   端上按原图四角取中位数并不可靠 —— 四角里若有两个角是深色（衣服/阴影/家具），
 *   中位数会落在两簇之间的废值上（实测墙色 RGB(233,217,204) 被取成 RGB(147,137,130)），
 *   去色边修正量整条错位，发际线/脸颊/下巴出现一圈亮色光晕，比不去色边还差。
 *   服务端用抠图结果的「透明区」统计（抠图自己裁定的背景，天然不含前景），最可靠。
 *
 * ⚠️ 安全：这是**公网直连**接口（端上用 wx.request，不走云托管云调用通道，
 *   因此拿不到 X-WX-OPENID 之类的平台身份）。默认无鉴权时任何人拿到地址就能刷
 *   BDA 费用，上线前必须在云托管环境变量里配 PHOTO_API_TOKEN（配了就强制校验
 *   请求头 X-Photo-Token），并在 mp 后台把公网域名加进 request 合法域名。
 *
 * 设计原则（不落盘）：只收 base64，处理完立即返回，全程内存；不写文件/库/缓存；
 * 任何日志不打印图片内容（一个 log.info(base64) 就把用户人脸写进日志）。
 */
@RestController
@RequestMapping("/idphoto")
public class IdPhotoController {

    private static final Logger log = LoggerFactory.getLogger(IdPhotoController.class);

    private final BdaSegmentService segmentService;
    private final CustomSegmentService customSegmentService;
    private final SkinBeautyService skinBeautyService;

    /** 美肤总开关：每次美肤多消耗 1 次 BDA 调用（自定义分割拿皮肤 mask），独立控制成本 */
    @Value("${photo.bda.beauty-enabled:false}")
    private boolean beautyEnabled;

    /** 可选共享口令；非空时强制校验请求头 X-Photo-Token（公网直连接口的防刷闸门） */
    @Value("${photo.api-token:}")
    private String apiToken;

    public IdPhotoController(BdaSegmentService segmentService,
                             CustomSegmentService customSegmentService,
                             SkinBeautyService skinBeautyService) {
        this.segmentService = segmentService;
        this.customSegmentService = customSegmentService;
        this.skinBeautyService = skinBeautyService;
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

        // level：id 办证档（保守，默认）| portrait 形象档（提亮/磨皮各强一档）
        boolean portrait = "portrait".equalsIgnoreCase(level == null ? "" : level.trim());
        double lift = portrait ? SkinBeautyService.LIFT_PORTRAIT : SkinBeautyService.LIFT_CERT;
        double smooth = portrait ? SkinBeautyService.SMOOTH_PORTRAIT : SkinBeautyService.SMOOTH_CERT;

        long t0 = System.currentTimeMillis();
        boolean beautyApplied = false;
        String input = image;
        if (beautyEnabled) {
            try {
                // 必须先于抠图：透明底图进 Java ImageIO 会被铺黑，肤色就废了
                String mask = customSegmentService.getFaceSkinMask(image);
                input = skinBeautyService.beautify(image, mask, lift, smooth);
                beautyApplied = true;
            } catch (Exception e) {
                // 美肤失败不阻断抠图主流程，退化为纯抠图
                log.warn("美肤跳过: {}", e.getMessage());
            }
        }
        if (acne) {
            // 祛痘走 BeautifyPic 另一条链路（需单独开通人脸试妆），当前未接入：
            // 明确忽略并在响应里如实告知，不假装做了
            log.info("acne=true 但祛痘链路未接入，已忽略");
        }

        try {
            String png = segmentService.segment(input);
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
}
