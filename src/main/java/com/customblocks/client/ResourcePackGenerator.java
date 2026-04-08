package com.customblocks.client;

import com.customblocks.CustomBlocksMod;
import com.customblocks.SlotManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;

import java.awt.Color;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Map;

@Environment(EnvType.CLIENT)
public class ResourcePackGenerator {

    private static final Gson   GSON        = new GsonBuilder().setPrettyPrinting().create();
    private static final int    PACK_FORMAT = 34;
    private static final String MOD_ID      = CustomBlocksMod.MOD_ID;

    // Mapping from our face key names → Minecraft model face key names
    // Note: Minecraft uses "up" / "down"; we expose "top" / "bottom" to users.
    private static final Map<String, String> FACE_TO_MC = Map.of(
            "top",    "up",
            "bottom", "down",
            "north",  "north",
            "south",  "south",
            "east",   "east",
            "west",   "west"
    );

    public static void generate(MinecraftClient client) {
        try {
            File mcDir    = client.runDirectory;
            File packRoot = new File(mcDir, "resourcepacks/customblocks_generated");
            File assets   = new File(packRoot, "assets/" + MOD_ID);

            new File(assets, "blockstates").mkdirs();
            new File(assets, "models/block").mkdirs();
            new File(assets, "models/item").mkdirs();
            new File(assets, "textures/block").mkdirs();
            new File(assets, "textures/item").mkdirs();

            // pack.mcmeta
            JsonObject pack = new JsonObject();
            pack.addProperty("pack_format", PACK_FORMAT);
            pack.addProperty("description", "CustomBlocks Generated");
            JsonObject meta = new JsonObject();
            meta.add("pack", pack);
            writeJson(meta, new File(packRoot, "pack.mcmeta"));

            for (int i = 0; i < SlotManager.MAX_SLOTS; i++) {
                String slotKey  = "slot_" + i;
                String modelRef = MOD_ID + ":block/" + slotKey;
                SlotManager.SlotData data = SlotManager.getBySlot(slotKey);

                // ── Default (all-faces) texture ────────────────────────────────
                File texDest = new File(assets, "textures/block/" + slotKey + ".png");
                if (data != null && data.texture != null && data.texture.length > 0) {
                    if (!texDest.exists() || texDest.length() != data.texture.length)
                        writePng(data.texture, texDest);
                    // Write animation mcmeta for animated (GIF) textures
                    if (data.isAnimated()) {
                        File mcmeta = new File(assets, "textures/block/" + slotKey + ".png.mcmeta");
                        try (java.io.FileWriter fw = new java.io.FileWriter(mcmeta, java.nio.charset.StandardCharsets.UTF_8)) {
                            fw.write(data.animMeta);
                        }
                    }
                } else {
                    if (!texDest.exists())
                        Files.write(texDest.toPath(), PLACEHOLDER_PNG);
                }

                // ── Per-face textures ─────────────────────────────────────────
                if (data != null && data.hasFaces()) {
                    for (Map.Entry<String, byte[]> face : data.faceTextures.entrySet()) {
                        File faceDest = new File(assets,
                                "textures/block/" + slotKey + "_" + face.getKey() + ".png");
                        if (!faceDest.exists() || faceDest.length() != face.getValue().length)
                            writePng(face.getValue(), faceDest);
                    }
                }

                // ── Blockstate ────────────────────────────────────────────────
                JsonObject variant  = new JsonObject(); variant.addProperty("model", modelRef);
                JsonObject variants = new JsonObject(); variants.add("", variant);
                JsonObject bs       = new JsonObject(); bs.add("variants", variants);
                writeJson(bs, new File(assets, "blockstates/" + slotKey + ".json"));

                // ── Block model ───────────────────────────────────────────────
                JsonObject bm = new JsonObject();
                if (data != null && data.hasFaces()) {
                    // cube — explicit texture ref per face; missing faces fall back to default
                    bm.addProperty("parent", "minecraft:block/cube");
                    JsonObject tex = new JsonObject();
                    // particle texture = default
                    tex.addProperty("particle", MOD_ID + ":block/" + slotKey);
                    for (String face : SlotManager.FACE_KEYS) {
                        String mcFace = FACE_TO_MC.get(face);
                        if (data.faceTextures.containsKey(face)) {
                            // This face has an override
                            tex.addProperty(mcFace, MOD_ID + ":block/" + slotKey + "_" + face);
                        } else {
                            // No override — use the default all-faces texture
                            tex.addProperty(mcFace, MOD_ID + ":block/" + slotKey);
                        }
                    }
                    bm.add("textures", tex);
                } else {
                    // cube_all — simple single texture, same as before
                    bm.addProperty("parent", "minecraft:block/cube_all");
                    JsonObject tex = new JsonObject();
                    tex.addProperty("all", MOD_ID + ":block/" + slotKey);
                    bm.add("textures", tex);
                }
                writeJson(bm, new File(assets, "models/block/" + slotKey + ".json"));

                // ── Item model (always shows the default face — top face if set, else all) ──
                JsonObject im = new JsonObject();
                im.addProperty("parent", modelRef);
                writeJson(im, new File(assets, "models/item/" + slotKey + ".json"));
            }

            // Tab icon
            byte[] tabIcon = SlotManager.getTabIconTexture();
            File tabDest = new File(assets, "textures/item/tab_icon.png");
            if (tabIcon != null && tabIcon.length > 0) writePng(tabIcon, tabDest);
            else Files.write(tabDest.toPath(), PLACEHOLDER_PNG);

            // ── Color Square items — flat 16x16 coloured squares ─────────────────────
            String[][] squares = {{"black_square",  "10,10,10"},
                                   {"yellow_square", "240,200,20"},
                                   {"green_square",  "30,140,30"}};
            for (String[] sq : squares) {
                String itemId  = sq[0];
                String[] rgb   = sq[1].split(",");
                // 9x9 center square on 16x16 RGBA canvas
                byte[] pngData = makeSquarePng(
                        Integer.parseInt(rgb[0].trim()),
                        Integer.parseInt(rgb[1].trim()),
                        Integer.parseInt(rgb[2].trim()));
                File sqTex = new File(assets, "textures/item/" + itemId + ".png");
                Files.write(sqTex.toPath(), pngData);
                JsonObject sqTex2 = new JsonObject();
                sqTex2.addProperty("layer0", MOD_ID + ":item/" + itemId);
                JsonObject sqModel = new JsonObject();
                sqModel.addProperty("parent", "minecraft:item/generated");
                sqModel.add("textures", sqTex2);
                writeJson(sqModel, new File(assets, "models/item/" + itemId + ".json"));
            }

            // Color Triangle items
            String[][] triangles = {{"black_triangle",  "10,10,10"},
                                     {"yellow_triangle", "240,200,20"},
                                     {"green_triangle",  "30,140,30"}};
            for (String[] tr : triangles) {
                String itemId  = tr[0];
                String[] rgb   = tr[1].split(",");
                byte[] pngData = makeTrianglePng(
                        Integer.parseInt(rgb[0].trim()),
                        Integer.parseInt(rgb[1].trim()),
                        Integer.parseInt(rgb[2].trim()));
                File trTex = new File(assets, "textures/item/" + itemId + ".png");
                Files.write(trTex.toPath(), pngData);
                JsonObject trTex2 = new JsonObject();
                trTex2.addProperty("layer0", MOD_ID + ":item/" + itemId);
                JsonObject trModel = new JsonObject();
                trModel.addProperty("parent", "minecraft:item/generated");
                trModel.add("textures", trTex2);
                writeJson(trModel, new File(assets, "models/item/" + itemId + ".json"));
            }

            // ── Rainbow Rectangle item texture ─────────────────────────────────
            File rectTex = new File(assets, "textures/item/rainbow_rectangle.png");
            Files.write(rectTex.toPath(), makeRainbowRectanglePng());
            JsonObject rectTexObj = new JsonObject();
            rectTexObj.addProperty("layer0", MOD_ID + ":item/rainbow_rectangle");
            JsonObject rectModel = new JsonObject();
            rectModel.addProperty("parent", "minecraft:item/generated");
            rectModel.add("textures", rectTexObj);
            writeJson(rectModel, new File(assets, "models/item/rainbow_rectangle.json"));

            CustomBlocksMod.LOGGER.info("[CustomBlocks] Resource pack generated.");
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to generate resource pack", e);
        }
    }

