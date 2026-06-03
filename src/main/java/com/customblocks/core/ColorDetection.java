package com.customblocks.core;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * Pixel-analysis color detection — the Layer 2 fallback when name scanning finds nothing.
 *
 * Algorithm (item 1.1 of the V3 master plan):
 *   1. Decode texture bytes to BufferedImage.
 *   2. Sample every 4th pixel, skip fully transparent ones (alpha < 10).
 *   3. Convert each sample to HSB color space.
 *   4. Achromatic pixels (saturation < 0.15):
 *        B < 0.2  → black bucket
 *        B > 0.8  → white bucket
 *        else     → gray bucket
 *   5. Chromatic pixels: bucket by hue into 12 × 30° segments.
 *   6. Largest bucket = dominant color family.
 *   7. If the dominant bucket has < 40% of all sampled pixels → AMBIGUOUS.
 *
 * Why HSB instead of RGB distance:
 *   RGB Euclidean treats (200,0,0) as "closer to black" than (255,50,50).
 *   HSB separates hue from brightness, so dark red → red (not black).
 */
public final class ColorDetection {

    /** Minimum saturation to be treated as chromatic. Below this = achromatic (black/white/gray). */
    private static final float ACHROMATIC_SATURATION = 0.15f;
    private static final float ACHROMATIC_BLACK_BRIGHTNESS = 0.20f;
    private static final float ACHROMATIC_WHITE_BRIGHTNESS = 0.80f;

    /** Minimum percentage of pixels in the dominant bucket to be considered "confident". */
    private static final double DOMINANT_THRESHOLD = 0.40;

    // Hue bucket → canonical color family name (12 buckets × 30° each, starting at 0°).
    // The bucket index = (int)(hue * 360 / 30) = (int)(hue * 12).
    private static final String[] HUE_BUCKET_FAMILIES = {
        "red",     // 0–30°    (0°  = pure red)
        "orange",  // 30–60°
        "yellow",  // 60–90°   (60° = pure yellow)
        "lime",    // 90–120°
        "green",   // 120–150° (120° = pure green)
        "cyan",    // 150–180°
        "cyan",    // 180–210° (180° = pure cyan)
        "blue",    // 210–240°
        "blue",    // 240–270° (240° = pure blue)
        "purple",  // 270–300°
        "magenta", // 300–330°
        "red",     // 330–360° (wraps back to red family)
    };

