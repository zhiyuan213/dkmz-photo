package com.dkmz.photo.service;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class NaturalRetouchTest {
    @Test void localHairGlareIsReducedButSkinAndAlphaArePreserved() {
        int[] rgb = new int[10000];
        float[] alpha = new float[10000];
        for (int y = 20; y < 100; y++) for (int x = 20; x < 80; x++) {
            alpha[y * 100 + x] = 1;
            rgb[y * 100 + x] = 0x303030;
        }
        rgb[2450] = 0xD0D0D0;
        rgb[2451] = 0xB49678;
        rgb[5050] = 0xD0D0D0;
        float[] before = alpha.clone();
        NaturalRetouch.hairHighlights(100, 100, alpha, rgb, 0);
        assertTrue((rgb[2450] & 255) < 208);
        assertTrue((rgb[2450] & 255) >= 190);
        assertEquals(0xB49678, rgb[2451]);
        assertEquals(0xD0D0D0, rgb[5050]);
        assertEquals(0x303030, rgb[2449]);
        assertArrayEquals(before, alpha);
    }

    @Test void predominantlyWhiteHairIsUnchanged() {
        int[] rgb = new int[10000]; Arrays.fill(rgb, 0xD0D0D0);
        float[] alpha = new float[10000]; Arrays.fill(alpha, 1f);
        int[] before = rgb.clone();
        NaturalRetouch.hairHighlights(100, 100, alpha, rgb, 0);
        assertArrayEquals(before, rgb);
    }

    @Test void blemishCorrectionPreservesNormalSkinMolesAndMaskBoundary() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) image.setRGB(x, y, 0xB49678);
        image.setRGB(16, 16, 0xCE9678);
        image.setRGB(8, 8, 0x302820);
        image.setRGB(25, 25, 0xCE9678);
        boolean[] skin = new boolean[1024]; Arrays.fill(skin, true);
        skin[25 * 32 + 26] = false;
        BufferedImage result = new IdPhotoBeautyService().beautify(image, skin, IdPhotoBeautyService.Level.NATURAL);
        int r = (result.getRGB(16, 16) >> 16) & 255;
        assertTrue(r < 206 && r >= 196);
        assertEquals(0x9678, result.getRGB(16, 16) & 65535);
        assertEquals(image.getRGB(8, 8), result.getRGB(8, 8));
        assertEquals(image.getRGB(25, 25), result.getRGB(25, 25));
        assertEquals(image.getRGB(4, 20), result.getRGB(4, 20));
        assertEquals(0xCE9678, image.getRGB(16, 16) & 0xFFFFFF);
    }
}
