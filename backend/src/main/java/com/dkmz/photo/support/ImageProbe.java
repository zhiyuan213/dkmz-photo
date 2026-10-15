package com.dkmz.photo.support;

/**
 * 从图片字节里读尺寸（只解析文件头，不解码像素）。
 *
 * <p>用途：内容安全检测的审计日志必须能回答「送检的到底是哪张图」。
 * 证件照构图只保留头部与肩部，若误把裁剪图当原图送检，违规内容会先被我们
 * 自己的裁剪"洗掉"；日志里的尺寸（如 {@code 960x1280} 对上一张 4032×3024 的原图）
 * 会立刻暴露这种退化，而不是等审核被驳回才发现。
 *
 * <p>支持 JPEG / PNG（本次送检图固定为 JPEG，PNG 一并支持便于自检接口的现造图）；
 * 识别不出返回 null，调用方按「尺寸未知」处理，绝不影响主流程。
 */
public final class ImageProbe {

    private ImageProbe() {
    }

    /** @return {width, height}；无法识别返回 null */
    public static int[] size(byte[] bytes) {
        if (bytes == null || bytes.length < 24) {
            return null;
        }
        int[] png = pngSize(bytes);
        if (png != null) {
            return png;
        }
        return jpegSize(bytes);
    }

    /** 日志用短描述，如 {@code 960x1280}；未知返回 {@code ?} */
    public static String describe(byte[] bytes) {
        int[] s = size(bytes);
        return s == null ? "?" : s[0] + "x" + s[1];
    }

    private static int[] pngSize(byte[] b) {
        if ((b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') {
            return null;
        }
        // IHDR 固定在偏移 16（宽）/ 20（高），大端
        int w = be32(b, 16);
        int h = be32(b, 20);
        return w > 0 && h > 0 ? new int[] { w, h } : null;
    }

    /** JPEG 没有固定偏移，必须逐段跳过直到 SOFn */
    private static int[] jpegSize(byte[] b) {
        if ((b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            return null;
        }
        int i = 2;
        while (i < b.length - 8) {
            if ((b[i] & 0xFF) != 0xFF) {
                i++;   // 段间填充
                continue;
            }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) {
                i++;   // 多个 0xFF 连续
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;   // 无载荷的独立标记
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return null;   // 图像数据已开始，不会再有 SOF
            }
            int len = be16(b, i + 2);
            if (len < 2) {
                return null;
            }
            // SOF0~SOF15，排除 DHT(C4) / JPG(C8) / DAC(CC)
            boolean sof = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                int h = be16(b, i + 5);
                int w = be16(b, i + 7);
                return w > 0 && h > 0 ? new int[] { w, h } : null;
            }
            i += 2 + len;
        }
        return null;
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static int be16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }
}