    /**
     * Analyse a texture and return the dominant color family.
     *
     * @param textureBytes raw PNG/GIF bytes (first frame used for animated)
     * @return a {@link DetectionResult} — check {@link DetectionResult#confident()} before trusting it
     */
    public static DetectionResult detect(byte[] textureBytes) {
        if (textureBytes == null || textureBytes.length == 0) {
            return DetectionResult.noTexture();
        }

        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(textureBytes));
        } catch (Exception e) {
            return DetectionResult.error("Could not decode texture.");
        }
        if (img == null) return DetectionResult.error("Could not decode texture.");

        int w = img.getWidth();
        int h = img.getHeight();
        if (w == 0 || h == 0) return DetectionResult.noTexture();

        // Bucket counts: indices 0-11 = hue buckets, 12 = black, 13 = white, 14 = gray
        int[] buckets = new int[15];
        int totalSampled = 0;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                // Only sample the outer edges (top row, bottom row, left col, right col)
                if (x > 0 && x < w - 1 && y > 0 && y < h - 1) continue;

                int argb = img.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;
                if (alpha < 10) continue; // skip transparent

                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8)  & 0xFF;
                int b =  argb        & 0xFF;

                float[] hsb = Color.RGBtoHSB(r, g, b, null);
                float sat  = hsb[1];
                float bri  = hsb[2];

                if (sat < ACHROMATIC_SATURATION) {
                    // Achromatic — classify by brightness
                    if (bri < ACHROMATIC_BLACK_BRIGHTNESS) {
                        buckets[12]++; // black
                    } else if (bri > ACHROMATIC_WHITE_BRIGHTNESS) {
                        buckets[13]++; // white
                    } else {
                        buckets[14]++; // gray
                    }
                } else {
                    // Chromatic — classify by hue bucket
                    float hue = hsb[0]; // 0.0–1.0
                    int bucket = (int)(hue * 12);
                    if (bucket < 0) bucket = 0;
                    if (bucket > 11) bucket = 11;
                    buckets[bucket]++;
                }
                totalSampled++;
            }
        }

        if (totalSampled == 0) return DetectionResult.noTexture();

        // Find the two largest buckets
        int firstIdx = 0, secondIdx = -1;
        for (int i = 1; i < buckets.length; i++) {
            if (buckets[i] > buckets[firstIdx]) {
                secondIdx = firstIdx;
                firstIdx  = i;
            } else if (secondIdx < 0 || buckets[i] > buckets[secondIdx]) {
                secondIdx = i;
            }
        }

        double dominantPct = (double) buckets[firstIdx] / totalSampled;
        String dominantFamily = bucketToFamily(firstIdx);

        if (dominantPct < DOMINANT_THRESHOLD) {
            // Two families nearly tied
            double secondPct = secondIdx >= 0 ? (double) buckets[secondIdx] / totalSampled : 0.0;
            String secondFamily = secondIdx >= 0 ? bucketToFamily(secondIdx) : null;
            return DetectionResult.ambiguous(dominantFamily, dominantPct, secondFamily, secondPct);
        }

        return DetectionResult.confident(dominantFamily, dominantPct);
    }

    private static String bucketToFamily(int idx) {
        if (idx == 12) return "black";
        if (idx == 13) return "white";
        if (idx == 14) return "gray";
        return HUE_BUCKET_FAMILIES[idx];
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public enum Status { CONFIDENT, AMBIGUOUS, NO_TEXTURE, ERROR }

    public static final class DetectionResult {
        public final Status  status;
        public final String  primary;      // dominant family, or null
        public final double  primaryPct;
        public final String  secondary;    // second-strongest family, or null
        public final double  secondaryPct;
        public final String  errorMsg;

        private DetectionResult(Status status, String primary, double primaryPct,
                                String secondary, double secondaryPct, String errorMsg) {
            this.status       = status;
            this.primary      = primary;
            this.primaryPct   = primaryPct;
            this.secondary    = secondary;
            this.secondaryPct = secondaryPct;
            this.errorMsg     = errorMsg;
        }

        static DetectionResult confident(String family, double pct) {
            return new DetectionResult(Status.CONFIDENT, family, pct, null, 0.0, null);
        }
        static DetectionResult ambiguous(String p, double pPct, String s, double sPct) {
            return new DetectionResult(Status.AMBIGUOUS, p, pPct, s, sPct, null);
        }
        static DetectionResult noTexture() {
            return new DetectionResult(Status.NO_TEXTURE, null, 0, null, 0, null);
        }
        static DetectionResult error(String msg) {
            return new DetectionResult(Status.ERROR, null, 0, null, 0, msg);
        }

        /** True when the dominant color was found with high confidence (≥ 40%). */
        public boolean confident() { return status == Status.CONFIDENT; }

        /**
         * Build the player-facing message described in item 1.1:
         *   CONFIDENT → null (no message needed — the caller proceeds silently)
         *   AMBIGUOUS → "§eDetected: red (67%) or brown (33%). §7Using red..."
         *   NO_TEXTURE → "§cThis block has no texture..."
         *   ERROR → "§cThis block has no texture..."
         */
        public String playerMessage(String blockId) {
            return switch (status) {
                case CONFIDENT -> null;
                case AMBIGUOUS -> String.format(
                    "§eDetected: %s (%d%%) or %s (%d%%). §7Using %s. Use /cb recolor %s to override.",
                    primary, (int)(primaryPct * 100),
                    secondary != null ? secondary : "?", (int)(secondaryPct * 100),
                    primary, secondary != null ? secondary : "?");
                case NO_TEXTURE -> "§cThis block has no texture. §7Add one with /cb retexture " + blockId + " <url>";
                case ERROR      -> "§cThis block has no texture. §7Add one with /cb retexture " + blockId + " <url>";
            };
        }
    }

    private ColorDetection() {}
}