    /** Decodes image bytes (PNG, JPEG, etc) and writes as valid PNG. */
    private static void writePng(byte[] imageBytes, File dest) {
        try (NativeImage img = NativeImage.read(new ByteArrayInputStream(imageBytes))) {
            dest.getParentFile().mkdirs();
            img.writeTo(dest.toPath());
        } catch (Exception e) {
            try { Files.write(dest.toPath(), imageBytes); }
            catch (Exception ignored) {}
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not decode image for {}, wrote raw bytes", dest.getName());
        }
    }

    private static void writeJson(JsonObject json, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        try (FileWriter fw = new FileWriter(dest, StandardCharsets.UTF_8)) {
            GSON.toJson(json, fw);
        }
    }

    // ── Item texture generators ───────────────────────────────────────────────

    /**
     * Glossy colour-swatch square (16×16 RGBA):
     *  - 1-px transparent margin on all sides
     *  - 1-px very-dark border ring
     *  - main colour fill
     *  - top highlight strip (colour blended ~55% toward white)
     *  - bottom-right shadow strip (colour × 0.65)
     *  - 2-px bright shine dot at top-left inner corner
     */
    /**
     * Rainbow Rectangle item texture — 16x8 glowing rainbow bar with rounded ends,
     * animated-style gradient from left (red) to right (violet) with a white shine strip.
     */
    private static byte[] makeRainbowRectanglePng() {
        int[][] px = new int[16][16];
        // Draw a rounded rectangle from row 4 to row 11 (8px tall, 16px wide)
        float[] hues = {0f, 0.083f, 0.167f, 0.25f, 0.333f, 0.417f, 0.5f, 0.583f, 0.667f,
                        0.75f, 0.833f, 0.917f, 1f, 1f, 1f, 1f};
        for (int row = 4; row <= 11; row++) {
            for (int col = 0; col < 16; col++) {
                // Rounded corners — skip outermost 2 cells on top/bottom rows at edges
                if ((row == 4 || row == 11) && (col == 0 || col == 15)) continue;
                float hue = hues[col];
                float sat = 1f, bri = row == 5 ? 1f : 0.85f; // shine on second row
                int rgb = hsbToArgb(hue, sat, bri);
                // Add white shine on top inner row
                if (row == 5) {
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    r = lerp(r, 255, 0.5f); g = lerp(g, 255, 0.5f); b = lerp(b, 255, 0.5f);
                    rgb = argb(255, r, g, b);
                }
                px[row][col] = rgb;
            }
        }
        // Dark outline
        for (int col = 1; col < 15; col++) {
            if (px[4][col] != 0) px[4][col] = darken(px[4][col]);
            if (px[11][col] != 0) px[11][col] = darken(px[11][col]);
        }
        for (int row = 5; row <= 10; row++) {
            if (px[row][0] != 0) px[row][0] = darken(px[row][0]);
            if (px[row][15] != 0) px[row][15] = darken(px[row][15]);
        }
        return pixelsToPng(px);
    }

