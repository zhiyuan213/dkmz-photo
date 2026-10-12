package com.dkmz.photo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Iterator;

/**
 * 云端「成品合成」：把 BDA 返回的透明底 PNG 一次做成可直接交付的成品 JPEG。
 *
 * 为什么放在后端做（而不是继续留给端上）：
 *   1. 端上唯一的像素来源是本地裁剪图，云端美白的像素进不了成片（历史 bug：
 *      maskFromPng 只读 alpha、buildComposite 用本地 croppedPath）——
 *      由后端把「美白后的人像像素 + alpha + 目标底色」一次合成，美白才真正生效；
 *   2. 端上把带 alpha 的 PNG drawImage 到透明离屏画布后，alpha=0 区域的 RGB 会被
 *      清零，导致「估计原背景色」取到黑色而全线崩坏；后端直接在 RGBA 原数组上
 *      处理，不存在这个陷阱；
 *   3. 返回不透明 JPEG（约 60~90KB）比透明 PNG（约 400KB）小一个量级，
 *      离 callContainer 的 1MB 响应上限（-606002）更远。
 *
 * 算法与端上 pipeline.js 的 decontaminateEdge 逐行对齐（同一套数学），
 * 云端 / 端上两条通道的换底观感保持一致：
 *   观测色 C = F·a + B·(1−a)   （F = 纯人像色，B = 原背景色，a = alpha）
 *   ① 半透明边缘：按 a 反推 F，相当于把「原背景贡献」置换为目标底色；
 *   ② 白墙残影：蓬松发丝 + 浅色背景处，分割会把 alpha 误判为实心，但颜色是
 *      「发丝 + 白墙」的低饱和灰白混合色（白底无感，红/蓝底显形为头顶白霜）
 *      → 改用颜色证据重估背景占比 w，并限制在上半区、且距真背景 ≤14px
 *      （chamfer 距离场）内，保护镜片反光等远离背景的亮色区域。
 *
 * 安全：全程内存直通，不落盘、不写库、日志不打印任何图片内容。
 */
@Service
public class PortraitCompositeService {

    private static final Logger log = LoggerFactory.getLogger(PortraitCompositeService.class);

    /** 残影重估的作用距离（px）：只有贴着真背景的低饱和亮色才可能是白墙混合 */
    private static final int GHOST_DIST = 14;
    /** 残影重估的饱和度上限（max(R,G,B) − min(R,G,B)） */
    private static final int SAT_MAX = 42;
    /** 残影重估只作用在上半区（发丝集中区），下半区是肩线/衣服的硬边缘 */
    private static final double GHOST_ROW_RATIO = 0.58;
    /** 成品图 JPEG 质量：端上还会按规格体积二次导出，这里给足画质余量 */
    private static final float JPEG_QUALITY = 0.95f;

    /**
     * 合成成品图。
     *
     * @param pngBase64   BDA 人像分割返回的透明底 PNG base64（可带 dataURL 前缀）
     * @param bgHex       目标底色，如 "#FFFFFF"（非法值退回纯白）
     * @param outTopRows  裁剪框越出原图的顶部行数（这些行必是补的白底，强制透明）
     * @return 成品 JPEG 的 base64（不透明，已含换底与边缘净化）
     */
    public String composite(String pngBase64, String bgHex, int outTopRows) {
        BufferedImage src = decode(pngBase64);
        int w = src.getWidth();
        int h = src.getHeight();
        int n = w * h;

        int[] px = new int[n];
        src.getRGB(0, 0, w, h, px, 0, w);

        // 拆成「alpha + 不透明 RGB」两个平面：后续所有运算都在数组上做，
        // 避免任何 drawImage 造成的 alpha=0 区 RGB 清零
        float[] alpha = new float[n];
        int[] rgb = new int[n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            alpha[i] = ((c >>> 24) & 0xFF) / 255f;
            rgb[i] = c & 0xFFFFFF;
        }

        int[] bg = parseHex(bgHex);
        decontaminate(w, h, alpha, rgb, outTopRows, bg);
        int[] dst = new int[n];
        for (int i = 0; i < n; i++) {
            float a = alpha[i];
            int r;
            int g;
            int b;
            if (a <= 0f) {
                r = bg[0];
                g = bg[1];
                b = bg[2];
            } else if (a >= 1f) {
                r = (rgb[i] >> 16) & 0xFF;
                g = (rgb[i] >> 8) & 0xFF;
                b = rgb[i] & 0xFF;
            } else {
                float ia = 1f - a;
                r = clamp(Math.round(((rgb[i] >> 16) & 0xFF) * a + bg[0] * ia));
                g = clamp(Math.round(((rgb[i] >> 8) & 0xFF) * a + bg[1] * ia));
                b = clamp(Math.round((rgb[i] & 0xFF) * a + bg[2] * ia));
            }
            dst[i] = (r << 16) | (g << 8) | b;
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, dst, 0, w);
        String jpeg = encodeJpeg(out, JPEG_QUALITY);
        log.info("成品合成 ok {}x{} 输出 {}KB", w, h, jpeg.length() / 1024);
        return jpeg;
    }

    // ------------------------------------------------------------------
    // 边缘去污染 + 白墙残影重估（原位修改 alpha / rgb）
    // ------------------------------------------------------------------
    private static void decontaminate(int w, int h, float[] alpha, int[] rgb, int outTopRows, int[] targetBg) {
        int n = w * h;

        // 0) 裁剪框越出原图的顶部行：一定是补的白底背景，强制透明
        int top = Math.max(0, Math.min(h, outTopRows));
        for (int y = 0; y < top; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                alpha[row + x] = 0f;
            }
        }

