package com.dkmz.photo.support;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 抠图结果「只回 alpha」的编码器 —— 把 BDA 返回的透明底 PNG 压成 8 位灰度 PNG。
 *
 * <p>为什么要有这个类：产品有两个功能——「只替换背景」与「替换背景 + 美颜」。
 * 不美颜时，端上本地就有一模一样的 RGB 像素，服务端唯一能提供的新信息就是
 * alpha（哪些像素是人、哪些是背景）。把 RGB 再传一遍是纯浪费：
 * <pre>
 *   透明底 RGBA PNG（480×640）  约 200 ~ 400 KB
 *   alpha 灰度 PNG（480×640）   约  10 ~  30 KB
 * </pre>
 * 流量降一个数量级，用户等待时间同步变短，且天然规避「服务端改过的像素被丢弃」
 * 这类数据流问题——端上用的就是本地那份高清像素。
 *
 * <p>编码约定：<b>灰度值 = alpha 值</b>（255 = 前景/人，0 = 背景），
 * PNG 为 8 位灰度（无 alpha 通道）。端上解码时取 R 通道即得 alpha。
 * 与「透明底 PNG 的 alpha 通道」逐像素等值（见 AlphaExtractor 的验证脚本）。
 *
 * <p>安全：全程内存直通，不写盘、不写库，任何日志都不打印图片内容。
 */
public final class AlphaExtractor {

    private AlphaExtractor() {
    }

    /** 抽取结果：灰度 PNG base64 + 尺寸（端上据此确认构图一致） */
    public static final class Result {
        public final String base64;
        public final int width;
        public final int height;

        Result(String base64, int width, int height) {
            this.base64 = base64;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 透明底 PNG base64 → alpha 灰度 PNG base64。
     *
     * @param transparentPngBase64 BDA 抠图结果（可带 dataURL 前缀）
     * @throws IllegalArgumentException 数据为空、base64 非法或图片无法解析
     */
    public static Result extract(String transparentPngBase64) {
        String b64 = stripDataUrlPrefix(transparentPngBase64);
        if (b64 == null || b64.isEmpty()) {
            throw new IllegalArgumentException("抠图结果为空");
        }
        try {
            byte[] png = Base64.getDecoder().decode(b64);
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) {
                throw new IllegalArgumentException("抠图结果无法解析");
            }
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 0 || h <= 0) {
                throw new IllegalArgumentException("抠图结果尺寸异常");
            }

            // 一次性批量取回像素（逐点 getRGB 在这个量级会明显慢）
            int[] argb = src.getRGB(0, 0, w, h, null, 0, w);

            BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            WritableRaster raster = gray.getRaster();
            for (int i = 0; i < w * h; i++) {
                int a = (argb[i] >>> 24) & 0xFF;
                // 直接写采样点：绕过 setRGB 的颜色空间换算，保证灰度值精确等于 alpha
                raster.setSample(i % w, i / w, 0, a);
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(gray, "png", bos);
            return new Result(Base64.getEncoder().encodeToString(bos.toByteArray()), w, h);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("抠图结果编码失败：" + e.getMessage(), e);
        }
    }

    /** 剥离 data URL 前缀（data:image/png;base64,xxxx → xxxx） */
    private static String stripDataUrlPrefix(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        int i = t.indexOf("base64,");
        return i >= 0 ? t.substring(i + "base64,".length()).trim() : t;
    }
}
