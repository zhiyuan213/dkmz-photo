package com.dkmz.photo.service;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/** 保守的局部修饰；不改变几何位置，不生成缺失细节。 */
final class NaturalRetouch {
    private NaturalRetouch() {}

    static void hairHighlights(int w, int h, float[] alpha, int[] rgb, int top) {
        int first = h;
        for (int y = top; y < h && first == h; y++) {
            int count = 0;
            for (int x = 0; x < w; x++) if (alpha[y * w + x] > .98f) count++;
            if (count >= Math.max(3, w / 20)) first = y;
        }
        int depth = Math.max(1, Math.round((h - first) * .12f));
        int end = Math.min(h, first + depth);
        int dark = 0, opaque = 0;
        for (int y = first; y < end; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x;
            if (alpha[i] < .98f) continue;
            opaque++;
            if (neutral(rgb[i]) && luma(rgb[i]) < 105) dark++;
        }
        // 大片白发、浅发或没有可靠深色头发证据时不处理。
        if (opaque == 0 || dark < opaque * .35) return;
        int[] source = rgb.clone();
        int radius = Math.max(2, Math.min(12, Math.round(w / 65f)));
        for (int y = first; y < end; y++) for (int x = radius; x < w - radius; x++) {
            int i = y * w + x, c = source[i];
            double light = luma(c);
            if (alpha[i] < .995f || !neutral(c) || light < 135 || light > 245) continue;
            int neighbors = 0, darkNeighbors = 0;
            for (int dy = -radius; dy <= radius; dy += 2) for (int dx = -radius; dx <= radius; dx += 2) {
                int ny = y + dy;
                if (ny < first || ny >= end) continue;
                int j = ny * w + x + dx;
                if (alpha[j] < .995f) continue;
                neighbors++;
                if (neutral(source[j]) && luma(source[j]) < 110) darkNeighbors++;
            }
            if (neighbors == 0 || darkNeighbors < neighbors * .3) continue;
            double fade = Math.min(1, (end - y) / (depth * .3));
            int reduction = (int) Math.round(Math.min(18, (light - 125) * .18) * fade);
            rgb[i] = shift(c, -reduction);
        }
    }

    /** 只减弱皮肤内部比周围更红的小点；深色痣及亮度边缘不参与。 */
    static BufferedImage blemishes(BufferedImage src, boolean[] skin) {
        int w = src.getWidth(), h = src.getHeight();
        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w), out = pixels.clone();
        int radius = Math.max(2, Math.min(5, Math.round(Math.max(w, h) / 240f)));
        int[] reds = new int[(radius * 2 + 1) * (radius * 2 + 1)];
        for (int y = radius; y < h - radius; y++) for (int x = radius; x < w - radius; x++) {
            int i = y * w + x, c = pixels[i];
            if (!skin[i] || luma(c) < 65) continue;
            int n = 0;
            boolean safe = true;
            for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) {
                int j = (y + dy) * w + x + dx;
                if (!skin[j] || Math.abs(luma(pixels[j]) - luma(c)) > 30) safe = false;
                reds[n++] = redness(pixels[j]);
            }
            if (!safe) continue;
            Arrays.sort(reds, 0, n);
            int excess = redness(c) - reds[n / 2];
            if (excess < 10 || excess > 65) continue;
            // 仅减弱局部红色，不涂平纹理；保留一部分原有色差。
            int correction = Math.min(10, (int) Math.round((excess - 8) * .3));
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            out[i] = (Math.max(0, r - correction) << 16) | (g << 8) | b;
        }
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, w, h, out, 0, w);
        return result;
    }

    private static int redness(int c) { return ((c >> 16) & 255) - ((c >> 8) & 255); }
    private static boolean neutral(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 24;
    }
    private static double luma(int c) { return .299 * ((c >> 16) & 255) + .587 * ((c >> 8) & 255) + .114 * (c & 255); }
    private static int shift(int c, int d) {
        return (Math.max(0, ((c >> 16) & 255) + d) << 16)
                | (Math.max(0, ((c >> 8) & 255) + d) << 8) | Math.max(0, (c & 255) + d);
    }
}
