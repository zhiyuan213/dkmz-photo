package com.dkmz.photo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 证件照美肤 —— v9 定稿算法的**适配层**（base64 进 / base64 出）。
 *
 * ============================================================
 * 为什么这个类现在这么薄
 * ============================================================
 * 美白 / 磨皮算法全局只保留一份实现：{@link IdPhotoBeautyService}
 * （v1→v9 调试沉淀的定稿版）。本类只负责把 base64 入参翻译成那套算法要的形态：
 *     imageBase64  → BufferedImage
 *     maskBase64   → boolean[]（灰度 >128 视为皮肤）
 * 然后把档位交给 IdPhotoBeautyService，再把结果编码回 base64 PNG。
 *
 * 历史：本类原先是自研 v2 实现（MEDIAN_LIFT=17 的旧标定 + 布尔 mask 硬切、无羽化）。
 * 它与 v9 定稿的差距正是《BDA美颜-修改记录》总结的三类问题：
 *   ① 美白标定（v3）：旧公式的 gain 分母跨度过大，正常亮度像素几乎拿不到提升量；
 *   ② 磨皮硬边（v7）：布尔 mask 内外两侧一个是混合图、一个是原图，边界梯度可达 30，
 *      肉眼即一条横线；
 *   ③ 羽化失效（v9）：必须「模糊完直接用，不裁剪、不钉值」，否则 feather 调多大都无效。
 * 现网若继续跑 v2，这三处一个都不会修 —— 所以统一到 v9。
 *
 * ============================================================
 * 与 /idphoto/process 链路的差别（是数据流差别，不是算法差别）
 * ============================================================
 *   本链路：先美肤（作用在原图）→ 再抠图 → 回透明底 PNG。
 *           mask 只勾 Face —— 这里没有抠图 alpha 可用来排除 Background，
 *           若勾 Background 会把整块背景并进皮肤区，反而把背景一起美颜。
 *   idphoto：先抠图 → mask(Face+Background) ∩ alpha → 在成片像素上美颜。
 *   两条链路的「美白 + 保边磨皮 + 连续羽化」算法完全一致，均出自 IdPhotoBeautyService。
 */
@Service
public class SkinBeautyService {

    private static final Logger log = LoggerFactory.getLogger(SkinBeautyService.class);

    private final IdPhotoBeautyService beauty;

    /** 美肤档位：id / normal / portrait（默认 normal = 均衡档，与线上原固定档强度相当） */
    @Value("${photo.bda.beauty-level:normal}")
    private String beautyLevel;

    public SkinBeautyService(IdPhotoBeautyService beauty) {
        this.beauty = beauty;
    }

    // ------------------------------------------------------------------
    // 旧签名兼容（/idphoto/process 的「办证档 / 形象档」仍从这里进来）
    //
    // ⚠️ 剂量语义与 v2 不同，不能按比例换算：
    //   v2 的 medianLift 是「皮肤亮度中位数的目标抬升量」，配合 SHADOW_KEEP=0.85、
    //   单像素封顶 36 —— 办证档 15 实测中位数抬升 ≈ +12.75；
    //   v9 的 whiten 是强度（提亮量 = 36×whiten×gain，单像素封顶 12）——
    //   办证档 whiten 0.15 实测中位数抬升 ≈ +3。
    //   若按 15/36 = 0.42 换算，会把「办证档」抬到形象档之上（越档），
    //   直接违反办证用途对「失真」的零容忍红线。
    //   因此这里按**最接近的档位**映射，而不是数值换算。
    //
    //   顺带说明文档里那两组数：id 档 0.15/0.20、portrait 档 0.35/0.40
    //   —— 它们本来就是 v9 的档位值（whiten/smooth），下面的映射正好落在同一组。
    // ------------------------------------------------------------------

    /** 办证档剂量标签（仅用于选档，不再作为算法参数；见上方剂量语义说明） */
    public static final double LIFT_CERT = 15.0;
    public static final double SMOOTH_CERT = 0.20;
    /** 形象档剂量标签（同上） */
    public static final double LIFT_PORTRAIT = 35.0;
    public static final double SMOOTH_PORTRAIT = 0.40;

