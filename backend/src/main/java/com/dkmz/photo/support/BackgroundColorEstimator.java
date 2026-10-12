package com.dkmz.photo.support;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

/**
 * 原背景色估计 —— 从 BDA 抠图返回的透明底 PNG 的「全透明区域」统计原背景色。
 *
 * <p>为什么要有这个类：端上换底合成用的是去色边公式
 * <pre>  out = C + (1 - alpha) × (新底色 - 原背景色)  </pre>
 * 抠图边缘像素是「前景 × alpha + 原背景 × (1-alpha)」的混合物。原背景色取错，
 * 修正量就整条错位，人物轮廓上会出现一圈亮色光晕（发际线、脸颊、下巴最明显），
 * 换算下来错误可达 (1-alpha)×Δ，Δ 常常 80 个亮度级——肉眼一眼可见。
 *
 * <p>为什么不用「原图四角采样取中位数」：四角一旦有两个角是深色（衣服、阴影、
 * 家具），合并取中位数会落在两簇之间，取到谁都不是的中间值。实测一张墙色为
 * RGB(233,217,204) 的照片，四角中位数取到 RGB(147,137,130)，去色边后轮廓出现
 * 明显亮色光晕，反而比不去色边更差。中位数只能滤掉「个别」离群角，滤不掉
 * 「半数角是深色」。
 *
 * <p>透明区域是抠图算法自己裁定的背景，天然不含前景物体，用它统计最可靠：
 * <ul>
 *   <li>只统计 alpha ≤ {@link #ALPHA_MAX} 的像素，避开抗锯齿混合带；</li>
 *   <li>逐通道取<b>中位数</b>（直方图法，O(n) 且无浮点累加），
 *       比均值更抗噪点与阴影渐变；</li>
 *   <li>样本覆盖率不足（透明像素太少，说明抠图几乎没找到背景）时返回 {@code null}，
 *       由调用方/端上回退到自己的兜底逻辑，绝不返回一个瞎猜的值。</li>
 * </ul>
 *
 * <p>安全：全程内存直通，不落盘、不写库，日志只打印颜色与覆盖率，绝不打印图片内容。
 */
public final class BackgroundColorEstimator {

    /** 视为「完全透明」的 alpha 上限（0~255）。8 约为 3%，可避开抗锯齿过渡带。 */
    private static final int ALPHA_MAX = 8;

    /** 透明像素占比下限：低于此值认为背景样本不足 */
    private static final double MIN_COVERAGE = 0.02;

    /** 透明像素绝对数量下限，防小图误判 */
    private static final int MIN_COUNT = 500;

    private BackgroundColorEstimator() {
    }

    /** 估计结果：颜色 + 样本量，样本量供日志/诊断用 */
    public static final class Result {
        public final int r;
        public final int g;
        public final int b;
        public final int sampleCount;
        public final double coverage;

        Result(int r, int g, int b, int sampleCount, double coverage) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.sampleCount = sampleCount;
            this.coverage = coverage;
        }

        public int[] toRgb() {
            return new int[]{r, g, b};
        }

        @Override
        public String toString() {
            return "RGB(" + r + "," + g + "," + b + ") 样本 " + sampleCount
                    + "px 覆盖率 " + String.format("%.1f%%", coverage * 100);
        }
    }

    /**
     * @param pngBase64 透明底 PNG 的 base64（可带 dataURL 前缀）
     * @return 估计到的原背景色；样本不足或解码失败返回 {@code null}
     */
    public static int[] estimate(String pngBase64) {
        Result r = estimateDetailed(pngBase64);
        return r == null ? null : r.toRgb();
    }

    /**
     * 带诊断信息的估计。任何异常都吞掉并返回 {@code null}——
     * 背景色只是「锦上添花」的优化项，绝不能让它的失败影响抠图主流程。
     */
    public static Result estimateDetailed(String pngBase64) {
        if (pngBase64 == null || pngBase64.isEmpty()) {
            return null;
        }
        try {
            String b64 = stripDataUrlPrefix(pngBase64);
            byte[] png = Base64.getDecoder().decode(b64);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                return null;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }

            // 一次性批量取回像素，比逐点 getRGB 快一个量级
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);

            // 逐通道直方图（值域 0~255，天然有界，无需排序）
            int[][] hist = new int[3][256];
            int count = 0;
            for (int argb : px) {
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha > ALPHA_MAX) {
                    continue;
                }
                hist[0][(argb >>> 16) & 0xFF]++;
                hist[1][(argb >>> 8) & 0xFF]++;
                hist[2][argb & 0xFF]++;
                count++;
            }

            double coverage = (double) count / ((double) w * h);
            if (count < MIN_COUNT || coverage < MIN_COVERAGE) {
                // 抠图结果几乎没有透明区（比如背景全被判成前景），
                // 这种情况给不出可信的原背景色，交回调用方兜底
                return null;
            }

            int r = medianOf(hist[0], count);
            int g = medianOf(hist[1], count);
            int b = medianOf(hist[2], count);
            return new Result(r, g, b, count, coverage);
        } catch (Exception e) {
            return null;
        }
    }

    /** 直方图中位数：累计到一半样本所在的那个灰度级 */
    private static int medianOf(int[] hist, int n) {
        int half = n / 2;
        int acc = 0;
        for (int v = 0; v < hist.length; v++) {
            acc += hist[v];
            if (acc > half) {
                return v;
            }
        }
        return 255;
    }

    private static String stripDataUrlPrefix(String s) {
        String t = s.trim();
        int i = t.indexOf("base64,");
        return i >= 0 ? t.substring(i + "base64,".length()).trim() : t;
    }
}