    private static int hsbToArgb(float h, float s, float b) {
        int rgb = Color.HSBtoRGB(h, s, b);
        return argb(255, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static int darken(int argbColor) {
        int r = ((argbColor >> 16) & 0xFF) / 4;
        int g = ((argbColor >> 8)  & 0xFF) / 4;
        int b = ( argbColor        & 0xFF) / 4;
        return argb(255, r, g, b);
    }

    private static byte[] makeSquarePng(int r, int g, int b) {
        int[][] px = new int[16][16]; // ARGB (0 = fully transparent)

        int dark  = argb(255, r/4,                 g/4,                 b/4);
        int main  = argb(255, r,                   g,                   b);
        int light = argb(255, lerp(r, 255, 0.55f), lerp(g, 255, 0.55f), lerp(b, 255, 0.55f));
        int shade = argb(255, (int)(r * 0.65f),    (int)(g * 0.65f),    (int)(b * 0.65f));
        int shine = argb(255, 255,                  255,                 255);

        // Main fill (rows 2-13, cols 2-13)
        for (int row = 2; row <= 13; row++)
            for (int col = 2; col <= 13; col++)
                px[row][col] = main;

        // Dark border ring (row/col 1 and 14)
        for (int i = 1; i <= 14; i++) {
            px[1][i] = dark;  px[14][i] = dark;
            px[i][1] = dark;  px[i][14] = dark;
        }

        // Highlight strip — top 3 rows of fill
        for (int row = 2; row <= 4; row++)
            for (int col = 2; col <= 12; col++)
                px[row][col] = light;

        // Shadow — bottom 2 rows + right 2 cols of fill
        for (int row = 12; row <= 13; row++)
            for (int col = 4;  col <= 13; col++) px[row][col] = shade;
        for (int row = 4;  row <= 13; row++)
            for (int col = 12; col <= 13; col++) px[row][col] = shade;

        // Shine dots at top-left inner corner
        px[2][2] = shine; px[2][3] = shine; px[3][2] = shine;

        return pixelsToPng(px);
    }

    /**
     * Bold outlined upward-pointing triangle (16×16 RGBA):
     *  - Apex:  row 1, centred on cols 7-8
     *  - Base:  row 14, cols 1-14
     *  - 1-px very-dark outline on all edges
     *  - main colour fill
     *  - lighter highlight on top quarter
     *  - shine dot just below the apex
     */
    private static byte[] makeTrianglePng(int r, int g, int b) {
        int[][] px = new int[16][16];

        int dark  = argb(255, r/4,                 g/4,                 b/4);
        int main  = argb(255, r,                   g,                   b);
        int light = argb(255, lerp(r, 255, 0.55f), lerp(g, 255, 0.55f), lerp(b, 255, 0.55f));
        int shine = argb(255, 255,                  255,                 255);

        float apexCx  = 7.5f;
        int   apexRow = 1, baseRow = 14;
        float baseHW  = 6.5f;

        // 1 — filled triangle
        for (int row = apexRow; row <= baseRow; row++) {
            float t    = (float)(row - apexRow) / (baseRow - apexRow);
            float hw   = t * baseHW;
            int   left = Math.round(apexCx - hw);
            int   right= Math.round(apexCx + hw);
            for (int col = left; col <= right; col++)
                if (col >= 0 && col < 16) px[row][col] = main;
        }

        // 2 — dark outline (left edge, right edge, base row)
        for (int row = apexRow; row <= baseRow; row++) {
            float t    = (float)(row - apexRow) / (baseRow - apexRow);
            float hw   = t * baseHW;
            int   left = Math.round(apexCx - hw);
            int   right= Math.round(apexCx + hw);
            if (left  >= 0 && left  < 16) px[row][left]  = dark;
            if (right >= 0 && right < 16) px[row][right] = dark;
        }
        // Top apex pixels
        px[apexRow][7] = dark; px[apexRow][8] = dark;
        // Base row
        for (int col = 1; col <= 14; col++) if (px[baseRow][col] != 0) px[baseRow][col] = dark;

        // 3 — highlight on top quarter (inside outline only)
        for (int row = apexRow + 1; row <= apexRow + 4; row++) {
            float t    = (float)(row - apexRow) / (baseRow - apexRow);
            float hw   = t * baseHW;
            int   left = Math.round(apexCx - hw) + 1;  // stay inside outline
            int   right= Math.round(apexCx + hw) - 1;
            for (int col = left; col <= right; col++)
                if (col >= 0 && col < 16 && px[row][col] == main)
                    px[row][col] = light;
        }

        // 4 — shine dot just below apex
        if (px[3][7] == light) px[3][7] = shine;
        if (px[3][8] == light) px[3][8] = shine;

        return pixelsToPng(px);
    }

    // ── PNG encoding helpers ──────────────────────────────────────────────────

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int v, int target, float f) {
        return Math.max(0, Math.min(255, v + (int)((target - v) * f)));
    }

    /** Encode a 16×16 ARGB int[][] to a valid RGBA PNG byte array. */
    private static byte[] pixelsToPng(int[][] argbPixels) {
        try {
            int w = 16, h = 16;
            byte[] raw = new byte[h * (1 + w * 4)];
            for (int row = 0; row < h; row++) {
                int base = row * (1 + w * 4);
                raw[base] = 0; // filter = None
                for (int col = 0; col < w; col++) {
                    int v = argbPixels[row][col];
                    raw[base + 1 + col*4    ] = (byte)((v >> 16) & 0xFF); // R
                    raw[base + 1 + col*4 + 1] = (byte)((v >>  8) & 0xFF); // G
                    raw[base + 1 + col*4 + 2] = (byte)( v        & 0xFF); // B
                    raw[base + 1 + col*4 + 3] = (byte)((v >> 24) & 0xFF); // A
                }
            }
            java.util.zip.Deflater def = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
            def.setInput(raw); def.finish();
            byte[] comp = new byte[raw.length + 128];
            int compLen = def.deflate(comp);
            def.end();
            byte[] idat = java.util.Arrays.copyOf(comp, compLen);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A});
            writeChunk(out, "IHDR", new byte[]{0,0,0,16, 0,0,0,16, 8, 6, 0, 0, 0});
            writeChunk(out, "IDAT", idat);
            writeChunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (Exception e) {
            return PLACEHOLDER_PNG;
        }
    }

