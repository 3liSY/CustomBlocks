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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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

                // ── Default (all-faces) texture — skip write if file already same size ──
                File texDest = new File(assets, "textures/block/" + slotKey + ".png");
                if (data != null && data.texture != null && data.texture.length > 0) {
                    if (!texDest.exists() || texDest.length() != data.texture.length)
                        writePng(data.texture, texDest);
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

    /**
     * Generates a 16×16 RGBA PNG with a 9×9 coloured square centred on a transparent background.
     */
    private static byte[] makeSquarePng(int r, int g, int b) {
        return makeShapePng(r, g, b, (row, col) -> row >= 3 && row < 12 && col >= 3 && col < 12);
    }

    /**
     * Generates a 16×16 RGBA PNG with an upward-pointing triangle centred on a transparent background.
     * Triangle vertices: top-centre (7,2), bottom-left (2,13), bottom-right (12,13).
     */
    private static byte[] makeTrianglePng(int r, int g, int b) {
        return makeShapePng(r, g, b, (row, col) -> {
            if (row < 2 || row > 13) return false;
            // For each row, compute the x extent of the triangle
            float progress = (float)(row - 2) / (13 - 2); // 0 at top, 1 at bottom
            float halfW = progress * (12 - 2) / 2.0f;       // 0 at top, 5 at bottom
            float cx = 7.0f;
            return col >= (cx - halfW) && col <= (cx + halfW);
        });
    }

    @FunctionalInterface interface PixelTest { boolean inside(int row, int col); }

    private static byte[] makeShapePng(int r, int g, int b, PixelTest test) {
        try {
            int w = 16, h = 16;
            // RGBA = 4 bytes per pixel
            byte[] raw = new byte[h * (1 + w * 4)];
            for (int row = 0; row < h; row++) {
                int base = row * (1 + w * 4);
                raw[base] = 0; // filter = None
                for (int col = 0; col < w; col++) {
                    if (test.inside(row, col)) {
                        raw[base + 1 + col * 4]     = (byte) r;
                        raw[base + 1 + col * 4 + 1] = (byte) g;
                        raw[base + 1 + col * 4 + 2] = (byte) b;
                        raw[base + 1 + col * 4 + 3] = (byte) 255; // opaque
                    } else {
                        raw[base + 1 + col * 4]     = 0;
                        raw[base + 1 + col * 4 + 1] = 0;
                        raw[base + 1 + col * 4 + 2] = 0;
                        raw[base + 1 + col * 4 + 3] = 0; // transparent
                    }
                }
            }
            java.util.zip.Deflater def = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
            def.setInput(raw);
            def.finish();
            byte[] comp = new byte[raw.length + 128];
            int compLen = def.deflate(comp);
            def.end();
            byte[] idat = java.util.Arrays.copyOf(comp, compLen);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A});
            // IHDR: 16x16, 8-bit RGBA (color type 6)
            writeChunk(out, "IHDR", new byte[]{
                0,0,0,16, 0,0,0,16, 8, 6, 0, 0, 0});
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
