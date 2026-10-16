package com.dkmz.photo.service;

import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;

/**
 * 证件照美肤：按皮肤 mask 连续羽化，轻度提亮和双边保边磨皮。
 * 两条业务链路共用；档位控制幅度，不改变脸型及五官。
 * 提亮以皮肤亮度中位数为锚点，保留色度并保护高光。
 * 磨皮以空间距离、颜色距离和皮肤权重做连续加权，舍入避免累积变暗。
 */
@Service
public class IdPhotoBeautyService {

    /** 单像素最大提亮量（0-255）。证件照必须保守，超过 15 就会明显假 */
    private static final int MAX_DELTA = 12;

    /** 美白羽化半径（px）。5~8 合适；超过 12 会糊到嘴唇边缘导致"掉色" */
    private static final int FEATHER_WHITEN = 6;

    /** 磨皮羽化半径（px）。磨皮比美白更怕硬边，所以给得更宽 */
    private static final int FEATHER_SMOOTH = 12;

    /** 磨皮前先腐蚀（px）。这是"收缩"语义，不是"清零模糊结果" */
    private static final int SMOOTH_ERODE = 4;

    /** 保边阈值：邻域像素与该像素的曼哈顿色差超过此值则不参与平滑 */
    private static final int EDGE_THRESHOLD = 90;

    /** 平滑邻域半径 */
    private static final int SMOOTH_RADIUS = 2;

    /**
     * 美颜档位
     *
     * ID       办证档：护照/签证/考试报名，最保守，肉眼几乎无感（合规优先）
     * NORMAL   均衡档：简历/工牌
     * PORTRAIT 形象档：社交头像，可看出变化（不建议用于办证）
     */
    public enum Level {
        NATURAL("natural", 0, 0),
        ID("id", 0.15, 0.20),
        NORMAL("normal", 0.25, 0.30),
        PORTRAIT("portrait", 0.35, 0.40);

        public final String key;
        public final double whiten;
        public final double smooth;

        Level(String key, double whiten, double smooth) {
            this.key = key;
            this.whiten = whiten;
            this.smooth = smooth;
        }

        public static Level of(String key) {
            if (key == null) return ID;
            for (Level l : values()) {
                if (l.key.equalsIgnoreCase(key)) return l;
            }
            return ID;
        }
    }

    /**
     * 美颜主入口
     *
     * @param src   原图（RGB）
     * @param mask  皮肤 mask，true 表示皮肤像素
     * @param level 档位
     * @return 美白 + 磨皮后的图（与原图同尺寸）
     */
    public BufferedImage beautify(BufferedImage src, boolean[] mask, Level level) {
        int w = src.getWidth();
        int h = src.getHeight();

        if (mask == null || mask.length != w * h || level == null) {
            throw new IllegalArgumentException("皮肤 mask 尺寸或美肤档位无效");
        }
        if (level == Level.NATURAL) return NaturalRetouch.blemishes(src, mask);

        // 1. 羽化权重：美白用原 mask，磨皮用腐蚀后的 mask
        float[] whitenWeight = buildFeatherWeight(mask, w, h, FEATHER_WHITEN);
        boolean[] core = erode(mask, w, h, SMOOTH_ERODE);
        float[] smoothWeight = buildFeatherWeight(core, w, h, FEATHER_SMOOTH);

        // 2. 皮肤区亮度中位数 —— 自适应锚点，不能用固定值
        double medianLuma = medianLuma(src, mask);

        // 3. 先美白，再磨皮
        BufferedImage whitened = whiten(src, whitenWeight, medianLuma, level.whiten);
        return smooth(whitened, smoothWeight, level.smooth);
    }

    // ============================================================
    // 羽化权重
    // ============================================================

