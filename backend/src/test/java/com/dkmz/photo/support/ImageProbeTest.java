package com.dkmz.photo.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图片尺寸探针：内容安全检测的审计日志靠它证明「送检的是哪张图」。
 * 送检图若误用了裁剪工作图（长边 960/1280），尺寸字段会立刻对不上手机原图。
 */
class ImageProbeTest {

    /** 只含 SOI + APP0 + SOF0 + EOI 的最小 JPEG（探针只解析文件头，不校验完整数据） */
    private static byte[] jpeg(int w, int h) {
        return new byte[] {
                (byte) 0xFF, (byte) 0xD8,                                     // SOI
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00,
                0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,       // APP0
                (byte) 0xFF, (byte) 0xC0, 0x00, 0x11, 0x08,                   // SOF0
                (byte) (h >> 8), (byte) h,
                (byte) (w >> 8), (byte) w,
                0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
                (byte) 0xFF, (byte) 0xD9,                                     // EOI
        };
    }

    /** PNG：IHDR 固定在偏移 16/20 */
    private static byte[] png(int w, int h) {
        return new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
                (byte) (w >> 24), (byte) (w >> 16), (byte) (w >> 8), (byte) w,
                (byte) (h >> 24), (byte) (h >> 16), (byte) (h >> 8), (byte) h,
        };
    }

    @Test
    void readsJpegSizeFromMarkers() {
        int[] s = ImageProbe.size(jpeg(3, 2));
        assertArrayEquals(new int[] { 3, 2 }, s);
        assertEquals("3x2", ImageProbe.describe(jpeg(3, 2)));
        // 真实量级：手机原图 vs 裁剪工作图能一眼分开
        assertArrayEquals(new int[] { 3024, 4032 }, ImageProbe.size(jpeg(3024, 4032)));
        assertArrayEquals(new int[] { 960, 1280 }, ImageProbe.size(jpeg(960, 1280)));
    }

    @Test
    void readsPngSize() {
        assertArrayEquals(new int[] { 320, 600 }, ImageProbe.size(png(320, 600)));
    }

    @Test
    void unknownOrTruncatedBytesReturnNullInsteadOfThrowing() {
        assertNull(ImageProbe.size(null));
        assertNull(ImageProbe.size(new byte[] { 1, 2, 3 }), "过短（自检测试里的假图）");
        assertNull(ImageProbe.size(new byte[64]), "全零不是任何已知格式");
        assertEquals("?", ImageProbe.describe(new byte[] { 1, 2, 3 }));
    }
}