        // 1) 估计原背景色 B（近透明像素均值；样本太少则退回纯白）
        double sr = 0;
        double sg = 0;
        double sb = 0;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (alpha[i] < 0.03f) {
                int c = rgb[i];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
                cnt++;
            }
        }
        boolean enough = cnt > n * 0.005;
        double b0 = enough ? sr / cnt : 255;
        double b1 = enough ? sg / cnt : 255;
        double b2 = enough ? sb / cnt : 255;
        double lumaBg = (b0 + b1 + b2) / 3;
        double lumMin = Math.max(150, lumaBg - 70);
        double lumBase = Math.max(1, lumaBg - 70);

        // 残影重估门控：只在「目标底色明显比原背景暗」时启用。
        // 白底→白底时头发/亮肤本身就长得像「白墙混合色」，重估会把大把头发/皮肤
        // 像素的 alpha 砍掉、前景反推被钳制成黑 ⇒ 铺白底后整片银灰（10-12 真机事故）；
        // 而白底目标下「灰白实心」与「半透明深色」观感本就几乎一致，跳过零损失。
        // 红/蓝底（比白墙暗 100+ 亮度）照常启用，治头顶白霜。
        double targetLuma = (targetBg[0] + targetBg[1] + targetBg[2]) / 3.0;
        boolean ghostOn = lumaBg - targetLuma >= 40;

        // 2) chamfer 距离场：0 = 确定背景（alpha<0.08），其余为到背景的近似欧氏距离
        float[] dist = new float[n];
        for (int i = 0; i < n; i++) {
            dist[i] = alpha[i] < 0.08f ? 0f : 1e6f;
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float v = dist[i];
                if (x > 0 && dist[i - 1] + 1 < v) v = dist[i - 1] + 1;
                if (y > 0) {
                    if (dist[i - w] + 1 < v) v = dist[i - w] + 1;
                    if (x > 0 && dist[i - w - 1] + 1.41f < v) v = dist[i - w - 1] + 1.41f;
                    if (x < w - 1 && dist[i - w + 1] + 1.41f < v) v = dist[i - w + 1] + 1.41f;
                }
                dist[i] = v;
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = y * w + x;
                float v = dist[i];
                if (x < w - 1 && dist[i + 1] + 1 < v) v = dist[i + 1] + 1;
                if (y < h - 1) {
                    if (dist[i + w] + 1 < v) v = dist[i + w] + 1;
                    if (x < w - 1 && dist[i + w + 1] + 1.41f < v) v = dist[i + w + 1] + 1.41f;
                    if (x > 0 && dist[i + w - 1] + 1.41f < v) v = dist[i + w - 1] + 1.41f;
                }
                dist[i] = v;
            }
        }

        // 3) 反推 + 残影重估 + 收边
        int ghostRow = (int) Math.floor(h * GHOST_ROW_RATIO);
        for (int y = 0; y < h; y++) {
            boolean inGhost = y < ghostRow;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float a = alpha[i];
                if (a <= 0f) continue;

                double bgRatio = 1 - a;   // mask 证据：背景占比

                // 颜色证据：上半区、近背景、低饱和、偏亮的像素按亮度重估
                if (ghostOn && inGhost && dist[i] <= GHOST_DIST) {
                    int c = rgb[i];
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;
                    int mx = Math.max(r, Math.max(g, b));
                    int mn = Math.min(r, Math.min(g, b));
                    if (mx - mn <= SAT_MAX) {
                        double lum = (r + g + b) / 3.0;
                        if (lum >= lumMin) {
                            double guess = Math.min(1, (lum - 70) / lumBase);
                            bgRatio = Math.max(bgRatio, guess);
                        }
                    }
                }

                if (bgRatio >= 0.9) {        // 背景占比过高（含原 a<0.1 的清零逻辑）
                    alpha[i] = 0f;
                    continue;
                }
                double inv = 1 / (1 - bgRatio);
                int c = rgb[i];
                int r = clamp((int) Math.round((((c >> 16) & 0xFF) - b0 * bgRatio) * inv));
                int g = clamp((int) Math.round((((c >> 8) & 0xFF) - b1 * bgRatio) * inv));
                int b = clamp((int) Math.round(((c & 0xFF) - b2 * bgRatio) * inv));
                rgb[i] = (r << 16) | (g << 8) | b;
                alpha[i] = (float) (1 - bgRatio);
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------
    private static BufferedImage decode(String imageBase64) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(
                    Base64.getDecoder().decode(stripDataUrlPrefix(imageBase64))));
        } catch (Exception e) {
            throw new IllegalArgumentException("分割结果解码失败");
        }
        if (img == null) throw new IllegalArgumentException("分割结果解码失败");
        return img;
    }

    private static String encodeJpeg(BufferedImage img, float quality) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpg");
            if (!it.hasNext()) throw new IllegalStateException("无 JPEG 编码器");
            ImageWriter writer = it.next();
            try {
                ImageOutputStream ios = ImageIO.createImageOutputStream(bos);
                writer.setOutput(ios);
                ImageWriteParam p = writer.getDefaultWriteParam();
                if (p.canWriteCompressed()) {
                    p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    p.setCompressionQuality(quality);
                }
                writer.write(null, new IIOImage(img, null, null), p);
                ios.close();
            } finally {
                writer.dispose();
            }
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("成品图编码失败", e);
        }
    }

    /** "#RRGGBB" / "RRGGBB" → int[3]；非法值退回纯白 */
    private static int[] parseHex(String hex) {
        int[] white = {255, 255, 255};
        if (hex == null) return white;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return white;
        try {
            int v = Integer.parseInt(s, 16);
            return new int[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
        } catch (NumberFormatException e) {
            return white;
        }
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