    /**
     * 构建 0~1 连续羽化权重
     *
     * ⚠️ 这里是修复 3 的关键 —— 模糊完【直接返回】，
     *    不做 mask 裁剪（BUG-A），不做核心区钉 1.0（BUG-B）。
     */
    private static float[] buildFeatherWeight(boolean[] mask, int w, int h, int radius) {
        float[] src = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            src[i] = mask[i] ? 1f : 0f;
        }
        float sigma = Math.max(0.5f, radius * 0.6f);
        float[] tmp = blur1D(src, w, h, sigma, true);
        return blur1D(tmp, w, h, sigma, false);
    }

    /**
     * 一维高斯模糊（可分离卷积，O(n·r)）
     *
     * 边缘用 clamp（复制边缘像素）：
     *   - mask 外全是 0，clamp 到 0 → 向外扩散正常，形成过渡带
     *   - mask 内全是 1，clamp 保持 1 → 核心区不被削弱
     */
    private static float[] blur1D(float[] src, int w, int h, float sigma, boolean horizontal) {
        int r = Math.max(1, (int) Math.ceil(3 * sigma));
        float[] kernel = new float[2 * r + 1];
        float sum = 0f;
        for (int i = -r; i <= r; i++) {
            float v = (float) Math.exp(-(double) (i * i) / (2.0 * sigma * sigma));
            kernel[i + r] = v;
            sum += v;
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= sum;
        }

        int n = horizontal ? w : h;   // 卷积方向上的长度
        int m = horizontal ? h : w;   // 另一方向的条数
        float[] out = new float[w * h];

        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) {
                float acc = 0f;
                for (int k = -r; k <= r; k++) {
                    int s = i + k;
                    if (s < 0) s = 0;
                    else if (s > n - 1) s = n - 1;
                    int idx = horizontal ? (j * w + s) : (s * w + j);
                    acc += src[idx] * kernel[k + r];
                }
                int oi = horizontal ? (j * w + i) : (i * w + j);
                out[oi] = acc;
            }
        }
        return out;
    }

    /** 腐蚀（收缩语义）：邻域内只要有一个非 mask 像素就置 false */
    private static boolean[] erode(boolean[] mask, int w, int h, int radius) {
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean keep = true;
                for (int dy = -radius; dy <= radius && keep; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h || !mask[ny * w + nx]) {
                            keep = false;
                            break;
                        }
                    }
                }
                out[y * w + x] = keep;
            }
        }
        return out;
    }

    // ============================================================
    // 美白
    // ============================================================

    /**
     * YCbCr 空间提亮（保留原色度）
     *
     * 为什么用 YCbCr：RGB 直接加亮度会让肤色往灰色跑，且白底一起被推亮。
     * 只动 Y（亮度），Cb/Cr 保持，避免固定肤色目标导致偏色。
     */
    private BufferedImage whiten(BufferedImage src, float[] weight,
                                 double medianLuma, double strength) {
        int w = src.getWidth();
        int h = src.getHeight();

        double lift = 36.0 * strength;
        double target = Math.min(240.0, medianLuma + lift);
        double span = Math.max(2.0 * lift, 8.0);

        int[] srcRgb = src.getRGB(0, 0, w, h, null, 0, w);
        int[] outRgb = new int[w * h];

        for (int i = 0; i < w * h; i++) {
            int p = srcRgb[i];
            float fa = weight[i];
            if (fa < 0.002f) {
                outRgb[i] = p;
                continue;
            }

            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;

            // RGB → YCbCr
            double y0 = 0.299 * r + 0.587 * g + 0.114 * b;
            double cb = -0.168736 * r - 0.331264 * g + 0.5 * b + 128.0;
            double cr = 0.5 * r - 0.418688 * g - 0.081312 * b + 128.0;

            // 增益：暗处提得多，亮处提得少（保留立体感）
            double gain = Math.min(1.0, Math.max(0.0, (target - y0) / span));

            // 高光保护：亮区衰减，避免脸曝掉
            double hiGuard = 1.0;
            if (y0 > 200.0) {
                hiGuard = Math.max(0.0, 1.0 - (y0 - 200.0) / 55.0);
            }

            // 提亮量 × 羽化权重 → 边界平滑过渡到 0，不产生硬边
            double delta = Math.min(MAX_DELTA, lift * gain * hiGuard * fa);
            double y1 = Math.min(255.0, y0 + delta);

            // 保留原始色度，固定 Cr 目标会使不同肤色偏红或偏灰。
            double cr1 = cr;

            // YCbCr → RGB
            int nr = clamp((int) Math.round(y1 + 1.402 * (cr1 - 128.0)));
            int ng = clamp((int) Math.round(y1 - 0.344136 * (cb - 128.0) - 0.714136 * (cr1 - 128.0)));
            int nb = clamp((int) Math.round(y1 + 1.772 * (cb - 128.0)));

            outRgb[i] = (0xFF << 24) | (nr << 16) | (ng << 8) | nb;
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, outRgb, 0, w);
        return out;
    }

    // ============================================================
    // 磨皮
    // ============================================================

    /**
     * 保边平滑
     *
     * 两个要点：
     *   1. 邻域内颜色差 > EDGE_THRESHOLD 的像素不参与平均 → 保边
     *   2. 混合系数用 weight[i] * strength（0~strength 连续衰减）
     *      而不是布尔 core 硬切 —— 这是消除"边界横线"的关键。
     *      布尔硬切时：内侧跟模糊图混合、外侧完全不混合，
     *      自拍照片纹理幅度可达 30/通道，硬切梯度 = 30，肉眼就是一条线。
     */
    private BufferedImage smooth(BufferedImage src, float[] weight, double strength) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] srcRgb = src.getRGB(0, 0, w, h, null, 0, w);
        int[] outRgb = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int p = srcRgb[i];

                float sa = (float) (weight[i] * strength);
                if (sa < 0.002f) {
                    outRgb[i] = p;
                    continue;
                }

                int pr = (p >> 16) & 0xFF;
                int pg = (p >> 8) & 0xFF;
                int pb = p & 0xFF;

                double sr = 0, sg = 0, sb = 0, totalWeight = 0;
                for (int dy = -SMOOTH_RADIUS; dy <= SMOOTH_RADIUS; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -SMOOTH_RADIUS; dx <= SMOOTH_RADIUS; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= w) continue;
                        int ni = ny * w + nx;
                        int q = srcRgb[ni];
                        int qr = (q >> 16) & 0xFF;
                        int qg = (q >> 8) & 0xFF;
                        int qb = q & 0xFF;
                        int diff = Math.abs(qr - pr) + Math.abs(qg - pg) + Math.abs(qb - pb);
                        if (diff > EDGE_THRESHOLD) continue;   // 保边
                        // 空间、色差及皮肤置信度共同控制贡献，避免眉毛/嘴唇混入平均。
                        double dr = qr - pr, dg = qg - pg, db = qb - pb;
                        double contribution = weight[ni] * Math.exp(
                                -(dx * dx + dy * dy) / 8.0
                                -(dr * dr + dg * dg + db * db) / (2.0 * 18.0 * 18.0));
                        sr += qr * contribution;
                        sg += qg * contribution;
                        sb += qb * contribution;
                        totalWeight += contribution;
                    }
                }
                if (totalWeight < 1e-6) {
                    outRgb[i] = p;
                    continue;
                }

                double ar = sr / totalWeight;
                double ag = sg / totalWeight;
                double ab = sb / totalWeight;

                int nr = clamp((int) Math.round(ar * sa + pr * (1.0 - sa)));
                int ng = clamp((int) Math.round(ag * sa + pg * (1.0 - sa)));
                int nb = clamp((int) Math.round(ab * sa + pb * (1.0 - sa)));
                outRgb[i] = (0xFF << 24) | (nr << 16) | (ng << 8) | nb;
            }
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, outRgb, 0, w);
        return out;
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 皮肤区亮度中位数 —— 自适应锚点 */
    private static double medianLuma(BufferedImage src, boolean[] mask) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] rgbs = src.getRGB(0, 0, w, h, null, 0, w);

        int count = 0;
        for (int i = 0; i < w * h; i++) {
            if (mask[i]) count++;
        }
        if (count == 0) return 128.0;

        int[] lumas = new int[count];
        int idx = 0;
        for (int i = 0; i < w * h; i++) {
            if (!mask[i]) continue;
            int p = rgbs[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            lumas[idx++] = (int) (0.299 * r + 0.587 * g + 0.114 * b);
        }
        java.util.Arrays.sort(lumas);
        return lumas[lumas.length / 2];
    }

    /** 逐像素膨胀的通用实现，供外部复用 */
    public static boolean[] dilate(boolean[] mask, int w, int h, int radius) {
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean hit = false;
                for (int dy = -radius; dy <= radius && !hit; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        if (mask[ny * w + nx]) { hit = true; break; }
                    }
                }
                out[y * w + x] = hit;
            }
        }
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
