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
 *   3. 自适应锚点 + 提亮封顶——锚点 = 皮肤区当前亮度中位数 + 小量提升，
 *      单像素提亮 ≤ 12/255。证件照美白是「微调气色」，不是「把脸打白」
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

    /** 单像素最大提亮量（0-255 尺度）。证件照必须保守，超过 15 就会明显假 */
    private static final int MAX_DELTA = 12;

    /** 办证档：极保守（提亮强度 0.15 / 磨皮强度 0.20） */
    public static final double WHITEN_ID = 0.15;
    public static final double SMOOTH_ID = 0.20;

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
        whiten(work, mask, medianLuma, WHITEN_ID);
        smooth(work, mask, SMOOTH_ID);

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(work, "png", bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("图片编码失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 美白（YCbCr 空间只动 Y，轻微去黄）
    // ------------------------------------------------------------------
    private static void whiten(BufferedImage img, boolean[][] mask,
                               double medianLuma, double strength) {
        int w = img.getWidth();
        int h = img.getHeight();

        // 锚点 = 当前中位数 + 随强度的小幅提升，上限 240
        double target = Math.min(240.0, medianLuma + 20.0 * strength);

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

                // 越暗提得越多；高光保护：接近 190 后衰减
                double gain = Math.max(0, (target - y0) / Math.max(target - 40.0, 1.0));
                double hiGuard = 1.0 - Math.pow(
                        Math.max(0, Math.min(1, (y0 - 190.0) / 65.0)), 1.5);

                double delta = 20.0 * strength * gain * hiGuard;
                delta = Math.max(0, Math.min(MAX_DELTA, delta));

                double y1 = Math.min(255, y0 + delta);

                // 轻微去黄：cr 向中性收一点（过头会发青，限幅 15%）
                double cr1 = 128.0 + (cr - 128.0) * (1.0 - 0.15 * strength);

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
    private static boolean[][] parseMask(String maskBase64, int targetW, int targetH) {
        try {
            BufferedImage m = ImageIO.read(new ByteArrayInputStream(
                    Base64.getDecoder().decode(stripDataUrlPrefix(maskBase64))));
            if (m == null) return null;

            if (m.getWidth() != targetW || m.getHeight() != targetH) {
                BufferedImage scaled = new BufferedImage(targetW, targetH,
                        BufferedImage.TYPE_INT_RGB);
                scaled.createGraphics().drawImage(m, 0, 0, targetW, targetH, null);
                m = scaled;
            }

            boolean[][] out = new boolean[targetH][targetW];
            int[] px = new int[targetW * targetH];
            m.getRGB(0, 0, targetW, targetH, px, 0, targetW);
            for (int y = 0; y < targetH; y++) {
                for (int x = 0; x < targetW; x++) {
                    int c = px[y * targetW + x];
                    int lum = (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
                    out[y][x] = lum > 128;   // 灰度 mask：亮 = 目标区域
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
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