    /**
     * 美肤主入口（/api/photo/segment 走这里）。
     *
     * @param imageBase64 原图 base64（RGB 图；不要传透明底图，透明通道会被铺黑）
     * @param maskBase64  皮肤区域灰度 mask base64；null/空 则跳过美肤
     * @return 美白 + 磨皮后的 PNG base64
     */
    public String beautify(String imageBase64, String maskBase64) {
        return beautify(imageBase64, maskBase64, IdPhotoBeautyService.Level.of(beautyLevel));
    }

    /**
     * 带剂量的美肤（/idphoto/process 的「办证档 / 形象档」走这里）。
     *
     * @param medianLift     v2 语义的提亮剂量标签，仅用于选档（≤20 办证档 / ≤30 均衡档 / 其余形象档）
     * @param smoothStrength v2 语义的磨皮剂量标签；v9 档位体系里磨皮强度与档位绑定
     *                       （ID 0.20 / NORMAL 0.30 / PORTRAIT 0.40），调用方传的值与档位自带的一致，忽略即可
     */
    public String beautify(String imageBase64, String maskBase64,
                           double medianLift, double smoothStrength) {
        return beautify(imageBase64, maskBase64, levelOf(medianLift, smoothStrength));
    }

    /** 旧剂量标签 → v9 档位（见上方「剂量语义」说明） */
    public static IdPhotoBeautyService.Level levelOf(double medianLift, double smoothStrength) {
        if (medianLift <= 20.0) return IdPhotoBeautyService.Level.ID;
        if (medianLift <= 30.0) return IdPhotoBeautyService.Level.NORMAL;
        return IdPhotoBeautyService.Level.PORTRAIT;
    }

    /** 允许调用方显式指定档位 */
    public String beautify(String imageBase64, String maskBase64, IdPhotoBeautyService.Level level) {
        String pure = stripDataUrlPrefix(imageBase64);
        if (maskBase64 == null || maskBase64.isEmpty()) {
            throw new IllegalArgumentException("未获取到皮肤 mask");
        }

        BufferedImage img = decode(pure);
        if (img == null) throw new IllegalArgumentException("图片解码失败");

        int w = img.getWidth();
        int h = img.getHeight();
        // 统一铺到 RGB（去掉可能的 alpha 通道，避免透明区被当黑色参与运算）
        BufferedImage work = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = work.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();

        boolean[] mask = parseMask(maskBase64, w, h);
        if (mask == null) {
            throw new IllegalArgumentException("皮肤 mask 解析失败");
        }

        boolean hasSkin = false;
        for (boolean pixel : mask) hasSkin |= pixel;
        if (!hasSkin) throw new IllegalArgumentException("未识别到面部皮肤");

        BufferedImage out = beauty.beautify(work, mask, level);
        log.info("美肤完成 v9 level={} whiten={} smooth={}", level.key, level.whiten, level.smooth);
        return encodePng(out);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 灰度 mask → boolean[]（>128 视为目标区域），必要时缩放到目标尺寸 */
    private static boolean[] parseMask(String maskBase64, int targetW, int targetH) {
        try {
            BufferedImage m = decode(stripDataUrlPrefix(maskBase64));
            if (m == null) return null;

            if (m.getWidth() != targetW || m.getHeight() != targetH) {
                BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.drawImage(m, 0, 0, targetW, targetH, null);
                g.dispose();
                m = scaled;
            }

            int[] px = new int[targetW * targetH];
            m.getRGB(0, 0, targetW, targetH, px, 0, targetW);
            boolean[] out = new boolean[targetW * targetH];
            for (int i = 0; i < out.length; i++) {
                int c = px[i];
                int lum = (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
                out[i] = lum > 128;   // 灰度 mask：亮 = 目标区域
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage decode(String b64) {
        try {
            return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String encodePng(BufferedImage img) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("图片编码失败", e);
        }
    }

    private static String stripDataUrlPrefix(String s) {
        if (s == null) return null;
        String t = s.trim();
        int i = t.indexOf("base64,");
        return i >= 0 ? t.substring(i + "base64,".length()).trim() : t;
    }
}
