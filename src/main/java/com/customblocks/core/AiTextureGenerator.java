package com.customblocks.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Phase 11.1 — AI Block Texture Generator.
 * <p>
 * Two modes:
 * <ul>
 *   <li><b>Procedural</b> (default, no API key): generates pixel-art textures
 *       from keyword detection in the player's description.</li>
 *   <li><b>API</b> (when {@code aiApiKey} is set): delegates to the configured
 *       provider (Stability AI / OpenAI image API). Returns 3 PNG byte arrays.</li>
 * </ul>
 */
public final class AiTextureGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/AI");
    private static final int SIZE = 128;
    private static final Random RNG = new Random();

    private AiTextureGenerator() {}

    /**
     * Generate up to 3 texture variations for the given description.
     * Returns a list of 1–3 PNG byte arrays.
     *
     * @param description player's text description
     * @param apiKey      the ai_api_key from config, or empty/null for procedural
     * @return list of PNG byte arrays (never null, may be empty on total failure)
     */
    public static java.util.List<byte[]> generate(String description, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            return generateViaApi(description, apiKey);
        }
        return generateProcedural(description);
    }

    /** Returns true if the API key is configured. */
    public static boolean hasApiKey() {
        String key = com.customblocks.CustomBlocksConfig.aiApiKey;
        return key != null && !key.isBlank();
    }

    // ── Procedural Generation ────────────────────────────────────────────────

    /**
     * Generates 3 procedural variations based on keyword matching.
     * Falls back to generic noise if no keyword matches.
     */
    public static java.util.List<byte[]> generateProcedural(String description) {
        Style style = detectStyle(description.toLowerCase(Locale.ROOT));
        java.util.List<byte[]> results = new ArrayList<>();
        for (int v = 0; v < 3; v++) {
            try {
                BufferedImage img = renderStyle(style, v);
                results.add(toPng(img));
            } catch (Exception e) {
                LOGGER.warn("[AI] Procedural render failed (variation {}): {}", v, e.getMessage());
            }
        }
        return results;
    }

    /** Keyword → Style mapping. */
    private static Style detectStyle(String desc) {
        if (contains(desc, "stone", "rock", "cobble", "granite", "diorite", "andesite", "concrete", "temple"))
            return Style.STONE;
        if (contains(desc, "lava", "magma", "fire", "inferno", "molten", "volcanic"))
            return Style.LAVA;
        if (contains(desc, "grass", "moss", "plant", "leaf", "leaves", "jungle", "nature"))
            return Style.GRASS;
        if (contains(desc, "wood", "log", "plank", "oak", "birch", "spruce", "bamboo", "bark"))
            return Style.WOOD;
        if (contains(desc, "sand", "sandstone", "desert", "dune", "terracotta"))
            return Style.SAND;
        if (contains(desc, "ice", "snow", "frost", "frozen", "tundra", "crystal", "glass"))
            return Style.ICE;
        if (contains(desc, "gold", "yellow", "amber", "honey", "gilded"))
            return Style.GOLD;
        if (contains(desc, "obsidian", "dark", "shadow", "void", "black", "nether", "soul"))
            return Style.DARK;
        if (contains(desc, "diamond", "blue", "azure", "ocean", "water", "aqua"))
            return Style.BLUE;
        if (contains(desc, "emerald", "green", "lime", "verdant"))
            return Style.GREEN;
        if (contains(desc, "brick", "clay", "terracotta", "red", "crimson"))
            return Style.BRICK;
        if (contains(desc, "iron", "steel", "metal", "ore", "netherite", "silver", "grey", "gray"))
            return Style.METAL;
        if (contains(desc, "ancient", "ruin", "worn", "aged", "cracked"))
            return Style.ANCIENT;
        if (contains(desc, "glowing", "glow", "luminous", "lantern", "light", "beacon"))
            return Style.GLOWING;
        return Style.GENERIC;
    }

    private static boolean contains(String text, String... keywords) {
        for (String k : keywords) if (text.contains(k)) return true;
        return false;
    }

    private enum Style {
        STONE, LAVA, GRASS, WOOD, SAND, ICE, GOLD, DARK, BLUE, GREEN, BRICK, METAL, ANCIENT, GLOWING, GENERIC
    }

    private static BufferedImage renderStyle(Style style, int variation) {
        return switch (style) {
            case STONE   -> noiseTexture(0x808080, 0x606060, 0xA0A0A0, variation);
            case LAVA    -> gradientNoise(0xFF4400, 0xFF8800, 0xFFCC00, variation);
            case GRASS   -> noiseTexture(0x3A7D2B, 0x2E6622, 0x4E9E38, variation);
            case WOOD    -> stripedTexture(0x8B5E3C, 0x6B4423, 0xA0703A, variation);
            case SAND    -> noiseTexture(0xE4C97A, 0xD4B55A, 0xF0DD9A, variation);
            case ICE     -> noiseTexture(0xAFD8E8, 0x90C0DC, 0xCCEEFF, variation);
            case GOLD    -> noiseTexture(0xFFD700, 0xCCAA00, 0xFFEA4A, variation);
            case DARK    -> noiseTexture(0x1A1A2E, 0x0D0D1A, 0x2A2A40, variation);
            case BLUE    -> noiseTexture(0x2266CC, 0x1144AA, 0x3388EE, variation);
            case GREEN   -> noiseTexture(0x22AA44, 0x118833, 0x33BB55, variation);
            case BRICK   -> brickTexture(0xAA4422, 0xCC5533, 0x886644, variation);
            case METAL   -> metalTexture(0x909090, 0x707070, 0xB0B0B0, variation);
            case ANCIENT -> ancientTexture(variation);
            case GLOWING -> glowingTexture(variation);
            case GENERIC -> noiseTexture(0x888888, 0x666666, 0xAAAAAA, variation);
        };
    }

    /** Simple noise texture using two base colours. variation shifts hue slightly. */
    private static BufferedImage noiseTexture(int baseRgb, int darkRgb, int lightRgb, int variation) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int shift = variation * 8;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int noise = pseudoNoise(x * 3 + shift, y * 3 + shift, variation * 113);
                int rgb;
                if (noise < 80)        rgb = darkRgb;
                else if (noise < 180)  rgb = baseRgb;
                else                   rgb = lightRgb;
                img.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        return img;
    }

    /** Gradient noise (lava-style). */
    private static BufferedImage gradientNoise(int c1, int c2, int c3, int variation) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int shift = variation * 17;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int n = pseudoNoise(x * 2 + shift, y * 2 + shift, variation * 71);
                int rgb;
                if (n < 100)       rgb = lerpColor(c1, c2, n / 100.0);
                else if (n < 200)  rgb = lerpColor(c2, c3, (n - 100) / 100.0);
                else               rgb = c3;
                img.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        return img;
    }

    /** Striped texture (wood grain). */
    private static BufferedImage stripedTexture(int base, int dark, int light, int variation) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int shift = variation * 7;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int grain = (x + shift) % 8 + pseudoNoise(x, y, variation * 31) / 32;
                int rgb = grain < 2 ? dark : grain < 6 ? base : light;
                img.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        return img;
    }

    /** Brick pattern. */
    private static BufferedImage brickTexture(int brickRgb, int lightRgb, int mortarRgb, int variation) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int brickH = 8, brickW = 16, mortarT = 1;
        int shift = variation * 4;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int row = y / brickH;
                int offsetX = (row % 2) * (brickW / 2 + shift % 4);
                int bx = (x + offsetX) % brickW;
                boolean isMortar = bx < mortarT || y % brickH < mortarT;
                int noise = pseudoNoise(x + variation * 5, y, variation * 37) / 64;
                img.setRGB(x, y, 0xFF000000 | (isMortar ? mortarRgb : (noise == 0 ? lightRgb : brickRgb)));
            }
        }
        return img;
    }

    /** Metal texture with horizontal scan lines. */
    private static BufferedImage metalTexture(int base, int dark, int light, int variation) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int shift = variation * 11;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int n = pseudoNoise(x + shift, y, variation * 53);
                int scanLine = ((y + shift) % 4 == 0) ? -30 : 0;
                int r = clamp(((base >> 16) & 0xFF) + n / 16 + scanLine);
                int g = clamp(((base >> 8) & 0xFF) + n / 16 + scanLine);
                int b = clamp((base & 0xFF) + n / 16 + scanLine);
                img.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    /** Ancient/ruined stone texture with cracks. */
    private static BufferedImage ancientTexture(int variation) {
        BufferedImage img = noiseTexture(0x6B6050, 0x504538, 0x857864, variation);
        // Add cracks
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x302820));
        int numCracks = 3 + variation;
        RNG.setSeed(variation * 999L);
        for (int c = 0; c < numCracks; c++) {
            int x1 = RNG.nextInt(SIZE), y1 = RNG.nextInt(SIZE);
            int x2 = x1 + RNG.nextInt(30) - 15, y2 = y1 + RNG.nextInt(30) - 15;
            g.drawLine(x1, y1, x2, y2);
        }
        g.dispose();
        return img;
    }

    /** Glowing texture with inner light effect. */
    private static BufferedImage glowingTexture(int variation) {
        int[] bases = {0xFFAA00, 0x00CCFF, 0xAA00FF};
        int base = bases[variation % bases.length];
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int cx = SIZE / 2, cy = SIZE / 2;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                double intensity = Math.max(0, 1.0 - dist / (SIZE * 0.6));
                int n = pseudoNoise(x + variation * 13, y + variation * 7, variation * 61);
                int r = clamp((int)(((base >> 16) & 0xFF) * (0.3 + 0.7 * intensity) + n / 10.0));
                int g2 = clamp((int)(((base >> 8) & 0xFF) * (0.3 + 0.7 * intensity) + n / 10.0));
                int b = clamp((int)((base & 0xFF) * (0.3 + 0.7 * intensity) + n / 10.0));
                img.setRGB(x, y, 0xFF000000 | (r << 16) | (g2 << 8) | b);
            }
        }
        return img;
    }

    // ── Math helpers ──────────────────────────────────────────────────────────

    /** Deterministic pseudo-noise [0, 255]. */
    private static int pseudoNoise(int x, int y, int seed) {
        int n = x * 1619 + y * 31337 + seed * 1013904223;
        n = (n >> 13) ^ n;
        n = n * (n * n * 15731 + 789221) + 1376312589;
        return (n >>> 24) & 0xFF;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static int lerpColor(int c1, int c2, double t) {
        int r = clamp((int)(((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t));
        int g = clamp((int)(((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t));
        int b = clamp((int)((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t));
        return (r << 16) | (g << 8) | b;
    }

    private static byte[] toPng(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    // ── API Generation (stub — delegates to HTTP) ─────────────────────────────

    /**
     * Attempts to call the configured AI API provider. Falls back to procedural
     * on any failure and logs the error clearly.
     */
    private static java.util.List<byte[]> generateViaApi(String description, String apiKey) {
        String provider = com.customblocks.CustomBlocksConfig.aiApiProvider;
        try {
            if ("openai".equalsIgnoreCase(provider)) {
                return callOpenAiImageApi(description, apiKey);
            }
            // Provider is configured but not yet implemented — warn so the server owner notices
            LOGGER.warn("[AI] Provider '{}' is not yet integrated (only 'openai' is supported). Using procedural fallback. Set aiApiProvider=openai in config.json to use the API.", provider);
        } catch (Exception e) {
            LOGGER.warn("[AI] API call failed ({}): {} — falling back to procedural.", provider, e.getMessage());
        }
        return generateProcedural(description);
    }

    /**
     * Calls OpenAI DALL·E to generate up to 3 texture variations.
     * Returns PNG byte arrays or throws on failure.
     */
    private static java.util.List<byte[]> callOpenAiImageApi(String description, String apiKey) throws Exception {
        String prompt = "Minecraft pixel art block texture, 128x128, seamless tile, style: " + description;
        int n = Math.max(1, Math.min(3, com.customblocks.CustomBlocksConfig.aiMaxVariations));

        String requestBody = String.format(
            "{\"model\":\"dall-e-3\",\"prompt\":\"%s\",\"n\":%d,\"size\":\"1024x1024\",\"response_format\":\"b64_json\"}",
            prompt.replace("\"", "\\\""), Math.min(n, 1) /* dall-e-3 only supports n=1 */);

        java.net.URL url = java.net.URI.create("https://api.openai.com/v1/images/generations").toURL();
        java.net.HttpURLConnection con = (java.net.HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Authorization", "Bearer " + apiKey);
        con.setRequestProperty("Content-Type", "application/json");
        con.setConnectTimeout(15_000);
        con.setReadTimeout(60_000);
        con.setDoOutput(true);
        con.getOutputStream().write(requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        int code = con.getResponseCode();
        if (code == 401) throw new Exception("AI API key rejected (HTTP 401). Check your key in Config → Integrations.");
        if (code != 200) throw new Exception("AI API returned HTTP " + code);

        String body = new String(con.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        com.google.gson.JsonArray dataArr = com.google.gson.JsonParser.parseString(body)
            .getAsJsonObject().getAsJsonArray("data");

        java.util.List<byte[]> results = new ArrayList<>();
        for (com.google.gson.JsonElement el : dataArr) {
            String b64 = el.getAsJsonObject().get("b64_json").getAsString();
            byte[] raw = java.util.Base64.getDecoder().decode(b64);
            // Resize to 128x128 pixel-art style
            java.awt.image.BufferedImage orig = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(raw));
            java.awt.image.BufferedImage resized = new java.awt.image.BufferedImage(SIZE, SIZE, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(orig, 0, 0, SIZE, SIZE, null);
            g.dispose();
            results.add(toPng(resized));
        }
        // Fill remaining variations with procedural fallback so we always return 3
        while (results.size() < 3) {
            results.addAll(generateProcedural(description));
            if (results.size() >= 3) break;
        }
        return results.subList(0, Math.min(3, results.size()));
    }
}
