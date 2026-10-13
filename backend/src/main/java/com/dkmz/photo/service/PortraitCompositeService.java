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
 * 将腾讯返回的透明 PNG 按其 alpha 合成到目标背景。
 * PNG 的透明区域 RGB 不保证保存原背景，不能据此估计背景后反推前景。
 * 保留软 alpha 和实心亮色，避免删除发丝、眼镜反光及浅色衣物。
 * 全程内存处理，不落盘。
 */
@Service
public class PortraitCompositeService {

    private static final Logger log = LoggerFactory.getLogger(PortraitCompositeService.class);

    /** 成品图 JPEG 质量：端上还会按规格体积二次导出，这里给足画质余量 */
    private static final float JPEG_QUALITY = 0.95f;

    /**
     * 合成成品图。
     *
     * @param pngBase64   BDA 人像分割返回的透明底 PNG base64（可带 dataURL 前缀）
     * @param bgHex       目标底色，如 "#FFFFFF"（非法值退回纯白）
     * @param outTopRows  裁剪框越出原图的顶部行数（这些行必是补的白底，强制透明）
     * @return 成品 JPEG 的 base64（不透明，保留原有软边缘）
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
        // 只有原图之外的补白行可以确定为背景；不按亮度猜测人体透明度。
        int top = Math.max(0, Math.min(h, outTopRows));
        java.util.Arrays.fill(alpha, 0, top * w, 0f);
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
