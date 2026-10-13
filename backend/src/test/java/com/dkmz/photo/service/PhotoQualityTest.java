package com.dkmz.photo.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PhotoQualityTest {
    private static BufferedImage solid(int type, int color) {
        BufferedImage image = new BufferedImage(64, 64, type);
        int[] pixels = new int[64 * 64];
        Arrays.fill(pixels, color);
        image.setRGB(0, 0, 64, 64, pixels, 0, 64);
        return image;
    }

    private static String png(BufferedImage image) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static BufferedImage composite(BufferedImage image, String background, int top) throws Exception {
        String result = new PortraitCompositeService().composite(png(image), background, top);
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(result)));
    }

    private static void near(int actual, int expected, int tolerance) {
        for (int shift : new int[]{16, 8, 0}) {
            assertEquals((expected >> shift) & 255, (actual >> shift) & 255, tolerance);
        }
    }

    @Test
    void transparentHiddenRgbMustNotInfluenceVisibleForeground() throws Exception {
        BufferedImage black = solid(BufferedImage.TYPE_INT_ARGB, 0x00000000);
        BufferedImage white = solid(BufferedImage.TYPE_INT_ARGB, 0x00FFFFFF);
        for (int y = 16; y < 48; y++) {
            for (int x = 16; x < 48; x++) {
                black.setRGB(x, y, 0x8096785A);
                white.setRGB(x, y, 0x8096785A);
            }
        }
        BufferedImage a = composite(black, "#438EDB", 0);
        BufferedImage b = composite(white, "#438EDB", 0);
        assertArrayEquals(a.getRGB(0, 0, 64, 64, null, 0, 64), b.getRGB(0, 0, 64, 64, null, 0, 64));
        near(a.getRGB(32, 32), 0x6D839A, 3);
    }

    @Test
    void fineHairWithLowAlphaIsPreserved() throws Exception {
        BufferedImage result = composite(solid(BufferedImage.TYPE_INT_ARGB, 0x141E1E1E), "#FFFFFF", 0);
        near(result.getRGB(32, 32), 0xEDEDED, 3);
    }

    @Test
    void opaqueHighlightsAreNotDeletedOnDarkBackground() throws Exception {
        BufferedImage image = solid(BufferedImage.TYPE_INT_ARGB, 0);
        for (int y = 12; y < 52; y++) {
            for (int x = 12; x < 52; x++) image.setRGB(x, y, 0xFFDCDCDC);
        }
        BufferedImage result = composite(image, "#B80202", 0);
        near(result.getRGB(14, 20), 0xDCDCDC, 6);
    }

    @Test
    void paddedRowsAreBackgroundOnly() throws Exception {
        BufferedImage result = composite(solid(BufferedImage.TYPE_INT_ARGB, 0xFFB4A08C), "#438EDB", 16);
        near(result.getRGB(32, 4), 0x438EDB, 3);
        near(result.getRGB(32, 32), 0xB4A08C, 3);
    }

    @Test
    void smoothingPreservesFlatColorsAndFeatureEdges() throws Exception {
        BufferedImage image = solid(BufferedImage.TYPE_INT_RGB, 0xFFB49678);
        for (int y = 0; y < 64; y++) {
            for (int x = 32; x < 64; x++) image.setRGB(x, y, 0xFF191919);
        }
        float[] weights = new float[4096];
        Arrays.fill(weights, 1f);
        Method smooth = IdPhotoBeautyService.class.getDeclaredMethod("smooth", BufferedImage.class, float[].class, double.class);
        smooth.setAccessible(true);
        BufferedImage result = (BufferedImage) smooth.invoke(new IdPhotoBeautyService(), image, weights, 0.4);
        assertArrayEquals(image.getRGB(0, 0, 64, 64, null, 0, 64), result.getRGB(0, 0, 64, 64, null, 0, 64));
    }

    @Test
    void emptySkinMaskMustNotReportBeautySuccess() throws Exception {
        SkinBeautyService service = new SkinBeautyService(new IdPhotoBeautyService());
        String image = png(solid(BufferedImage.TYPE_INT_RGB, 0xFFB49678));
        String mask = png(solid(BufferedImage.TYPE_INT_RGB, 0xFF000000));
        assertThrows(IllegalArgumentException.class,
                () -> service.beautify(image, mask, IdPhotoBeautyService.Level.NORMAL));
    }

    @Test
    void smoothingReducesTextureWithoutBrightnessBias() throws Exception {
        BufferedImage image = solid(BufferedImage.TYPE_INT_RGB, 0);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int noise = (x + y) % 2 == 0 ? 8 : -8;
                image.setRGB(x, y, ((180 + noise) << 16) | ((150 + noise) << 8) | (120 + noise));
            }
        }
        float[] weights = new float[4096];
        Arrays.fill(weights, 1f);
        Method smooth = IdPhotoBeautyService.class.getDeclaredMethod("smooth", BufferedImage.class, float[].class, double.class);
        smooth.setAccessible(true);
        BufferedImage result = (BufferedImage) smooth.invoke(new IdPhotoBeautyService(), image, weights, 0.4);
        int bright = (result.getRGB(30, 30) >> 16) & 255;
        int dark = (result.getRGB(31, 30) >> 16) & 255;
        assertTrue(bright - dark < 16, "应降低细碎纹理");
        assertTrue(bright - dark >= 8, "应保留自然纹理");
        assertEquals(180, (bright + dark) / 2.0, 0.5, "不应因取整产生系统性变暗");
    }
}
