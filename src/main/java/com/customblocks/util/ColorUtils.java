package com.customblocks.util;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

public class ColorUtils {

    /** Blends two colors evenly. */
    public static int blendColors(int color1, int color2) {
        Color c1 = new Color(color1);
        Color c2 = new Color(color2);
        int r = (c1.getRed() + c2.getRed()) / 2;
        int g = (c1.getGreen() + c2.getGreen()) / 2;
        int b = (c1.getBlue() + c2.getBlue()) / 2;
        return new Color(r, g, b).getRGB();
    }

    /** Returns the complementary color (opposite on the color wheel). */
    public static int getComplementary(int color) {
        Color c = new Color(color);
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        hsb[0] = (hsb[0] + 0.5f) % 1.0f; // Shift hue by 180 degrees
        return Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
    }

    /** Returns analogous colors (neighbors on the color wheel). */
    public static int[] getAnalogous(int color) {
        Color c = new Color(color);
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        int a1 = Color.HSBtoRGB((hsb[0] + 0.08f) % 1.0f, hsb[1], hsb[2]);
        int a2 = Color.HSBtoRGB((hsb[0] - 0.08f + 1.0f) % 1.0f, hsb[1], hsb[2]);
        return new int[]{a1, a2};
    }

    /** Returns triadic colors (120 degrees offset). */
    public static int[] getTriadic(int color) {
        Color c = new Color(color);
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        int t1 = Color.HSBtoRGB((hsb[0] + 0.333f) % 1.0f, hsb[1], hsb[2]);
        int t2 = Color.HSBtoRGB((hsb[0] + 0.666f) % 1.0f, hsb[1], hsb[2]);
        return new int[]{t1, t2};
    }

    /** Generates 8 shades of a given color, from dark to light. */
    public static int[] getShades(int color) {
        Color c = new Color(color);
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        int[] shades = new int[8];
        float minBri = 0.2f;
        float maxBri = 0.95f;
        float step = (maxBri - minBri) / 7.0f;
        for (int i = 0; i < 8; i++) {
            shades[i] = Color.HSBtoRGB(hsb[0], hsb[1], minBri + (i * step));
        }
        return shades;
    }

    /** Extracts the dominant color from a PNG byte array, skipping transparency. */
    public static int extractDominantColor(byte[] pngData) {
        if (pngData == null || pngData.length == 0) return 0xFFFFFF;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngData));
            if (img == null) return 0xFFFFFF;

            Map<Integer, Integer> colorCounts = new HashMap<>();
            int maxCount = 0;
            int dominantColor = 0xFFFFFF;

            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int argb = img.getRGB(x, y);
                    int alpha = (argb >> 24) & 0xFF;
                    if (alpha < 50) continue; // Skip mostly transparent pixels

                    int rgb = argb & 0xFFFFFF;
                    // Quantize to reduce noise (e.g. group similar colors)
                    int r = ((rgb >> 16) & 0xFF) / 16 * 16;
                    int g = ((rgb >> 8) & 0xFF) / 16 * 16;
                    int b = (rgb & 0xFF) / 16 * 16;
                    int quantized = (r << 16) | (g << 8) | b;

                    int count = colorCounts.getOrDefault(quantized, 0) + 1;
                    colorCounts.put(quantized, count);

                    if (count > maxCount) {
                        maxCount = count;
                        dominantColor = rgb; // Return actual color, not quantized
                    }
                }
            }
            return dominantColor;
        } catch (Exception e) {
            return 0xFFFFFF; // Fallback to white
        }
    }
}
