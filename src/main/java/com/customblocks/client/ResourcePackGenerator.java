// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks.client;

import com.google.gson.GsonBuilder;
import java.util.zip.CRC32;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.io.IOException;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import net.minecraft.class_1011;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import com.customblocks.CustomBlocksMod;
import com.google.gson.JsonArray;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import com.customblocks.SlotManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import net.minecraft.class_310;
import java.util.Map;
import com.google.gson.Gson;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class ResourcePackGenerator
{
    private static final Gson GSON;
    private static final int PACK_FORMAT = 34;
    private static final String MOD_ID = "customblocks";
    private static final Map<String, String> FACE_TO_MC;
    private static final byte[] PLACEHOLDER_PNG;
    
    public static void generate(final class_310 client) {
        try {
            final File mcDir = client.field_1697;
            final File packRoot = new File(mcDir, "resourcepacks/customblocks_generated");
            final File assets = new File(packRoot, "assets/customblocks");
            new File(assets, "blockstates").mkdirs();
            new File(assets, "models/block").mkdirs();
            new File(assets, "models/item").mkdirs();
            new File(assets, "textures/block").mkdirs();
            new File(assets, "textures/item").mkdirs();
            final JsonObject pack = new JsonObject();
            pack.addProperty("pack_format", (Number)34);
            pack.addProperty("description", "CustomBlocks Generated");
            final JsonObject meta = new JsonObject();
            meta.add("pack", (JsonElement)pack);
            writeJson(meta, new File(packRoot, "pack.mcmeta"));
            for (int i = 0; i < 512; ++i) {
                final String slotKey = "slot_" + i;
                final String modelRef = "customblocks:block/" + slotKey;
                final SlotManager.SlotData data = SlotManager.getBySlot(slotKey);
                final File texDest = new File(assets, "textures/block/" + slotKey + ".png");
                if (data != null && data.texture != null && data.texture.length > 0) {
                    if (!texDest.exists() || texDest.length() != data.texture.length) {
                        writePng(data.texture, texDest);
                    }
                }
                else if (!texDest.exists()) {
                    Files.write(texDest.toPath(), ResourcePackGenerator.PLACEHOLDER_PNG, new OpenOption[0]);
                }
                if (data != null && data.hasFaces()) {
                    for (Map.Entry<String, byte[]> face : data.faceTextures.entrySet()) {
                        final File faceDest = new File(assets, "textures/block/" + slotKey + "_" + (String)face.getKey() + ".png");
                        if (!faceDest.exists() || faceDest.length() != face.getValue().length) {
                            writePng(face.getValue(), faceDest);
                        }
                    }
                }
                final JsonObject variant = new JsonObject();
                variant.addProperty("model", modelRef);
                final JsonObject variants = new JsonObject();
                variants.add("", (JsonElement)variant);
                final JsonObject bs = new JsonObject();
                bs.add("variants", (JsonElement)variants);
                writeJson(bs, new File(assets, "blockstates/" + slotKey + ".json"));
                final JsonObject bm = new JsonObject();
                if (data != null && data.hasFaces()) {
                    bm.addProperty("parent", "minecraft:block/cube");
                    final JsonObject tex = new JsonObject();
                    tex.addProperty("particle", "customblocks:block/" + slotKey);
                    for (String face2 : SlotManager.FACE_KEYS) {
                        final String mcFace = ResourcePackGenerator.FACE_TO_MC.get(face2);
                        if (data.faceTextures.containsKey(face2)) {
                            tex.addProperty(mcFace, "customblocks:block/" + slotKey + "_" + face2);
                        }
                        else {
                            tex.addProperty(mcFace, "customblocks:block/" + slotKey);
                        }
                    }
                    bm.add("textures", (JsonElement)tex);
                }
                else {
                    bm.addProperty("parent", "minecraft:block/cube_all");
                    final JsonObject tex = new JsonObject();
                    tex.addProperty("all", "customblocks:block/" + slotKey);
                    bm.add("textures", (JsonElement)tex);
                }
                writeJson(bm, new File(assets, "models/block/" + slotKey + ".json"));
                final JsonObject im = new JsonObject();
                im.addProperty("parent", modelRef);
                writeJson(im, new File(assets, "models/item/" + slotKey + ".json"));
            }
            final byte[] tabIcon = SlotManager.getTabIconTexture();
            final File tabDest = new File(assets, "textures/item/tab_icon.png");
            if (tabIcon != null && tabIcon.length > 0) {
                writePng(tabIcon, tabDest);
            }
            else {
                Files.write(tabDest.toPath(), ResourcePackGenerator.PLACEHOLDER_PNG, new OpenOption[0]);
            }
            final String[][] array;
            final String[][] squares = array = new String[][] { { "black_square", "10,10,10" }, { "yellow_square", "240,200,20" }, { "green_square", "30,140,30" } };
            for (int length = array.length, j = 0; j < length; ++j) {
                final String[] sq = array[j];
                final String itemId = sq[0];
                final String[] rgb = sq[1].split(",");
                final byte[] pngData = makeSolidPng(Integer.parseInt(rgb[0].trim()), Integer.parseInt(rgb[1].trim()), Integer.parseInt(rgb[2].trim()));
                final File sqTex = new File(assets, "textures/item/" + itemId + ".png");
                Files.write(sqTex.toPath(), pngData, new OpenOption[0]);
                final JsonObject sqTex2 = new JsonObject();
                sqTex2.addProperty("layer0", "customblocks:item/" + itemId);
                final JsonObject sqModel = new JsonObject();
                sqModel.addProperty("parent", "minecraft:item/generated");
                sqModel.add("textures", (JsonElement)sqTex2);
                final JsonObject display = new JsonObject();
                for (final String view : new String[] { "thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand", "fixed" }) {
                    final JsonObject v = new JsonObject();
                    final JsonArray sc = new JsonArray();
                    sc.add((Number)0.35);
                    sc.add((Number)0.35);
                    sc.add((Number)0.35);
                    final JsonArray tr = new JsonArray();
                    tr.add((Number)0);
                    tr.add((Number)0);
                    tr.add((Number)0);
                    final JsonArray ro = new JsonArray();
                    ro.add((Number)0);
                    ro.add((Number)0);
                    ro.add((Number)0);
                    v.add("scale", (JsonElement)sc);
                    v.add("translation", (JsonElement)tr);
                    v.add("rotation", (JsonElement)ro);
                    display.add(view, (JsonElement)v);
                }
                final JsonObject gui = new JsonObject();
                final JsonArray gs = new JsonArray();
                gs.add((Number)0.4);
                gs.add((Number)0.4);
                gs.add((Number)0.4);
                final JsonArray gt = new JsonArray();
                gt.add((Number)0);
                gt.add((Number)0);
                gt.add((Number)0);
                final JsonArray gr = new JsonArray();
                gr.add((Number)0);
                gr.add((Number)0);
                gr.add((Number)0);
                gui.add("scale", (JsonElement)gs);
                gui.add("translation", (JsonElement)gt);
                gui.add("rotation", (JsonElement)gr);
                display.add("gui", (JsonElement)gui);
                final JsonObject gnd = new JsonObject();
                final JsonArray gns = new JsonArray();
                gns.add((Number)0.3);
                gns.add((Number)0.3);
                gns.add((Number)0.3);
                final JsonArray gnt = new JsonArray();
                gnt.add((Number)0);
                gnt.add((Number)(-2));
                gnt.add((Number)0);
                final JsonArray gnr = new JsonArray();
                gnr.add((Number)0);
                gnr.add((Number)0);
                gnr.add((Number)0);
                gnd.add("scale", (JsonElement)gns);
                gnd.add("translation", (JsonElement)gnt);
                gnd.add("rotation", (JsonElement)gnr);
                display.add("ground", (JsonElement)gnd);
                sqModel.add("display", (JsonElement)display);
                writeJson(sqModel, new File(assets, "models/item/" + itemId + ".json"));
            }
            // Generate triangle items (smaller than squares)
            final String[][] triangles = new String[][] { { "black_triangle", "10,10,10" }, { "yellow_triangle", "240,200,20" }, { "green_triangle", "30,140,30" } };
            for (int k = 0; k < triangles.length; ++k) {
                final String[] tri = triangles[k];
                final String itemId = tri[0];
                final String[] rgb = tri[1].split(",");
                final byte[] pngData = makeTrianglePng(Integer.parseInt(rgb[0].trim()), Integer.parseInt(rgb[1].trim()), Integer.parseInt(rgb[2].trim()));
                final File triTex = new File(assets, "textures/item/" + itemId + ".png");
                Files.write(triTex.toPath(), pngData, new OpenOption[0]);
                final JsonObject triTex2 = new JsonObject();
                triTex2.addProperty("layer0", "customblocks:item/" + itemId);
                final JsonObject triModel = new JsonObject();
                triModel.addProperty("parent", "minecraft:item/generated");
                triModel.add("textures", (JsonElement)triTex2);
                final JsonObject display = new JsonObject();
                for (final String view : new String[] { "thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand", "fixed" }) {
                    final JsonObject v = new JsonObject();
                    final JsonArray sc = new JsonArray();
                    sc.add((Number)0.35);
                    sc.add((Number)0.35);
                    sc.add((Number)0.35);
                    final JsonArray tr = new JsonArray();
                    tr.add((Number)0);
                    tr.add((Number)0);
                    tr.add((Number)0);
                    final JsonArray ro = new JsonArray();
                    ro.add((Number)0);
                    ro.add((Number)0);
                    ro.add((Number)0);
                    v.add("scale", (JsonElement)sc);
                    v.add("translation", (JsonElement)tr);
                    v.add("rotation", (JsonElement)ro);
                    display.add(view, (JsonElement)v);
                }
                final JsonObject gui = new JsonObject();
                final JsonArray gs = new JsonArray();
                gs.add((Number)0.4);
                gs.add((Number)0.4);
                gs.add((Number)0.4);
                final JsonArray gt = new JsonArray();
                gt.add((Number)0);
                gt.add((Number)0);
                gt.add((Number)0);
                final JsonArray gr = new JsonArray();
                gr.add((Number)0);
                gr.add((Number)0);
                gr.add((Number)0);
                gui.add("scale", (JsonElement)gs);
                gui.add("translation", (JsonElement)gt);
                gui.add("rotation", (JsonElement)gr);
                display.add("gui", (JsonElement)gui);
                final JsonObject gnd = new JsonObject();
                final JsonArray gns = new JsonArray();
                gns.add((Number)0.3);
                gns.add((Number)0.3);
                gns.add((Number)0.3);
                final JsonArray gnt = new JsonArray();
                gnt.add((Number)0);
                gnt.add((Number)(-2));
                gnt.add((Number)0);
                final JsonArray gnr = new JsonArray();
                gnr.add((Number)0);
                gnr.add((Number)0);
                gnr.add((Number)0);
                gnd.add("scale", (JsonElement)gns);
                gnd.add("translation", (JsonElement)gnt);
                gnd.add("rotation", (JsonElement)gnr);
                display.add("ground", (JsonElement)gnd);
                triModel.add("display", (JsonElement)display);
                writeJson(triModel, new File(assets, "models/item/" + itemId + ".json"));
            }
            CustomBlocksMod.LOGGER.info("[CustomBlocks] Resource pack generated.");
        }
        catch (final Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to generate resource pack", (Throwable)e);
        }
    }
    
    private static void writePng(final byte[] imageBytes, final File dest) {
        try (final class_1011 img = class_1011.method_4309((InputStream)new ByteArrayInputStream(imageBytes))) {
            dest.getParentFile().mkdirs();
            img.method_4314(dest.toPath());
        }
        catch (final Exception e) {
            try {
                Files.write(dest.toPath(), imageBytes, new OpenOption[0]);
            }
            catch (final Exception ex) {}
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not decode image for {}, wrote raw bytes", (Object)dest.getName());
        }
    }
    
    private static void writeJson(final JsonObject json, final File dest) throws IOException {
        dest.getParentFile().mkdirs();
        try (final FileWriter fw = new FileWriter(dest, StandardCharsets.UTF_8)) {
            ResourcePackGenerator.GSON.toJson((JsonElement)json, (Appendable)fw);
        }
    }
    
    private static byte[] makeSolidPng(final int r, final int g, final int b) {
        try {
            final int w = 16;
            final int h = 16;
            final byte[] raw = new byte[h * (1 + w * 3)];
            for (int row = 0; row < h; ++row) {
                final int base = row * (1 + w * 3);
                raw[base] = 0;
                for (int col = 0; col < w; ++col) {
                    raw[base + 1 + col * 3] = (byte)r;
                    raw[base + 1 + col * 3 + 1] = (byte)g;
                    raw[base + 1 + col * 3 + 2] = (byte)b;
                }
            }
            final Deflater def = new Deflater(9);
            def.setInput(raw);
            def.finish();
            final byte[] comp = new byte[raw.length + 64];
            final int compLen = def.deflate(comp);
            def.end();
            final byte[] idat = Arrays.copyOf(comp, compLen);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] { -119, 80, 78, 71, 13, 10, 26, 10 });
            writeChunk(out, "IHDR", new byte[] { 0, 0, 0, 16, 0, 0, 0, 16, 8, 2, 0, 0, 0 });
            writeChunk(out, "IDAT", idat);
            writeChunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        }
        catch (final Exception e) {
            return ResourcePackGenerator.PLACEHOLDER_PNG;
        }
    }
    
    private static byte[] makeTrianglePng(final int r, final int g, final int b) {
        try {
            final int w = 16;
            final int h = 16;
            final byte[] raw = new byte[h * (1 + w * 3)];
            for (int row = 0; row < h; ++row) {
                final int base = row * (1 + w * 3);
                raw[base] = 0;
                // Create triangle shape (wider at bottom)
                int widthAtRow = (row * 2) + 2; // Triangle gets wider towards bottom
                int startCol = (w - widthAtRow) / 2;
                int endCol = startCol + widthAtRow;
                for (int col = 0; col < w; ++col) {
                    if (col >= startCol && col < endCol) {
                        raw[base + 1 + col * 3] = (byte)r;
                        raw[base + 1 + col * 3 + 1] = (byte)g;
                        raw[base + 1 + col * 3 + 2] = (byte)b;
                    } else {
                        // Transparent (white with 0 alpha - but we use RGB so just make it black/dark)
                        raw[base + 1 + col * 3] = 0;
                        raw[base + 1 + col * 3 + 1] = 0;
                        raw[base + 1 + col * 3 + 2] = 0;
                    }
                }
            }
            final Deflater def = new Deflater(9);
            def.setInput(raw);
            def.finish();
            final byte[] comp = new byte[raw.length + 64];
            final int compLen = def.deflate(comp);
            def.end();
            final byte[] idat = Arrays.copyOf(comp, compLen);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] { -119, 80, 78, 71, 13, 10, 26, 10 });
            writeChunk(out, "IHDR", new byte[] { 0, 0, 0, 16, 0, 0, 0, 16, 8, 2, 0, 0, 0 });
            writeChunk(out, "IDAT", idat);
            writeChunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        }
        catch (final Exception e) {
            return ResourcePackGenerator.PLACEHOLDER_PNG;
        }
    }
    
    private static void writeChunk(final ByteArrayOutputStream out, final String type, final byte[] data) throws Exception {
        final byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(data.length >>> 24 & 0xFF);
        out.write(data.length >>> 16 & 0xFF);
        out.write(data.length >>> 8 & 0xFF);
        out.write(data.length & 0xFF);
        out.write(typeBytes);
        out.write(data);
        final CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        final long c = crc.getValue();
        out.write((int)(c >>> 24) & 0xFF);
        out.write((int)(c >>> 16) & 0xFF);
        out.write((int)(c >>> 8) & 0xFF);
        out.write((int)c & 0xFF);
    }
    
    static {
        GSON = new GsonBuilder().setPrettyPrinting().create();
        FACE_TO_MC = Map.of("top", "up", "bottom", "down", "north", "north", "south", "south", "east", "east", "west", "west");
        PLACEHOLDER_PNG = new byte[] { -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 119, 83, -34, 0, 0, 0, 12, 73, 68, 65, 84, 8, -41, 99, -8, 15, -16, 0, 0, 0, 2, 0, 1, -30, 33, -68, 51, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126 };
    }
}
