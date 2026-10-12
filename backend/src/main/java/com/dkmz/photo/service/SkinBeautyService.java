package com.dkmz.photo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 证件照美肤 —— 美白（提亮）+ 轻度磨皮，纯 Java 实现（不依赖 OpenCV），零额外费用。
 *
 * 设计要点（证件照场景，与短视频美颜完全不同）：
 *   1. 只在皮肤 mask 内生效——否则白底/蓝底背景一起被推亮，整张照片发灰
 *   2. YCbCr 空间只提亮 Y 通道——RGB 直接加亮度会让肤色发灰发青、丢血色
 *   3. 自适应锚点 + 提亮封顶——锚点 = 皮肤区亮度中位数 +17，阴影区按 85% 收敛，
 *      单像素提亮 ≤ 36/255；锚点以上（含高光）不动，保留立体感
 *   4. 高光保护——已经很亮的区域衰减提亮幅度，避免脸「曝掉」丢立体感
 *   5. 绝不做瘦脸/大眼——办证照片官方禁止改变面部特征，过度处理会被判「失真」
 *
 * 祛痘：Java 原生实现高质量 inpainting 吃力，建议后续接腾讯云人脸试妆
 * BeautifyPic（fmu 产品，官方含祛痘能力，FaceLifting/EyeEnlarging 必须为 0）。
 * 本版未接，保持最小改动面。
 */
@Service
public class SkinBeautyService {

    private static final Logger log = LoggerFactory.getLogger(SkinBeautyService.class);

    /**
     * 单像素最大提亮量（0-255 尺度）。
     * v1：锚点+3 再乘 gain ⇒ 中位数处 delta≈0.1，肉眼不可见；
     * v2：锚点直接抬 9、阴影收敛 75% ⇒ 中位数处 ≈ +5.5，用户仍反馈「看不出区别」
     *     （除剂量偏小外，当时端上根本没采用美白后的像素，见 PortraitCompositeService 注释）；
     * v3（当前）：锚点抬 17、阴影收敛 85%、封顶 36 ⇒ 中位数处 ≈ +12，在真实人像上
     *     重放实测：肤色明显白净、气色变好，五官阴影与立体感保留，无「假白/过曝」。
     */
    private static final int MAX_DELTA = 36;

    /** 皮肤亮度中位数的目标抬升量（0-255 尺度） */
    private static final double MEDIAN_LIFT = 17.0;

    /** 阴影区向锚点收敛的比例（1.0 = 完全拉平，丢立体感；0.85 保留光影层次） */
    private static final double SHADOW_KEEP = 0.85;

    /** 去黄强度（cr 偏移向中性收 18%，发青临界之内） */
    private static final double DEYELLOW = 0.18;

    /** 磨皮强度（0-1，越高越平；保边阈值见 smooth()） */
    public static final double SMOOTH_ID = 0.35;