    private static void writeChunk(java.io.ByteArrayOutputStream out, String type, byte[] data) throws Exception {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        // Length
        out.write((data.length >>> 24) & 0xFF);
        out.write((data.length >>> 16) & 0xFF);
        out.write((data.length >>> 8)  & 0xFF);
        out.write( data.length         & 0xFF);
        // Type
        out.write(typeBytes);
        // Data
        out.write(data);
        // CRC over type + data
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        long c = crc.getValue();
        out.write((int)(c >>> 24) & 0xFF);
        out.write((int)(c >>> 16) & 0xFF);
        out.write((int)(c >>> 8)  & 0xFF);
        out.write((int) c         & 0xFF);
    }

    // 1×1 opaque pink placeholder PNG
    private static final byte[] PLACEHOLDER_PNG = {
        (byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0x00,0x00,0x00,0x0D,0x49,0x48,0x44,0x52,
        0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x01,0x08,0x02,0x00,0x00,0x00,(byte)0x90,0x77,0x53,(byte)0xDE,
        0x00,0x00,0x00,0x0C,0x49,0x44,0x41,0x54,0x08,(byte)0xD7,0x63,(byte)0xF8,(byte)0x0F,(byte)0xF0,
        0x00,0x00,0x00,0x02,0x00,0x01,(byte)0xE2,0x21,(byte)0xBC,0x33,0x00,0x00,0x00,0x00,
        0x49,0x45,0x4E,0x44,(byte)0xAE,0x42,0x60,(byte)0x82
    };
}
