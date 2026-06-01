package com.customblocks.texturegen;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generates the 16×16 deleter item texture — a red recycle bin icon.
 * Run via: ./gradlew generateItemTextures
 * Output: src/main/resources/assets/customblocks/textures/item/deleter.png
 */
public class GenerateDeleterTexture {

    // Palette
    private static final int OUTLINE   = color(0x33, 0x00, 0x00);
    private static final int SHADOW    = color(0x88, 0x11, 0x00);
    private static final int BODY      = color(0xCC, 0x22, 0x00);
    private static final int HIGHLIGHT = color(0xFF, 0x44, 0x22);
    private static final int SYMBOL    = color(0xFF, 0xFF, 0xFF);
    private static final int TRANSP    = 0x00000000;

    private static int color(int r, int g, int b) {
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0]
            : "src/main/resources/assets/customblocks/textures/item/deleter.png";

        BufferedImage img = generate();
        File out = new File(outPath);
        out.getParentFile().mkdirs();
        ImageIO.write(img, "PNG", out);
        System.out.println("Deleter texture written to: " + out.getAbsolutePath());
    }

    public static BufferedImage generate() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);

        // Fill transparent
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                img.setRGB(x, y, TRANSP);

        // ── Lid (rows 1-2, cols 3-12) ────────────────────────────────────────
        //  Outline top
        for (int x = 3; x <= 12; x++) img.setRGB(x, 1, OUTLINE);
        //  Lid fill
        for (int x = 4; x <= 11; x++) img.setRGB(x, 2, BODY);
        img.setRGB(3,  2, OUTLINE);
        img.setRGB(12, 2, OUTLINE);
        // Handle bump on lid
        for (int x = 6; x <= 9; x++) img.setRGB(x, 0, OUTLINE);
        img.setRGB(6, 1, BODY);
        img.setRGB(7, 1, HIGHLIGHT);
        img.setRGB(8, 1, BODY);
        img.setRGB(9, 1, BODY);

        // ── Body (rows 3-12, cols 3-12 with taper) ───────────────────────────
        for (int row = 3; row <= 12; row++) {
            int left  = 3;
            int right = 12;
            img.setRGB(left,  row, OUTLINE);
            img.setRGB(right, row, OUTLINE);
            for (int x = left + 1; x < right; x++) {
                img.setRGB(x, row, (x == left + 1) ? HIGHLIGHT : BODY);
            }
        }

        // ── Base (row 13, cols 3-12) ─────────────────────────────────────────
        for (int x = 3; x <= 12; x++) img.setRGB(x, 13, OUTLINE);

        // ── Shadow sides (rows 3-12, cols 2 & 13) ────────────────────────────
        for (int row = 4; row <= 12; row++) {
            img.setRGB(2,  row, SHADOW);
            img.setRGB(13, row, SHADOW);
        }

        // ── White 'X' symbol on the front ────────
        // Center the X between x=5..10, row=5..10
        img.setRGB(5, 5, SYMBOL); img.setRGB(10, 5, SYMBOL);
        img.setRGB(6, 6, SYMBOL); img.setRGB(9,  6, SYMBOL);
        img.setRGB(7, 7, SYMBOL); img.setRGB(8,  7, SYMBOL);
        img.setRGB(7, 8, SYMBOL); img.setRGB(8,  8, SYMBOL);
        img.setRGB(6, 9, SYMBOL); img.setRGB(9,  9, SYMBOL);
        img.setRGB(5,10, SYMBOL); img.setRGB(10,10, SYMBOL);

        return img;
    }
}
