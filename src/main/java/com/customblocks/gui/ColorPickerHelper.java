package com.customblocks.gui;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Phase G1 — Pro Color Picker Studio (server-side logic).
 * <p>
 * Provides colour manipulation utilities: HSL ↔ RGB conversion, palette generation,
 * auto-gradient between two colours, complementary / analogous / triadic palettes.
 */
public final class ColorPickerHelper {

    private ColorPickerHelper() {}

    // ── RGB ↔ HSL ────────────────────────────────────────────────────────

    /** Convert RGB (0-255 each) to HSL (H: 0-360, S: 0-100, L: 0-100). */
    public static float[] rgbToHsl(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float h = 0, s, l = (max + min) / 2f;

        if (max == min) {
            s = 0; // achromatic
        } else {
            float d = max - min;
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == rf) h = (gf - bf) / d + (gf < bf ? 6f : 0f);
            else if (max == gf) h = (bf - rf) / d + 2f;
            else h = (rf - gf) / d + 4f;
            h /= 6f;
        }
        return new float[]{h * 360f, s * 100f, l * 100f};
    }

    /** Convert HSL (H: 0-360, S: 0-100, L: 0-100) to RGB (0-255 each). */
    public static int[] hslToRgb(float h, float s, float l) {
        h /= 360f; s /= 100f; l /= 100f;
        float r, g, b;
        if (s == 0) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
            float p = 2f * l - q;
            r = hue2rgb(p, q, h + 1f / 3f);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 1f / 3f);
        }
        return new int[]{Math.round(r * 255), Math.round(g * 255), Math.round(b * 255)};
    }

    private static float hue2rgb(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6f) return p + (q - p) * 6f * t;
        if (t < 1f / 2f) return q;
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
        return p;
    }

    // ── Hex ↔ RGB ────────────────────────────────────────────────────────

    /** Parse #RRGGBB or RRGGBB. Returns null on failure. */
    public static int[] hexToRgb(String hex) {
        if (hex == null) return null;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        try {
            int v = Integer.parseInt(s, 16);
            return new int[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** RGB to #RRGGBB. */
    public static String rgbToHex(int r, int g, int b) {
        return String.format("#%02X%02X%02X", r & 0xFF, g & 0xFF, b & 0xFF);
    }

    // ── Phase G2: Auto-Gradient ──────────────────────────────────────────

    /**
     * Generate a smooth gradient between two colours (in HSL space).
     * @param hexFrom start colour (#RRGGBB)
     * @param hexTo   end colour (#RRGGBB)
     * @param steps   number of gradient stops (minimum 2)
     * @return array of hex strings
     */
    public static String[] generateGradient(String hexFrom, String hexTo, int steps) {
        steps = Math.max(2, Math.min(64, steps));
        int[] from = hexToRgb(hexFrom);
        int[] to = hexToRgb(hexTo);
        if (from == null || to == null) return new String[]{hexFrom, hexTo};

        float[] hslFrom = rgbToHsl(from[0], from[1], from[2]);
        float[] hslTo = rgbToHsl(to[0], to[1], to[2]);

        String[] result = new String[steps];
        for (int i = 0; i < steps; i++) {
            float t = steps == 1 ? 0f : (float) i / (steps - 1);
            float h = lerp(hslFrom[0], hslTo[0], t);
            float s = lerp(hslFrom[1], hslTo[1], t);
            float l = lerp(hslFrom[2], hslTo[2], t);
            int[] rgb = hslToRgb(h, s, l);
            result[i] = rgbToHex(rgb[0], rgb[1], rgb[2]);
        }
        return result;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ── Texture Recoloring ───────────────────────────────────────────────

    /**
     * Recolour a texture by shifting all non-black pixels toward a target colour
     * while preserving luminance and edge detail.
     *
     * @param image the source image (modified in-place)
     * @param targetR target red 0-255
     * @param targetG target green 0-255
     * @param targetB target blue 0-255
     * @param strength 0.0 = no change, 1.0 = full recolour
     */
    public static void recolorImage(BufferedImage image, int targetR, int targetG, int targetB, float strength) {
        strength = Math.max(0f, Math.min(1f, strength));
        int w = image.getWidth(), h = image.getHeight();
        float[] targetHsl = rgbToHsl(targetR, targetG, targetB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                if (a < 10) continue; // skip transparent
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                // Skip near-black pixels (preserve edges/outlines)
                if (r + g + b < 30) continue;

                float[] pixelHsl = rgbToHsl(r, g, b);
                // Blend hue and saturation toward target; keep original lightness
                float newH = lerp(pixelHsl[0], targetHsl[0], strength);
                float newS = lerp(pixelHsl[1], targetHsl[1], strength);
                float newL = pixelHsl[2]; // preserve luminance
                int[] newRgb = hslToRgb(newH, newS, newL);
                image.setRGB(x, y, (a << 24) | (newRgb[0] << 16) | (newRgb[1] << 8) | newRgb[2]);
            }
        }
    }

    // ── Block Dressing Overlays (Phase G3) ────────────────────────────────

    /**
     * Apply a semi-transparent overlay tint to a texture (for "block dressing").
     * @param image the source image (modified in-place)
     * @param overlayR overlay red 0-255
     * @param overlayG overlay green 0-255
     * @param overlayB overlay blue 0-255
     * @param opacity 0.0 = invisible, 1.0 = fully opaque overlay
     */
    public static void applyOverlay(BufferedImage image, int overlayR, int overlayG, int overlayB, float opacity) {
        opacity = Math.max(0f, Math.min(1f, opacity));
        int w = image.getWidth(), h = image.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                if (a < 10) continue;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                r = Math.round(r * (1f - opacity) + overlayR * opacity);
                g = Math.round(g * (1f - opacity) + overlayG * opacity);
                b = Math.round(b * (1f - opacity) + overlayB * opacity);
                image.setRGB(x, y, (a << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b));
            }
        }
    }

    private static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }
}