    /**
     * 美肤主入口。
     *
     * @param imageBase64 原图 base64（RGB 图；不要传透明底图，透明通道会被铺黑）
     * @param maskBase64  皮肤区域 mask 灰度图 base64（来自 CustomSegmentService）；null 则不做美肤
     * @return 处理后的图片 base64（PNG）
     */
    public String beautify(String imageBase64, String maskBase64) {
        if (maskBase64 == null || maskBase64.isEmpty()) {
            // 没有 mask 宁可不做——全图提亮会把深色背景一起推亮
            log.info("无皮肤 mask，跳过美肤");
            return stripDataUrlPrefix(imageBase64);
        }

        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(
                    Base64.getDecoder().decode(stripDataUrlPrefix(imageBase64))));
        } catch (Exception e) {
            throw new IllegalArgumentException("图片解码失败");
        }
        if (img == null) throw new IllegalArgumentException("图片解码失败");

        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage work = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        work.createGraphics().drawImage(img, 0, 0, null);

        boolean[][] mask = parseMask(maskBase64, w, h);
        if (mask == null) {
            log.info("皮肤 mask 解析失败，跳过美肤");
            return stripDataUrlPrefix(imageBase64);
        }

        // 1) 皮肤区亮度中位数 → 提亮锚点；2) YCbCr 提亮；3) 保边轻度磨皮
        double medianLuma = calcMaskMedianLuma(work, mask);
        // 保险丝：皮肤亮度中位数不可能低于 60（深肤）或高于 210（近曝），
        // 越界说明 mask 判错了区域（比如盖到了头发/背景），提亮只会帮倒忙
        if (medianLuma < 60 || medianLuma > 210) {
            log.warn("皮肤亮度中位数={} 越界，疑似 mask 判错区域，放弃美肤", (int) medianLuma);
            return stripDataUrlPrefix(imageBase64);
        }
        whiten(work, mask, medianLuma);
        smooth(work, mask, SMOOTH_ID);
        log.info("美肤完成 medianY={} → +{}", (int) medianLuma, MEDIAN_LIFT);

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(work, "png", bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("图片编码失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 美白（YCbCr 空间只动 Y，去黄）
    // ------------------------------------------------------------------
    private static void whiten(BufferedImage img, boolean[][] mask,
                               double medianLuma) {
        int w = img.getWidth();
        int h = img.getHeight();

        // 目标锚点：当前中位数 + 可感知的抬升，上限 238（再高纸打印会过曝）
        double target = Math.min(238.0, medianLuma + MEDIAN_LIFT);

        int[] rgb = new int[w * h];
        img.getRGB(0, 0, w, h, rgb, 0, w);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (!mask[y][x]) continue;

                int c = rgb[idx];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;

                double y0 = 0.299 * r + 0.587 * g + 0.114 * b;
                double cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b;
                double cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b;

                // 亮度低于锚点的部分按比例向锚点收敛（保留 25% 阴影层次）；
                // 高于锚点（含高光）不动 → 天然的立体感与高光保护
                double delta = (target - y0) * SHADOW_KEEP;
                if (delta < 0) delta = 0;

                // 高光保护：接近 190 后再衰减一次，避免脸「曝掉」
                double hiGuard = 1.0 - Math.pow(
                        Math.max(0, Math.min(1, (y0 - 190.0) / 65.0)), 1.5);
                delta *= hiGuard;

                delta = Math.min(MAX_DELTA, delta);
                if (delta < 0.5) continue;

                double y1 = Math.min(255, y0 + delta);

                // 去黄：cr 偏移向中性收 12%
                double cr1 = 128.0 + (cr - 128.0) * (1.0 - DEYELLOW);

                int nr = clamp((int) (y1 + 1.402 * (cr1 - 128.0)));
                int ng = clamp((int) (y1 - 0.344136 * (cb - 128.0)
                        - 0.714136 * (cr1 - 128.0)));
                int nb = clamp((int) (y1 + 1.772 * (cb - 128.0)));

                rgb[idx] = (nr << 16) | (ng << 8) | nb;
            }
        }
        img.setRGB(0, 0, w, h, rgb, 0, w);
    }

    // ------------------------------------------------------------------
    // 轻度磨皮（保边：邻域颜色接近时才融合，避免糊掉眉毛/发丝边界）
    // ------------------------------------------------------------------
    private static void smooth(BufferedImage img, boolean[][] mask, double strength) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] src = new int[w * h];
        img.getRGB(0, 0, w, h, src, 0, w);
        int[] dst = src.clone();

        int radius = 2;
        double keepDetail = 1.0 - strength;

        for (int y = radius; y < h - radius; y++) {
            for (int x = radius; x < w - radius; x++) {
                int idx = y * w + x;
                if (!mask[y][x]) continue;

                int c = src[idx];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;

                int sr = 0;
                int sg = 0;
                int sb = 0;
                int n = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int j = (y + dy) * w + (x + dx);
                        if (!mask[y + dy][x + dx]) continue;
                        int c2 = src[j];
                        int r2 = (c2 >> 16) & 0xFF;
                        int g2 = (c2 >> 8) & 0xFF;
                        int b2 = c2 & 0xFF;

                        // 保边：颜色差异太大不参与平滑
                        int diff = Math.abs(r2 - r) + Math.abs(g2 - g) + Math.abs(b2 - b);
                        if (diff > 90) continue;

                        sr += r2;
                        sg += g2;
                        sb += b2;
                        n++;
                    }
                }
                if (n == 0) continue;

                int ar = sr / n;
                int ag = sg / n;
                int ab = sb / n;
                // 高频保留：只吸收 (1-keepDetail) 的平滑量，避免塑料脸
                int nr = clamp((int) (ar + (r - ar) * keepDetail));
                int ng = clamp((int) (ag + (g - ag) * keepDetail));
                int nb = clamp((int) (ab + (b - ab) * keepDetail));

                dst[idx] = (nr << 16) | (ng << 8) | nb;
            }
        }
        img.setRGB(0, 0, w, h, dst, 0, w);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------
    /** mask 覆盖率合理区间：脸部皮肤在证件照裁剪里一般占 8%~30%；越界视为解析失败 */
    private static final double MASK_MIN_COVERAGE = 0.02;
    private static final double MASK_MAX_COVERAGE = 0.45;

    /**
     * 解析皮肤 mask，对 BDA MaskImage 的三种可能编码都健壮：
     *   ① 黑白不透明图（脸白底黑）——按亮度判；
     *   ② 透明底图（脸不透明白、背景透明但 RGB 可能是白！）——只按亮度会把
     *      整张图判成皮肤 ⇒ 全图提亮封顶 ⇒ 「泥巴脸+银灰头发」事故（10-12），
     *      必须结合 alpha；
     *   ③ 白底黑脸的反相图——按反相亮度判。
     * 依次尝试三种判据，取第一个覆盖率落在合理区间的；全部越界则放弃美肤。
     */
    private static boolean[][] parseMask(String maskBase64, int targetW, int targetH) {
        BufferedImage m;
        try {
            m = ImageIO.read(new ByteArrayInputStream(
                    Base64.getDecoder().decode(stripDataUrlPrefix(maskBase64))));
        } catch (Exception e) {
            return null;
        }
        if (m == null) return null;

        if (m.getWidth() != targetW || m.getHeight() != targetH) {
            log.info("mask 尺寸 {}x{} 与图片 {}x{} 不一致，缩放对齐",
                    m.getWidth(), m.getHeight(), targetW, targetH);
            BufferedImage scaled = new BufferedImage(targetW, targetH,
                    BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.drawImage(m, 0, 0, targetW, targetH, null);
            g.dispose();
            m = scaled;
        }

        int n = targetW * targetH;
        int[] px = new int[n];
        m.getRGB(0, 0, targetW, targetH, px, 0, targetW);

        boolean[][] lumMask = new boolean[targetH][targetW];
        boolean[][] alphaMask = new boolean[targetH][targetW];
        boolean[][] invMask = new boolean[targetH][targetW];
        int lumCnt = 0;
        int alphaCnt = 0;
        int invCnt = 0;
        for (int i = 0, y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++, i++) {
                int c = px[i];
                int alpha = (c >>> 24) & 0xFF;
                int lum = (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
                boolean lm = lum > 128;
                boolean am = alpha > 128;
                lumMask[y][x] = lm;
                alphaMask[y][x] = am;
                invMask[y][x] = !lm;
                if (lm) lumCnt++;
                if (am) alphaCnt++;
                if (!lm) invCnt++;
            }
        }

        String[] names = {"亮度", "alpha", "反相亮度"};
        boolean[][][] masks = {lumMask, alphaMask, invMask};
        int[] counts = {lumCnt, alphaCnt, invCnt};
        for (int k = 0; k < 3; k++) {
            double cov = counts[k] / (double) n;
            String covStr = (Math.round(cov * 1000) / 10.0) + "%";
            if (cov >= MASK_MIN_COVERAGE && cov <= MASK_MAX_COVERAGE) {
                log.info("皮肤 mask 判据={} 覆盖率={} → 采用", names[k], covStr);
                return masks[k];
            }
            log.info("皮肤 mask 判据={} 覆盖率={} 越界，弃用", names[k], covStr);
        }
        log.warn("皮肤 mask 三种判据覆盖率全部越界，放弃美肤");
        return null;
    }

    private static double calcMaskMedianLuma(BufferedImage img, boolean[][] mask) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = new int[w * h];
        img.getRGB(0, 0, w, h, px, 0, w);

        int[] lumas = new int[w * h];
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!mask[y][x]) continue;
                int c = px[y * w + x];
                lumas[n++] = (int) (0.299 * ((c >> 16) & 0xFF)
                        + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF));
            }
        }
        if (n == 0) return 128.0;
        int[] arr = new int[n];
        System.arraycopy(lumas, 0, arr, 0, n);
        java.util.Arrays.sort(arr);
        return arr[n / 2];
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static String stripDataUrlPrefix(String s) {
        if (s == null) return null;
        String t = s.trim();
        int i = t.indexOf("base64,");
        return i >= 0 ? t.substring(i + "base64,".length()).trim() : t;
    }
}
