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
    @Test
    void naturalTonePreservesChromaTransparencyAndTexture() {
        int[] rgb = {0xFFFFFF, 0x000000, 0x505050, 0xB49678, 0xB6987A, 0x123456};
        int[] before = rgb.clone();
        float[] alpha = {1, 1, 1, 1, 1, 0};
        PortraitCompositeService.enhanceNaturalTone(alpha, rgb);
        assertEquals(before[0], rgb[0]);
        assertEquals(before[1], rgb[1]);
        assertEquals(before[5], rgb[5]);
        assertTrue((rgb[2] & 255) < 80, "暗部不能被抬灰");
        for (int i = 2; i <= 4; i++) {
            int dr = ((rgb[i] >> 16) & 255) - ((before[i] >> 16) & 255);
            assertEquals(dr, ((rgb[i] >> 8) & 255) - ((before[i] >> 8) & 255));
            assertEquals(dr, (rgb[i] & 255) - (before[i] & 255));
            assertTrue(Math.abs(dr) <= 3);
        }
        assertTrue((rgb[4] & 255) > (rgb[3] & 255), "局部纹理必须保留");
    }

    private static class HairFixture {
        final float[] alpha = new float[4096];
        final int[] rgb = new int[4096];
        final int[] original = new int[4096];
        final int edge = 20 * 64 + 16;
        HairFixture() {
            Arrays.fill(original, 0xF0F0F0);
            for (int y = 16; y < 48; y++) {
                for (int x = 17; x < 48; x++) {
                    int i = y * 64 + x;
                    alpha[i] = 1;
                    rgb[i] = original[i] = 0x3C3C3C;
                }
            }
            alpha[edge] = 0.5f;
            rgb[edge] = original[edge] = 0x969696;
        }
        void clean() { PortraitCompositeService.cleanHairEdge(64, 64, alpha, rgb, original, 0); }
    }

    @Test
    void hairEdgeRemovesContaminationWithoutChangingAlphaOrOpaqueHighlights() {
        HairFixture f = new HairFixture();
        int highlight = 20 * 64 + 20;
        f.rgb[highlight] = f.original[highlight] = 0xDCDCDC;
        float[] before = f.alpha.clone();
        f.clean();
        assertTrue((f.rgb[f.edge] & 255) < 150);
        assertTrue((f.rgb[f.edge] & 255) >= 108, "下调幅度应有上限");
        assertEquals(0xDCDCDC, f.rgb[highlight]);
        assertArrayEquals(before, f.alpha, "不得削除发丝透明度");
    }

    @Test
    void alreadyDecontaminatedPngIsNotCorrectedAgain() {
        HairFixture f = new HairFixture();
        f.rgb[f.edge] = 0x3C3C3C;
        f.clean();
        assertEquals(0x3C3C3C, f.rgb[f.edge]);
    }

    @Test
    void coloredSkinAndLowerFaceArePreserved() {
        HairFixture f = new HairFixture();
        f.rgb[f.edge] = f.original[f.edge] = 0xB49678;
        int eye = 40 * 64 + 16;
        f.alpha[eye] = 0.5f;
        f.rgb[eye] = f.original[eye] = 0x969696;
        f.clean();
        assertEquals(0xB49678, f.rgb[f.edge]);
        assertEquals(0x969696, f.rgb[eye]);
    }

    @Test
    void whiteHairOnLightBackgroundIsNotDarkened() {
        HairFixture f = new HairFixture();
        Arrays.fill(f.rgb, 0xDCDCDC);
        for (int i = 0; i < f.alpha.length; i++) if (f.alpha[i] > 0) f.original[i] = 0xDCDCDC;
        f.clean();
        assertEquals(0xDCDCDC, f.rgb[f.edge]);
    }

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
