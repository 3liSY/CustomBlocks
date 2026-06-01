package com.customblocks.network;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.customblocks.CustomBlocksConfig;
import com.customblocks.CustomBlocksMod;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ServerPackGenerator {
    private static final Gson   GSON        = new GsonBuilder().setPrettyPrinting().create();
    private static final int    PACK_FORMAT = 34;
    private static final String MOD_ID      = CustomBlocksMod.MOD_ID;

    /**
     * 1.17 — If a pack build is requested before startup texture loading is complete,
     * we defer it here and run it once the flag flips.
     */
    private static volatile boolean pendingBuildRequest = false;

    /**
     * 1.17 — Called by SlotManager.finalizeStartupLoad() when the async texture load
     * finishes. Triggers exactly one deferred pack build if one was queued.
     */
    public static void flushPendingBuildIfNeeded() {
        if (pendingBuildRequest) {
            pendingBuildRequest = false;
            com.customblocks.network.ResourcePackServer.updatePack();
        }
    }

    private static final Map<String, String> FACE_TO_MC = Map.of(
            "top",    "up",
            "bottom", "down",
            "north",  "north",
            "south",  "south",
            "east",   "east",
            "west",   "west"
    );


    /** Default: generate from live snapshot. */
    public static void generateZipToDisk(File outputFile) {
        generateZipWithSnapshot(SlotManager.getSnapshot(), outputFile);
    }

    /**
     * The Royal Architect Fix: Generates the ZIP directly to disk from a frozen snapshot.
     */
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    public static void generateZipWithSnapshot(SlotManager.Snapshot snapshot, File outputFile) {
        // 1.17 — Don't build a pack before textures are loaded; the result would be all
        // purple/black checkerboard for every client who joins during the startup window.
        if (!com.customblocks.core.SlotManager.isStartupLoadComplete()) {
            pendingBuildRequest = true;
            CustomBlocksMod.LOGGER.info("[CustomBlocks] 1.17 Pack build deferred — startup texture load not yet complete.");
            return;
        }
        try {
            if (outputFile.getParentFile() != null) {
                outputFile.getParentFile().mkdirs();
            }
            // 1.11 Fix TOCTOU: Write to a .tmp file first, then atomically move to the live file.
            File tmpFile = new File(outputFile.getAbsolutePath() + ".tmp");
            // Track paths already written so a duplicate (e.g. two SlotData with
            // the same index, corrupted state) cannot abort the entire pack build
            // with ZipException: duplicate entry.
            java.util.Set<String> writtenPaths = new java.util.HashSet<>();
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmpFile))) {

                // pack.mcmeta
                JsonObject pack = new JsonObject();
                pack.addProperty("pack_format", PACK_FORMAT);
                pack.addProperty("description", "CustomBlocks Server Pack");
                JsonObject meta = new JsonObject();
                meta.add("pack", pack);
                addZipEntry(zos, "pack.mcmeta", GSON.toJson(meta).getBytes(StandardCharsets.UTF_8), writtenPaths);

                // Dedupe snapshot entries by index so a corrupt state cannot produce
                // duplicate slot_N.png writes. First entry wins; duplicates logged.
                Map<Integer, SlotData> dedupedByIndex = new java.util.LinkedHashMap<>();
                for (SlotData d : snapshot.slots()) {
                    if (d == null) continue;
                    if (dedupedByIndex.putIfAbsent(d.index, d) != null) {
                        CustomBlocksMod.LOGGER.warn(
                            "[CustomBlocks] Duplicate SlotData for slot_{} ('{}' vs '{}') — keeping first, skipping duplicate",
                            d.index, dedupedByIndex.get(d.index).customId, d.customId);
                    }
                }

                // LAYER 1: The Active-Only Compiler (Skip Empty Blocks)
                for (SlotData data : dedupedByIndex.values()) {
                    if ("tab_icon".equals(data.customId)) continue;
                    
                    int i = data.index;
                    String slotKey = "slot_" + i;
                    String modelRef = MOD_ID + ":block/" + slotKey;

                    // default texture
                    if (data.texture != null && data.texture.length > 0) {
                        // 1.9 — validation gate: fix non-power-of-2 textures before they
                        // reach the atlas and silently kill mipmapping for every block.
                        int frames = com.customblocks.ImageProcessor.getVerticalFrames(data.texture);
                        byte[] texBytes = (frames == 1)
                            ? com.customblocks.ImageProcessor.ensurePowerOf2(data.texture)
                            : data.texture; // animated strips: ensurePowerOf2 must not be called
                        addZipEntry(zos, "assets/" + MOD_ID + "/textures/block/" + slotKey + ".png", texBytes, writtenPaths);
                        // Use actual pixel dimensions as source of truth so the
                        // mcmeta is still written even when animMeta is null from
                        // legacy save data or a lost packet. Prevents stacked-frames.
                        if (frames > 1) {
                            String effectiveMeta = (data.animMeta != null && !data.animMeta.isEmpty())
                                    ? data.animMeta
                                    : com.customblocks.ImageProcessor.synthesizeDefaultMcmeta(frames);
                            addZipEntry(zos, "assets/" + MOD_ID + "/textures/block/" + slotKey + ".png.mcmeta",
                                    effectiveMeta.getBytes(StandardCharsets.UTF_8), writtenPaths);
                        }
                    } else {
                        addZipEntry(zos, "assets/" + MOD_ID + "/textures/block/" + slotKey + ".png", PLACEHOLDER_PNG, writtenPaths);
                    }

                    // face textures
                    if (data.hasFaces()) {
                        for (Map.Entry<String, byte[]> face : data.faceTextures.entrySet()) {
                            String faceKey = face.getKey();
                            byte[] faceBytes = face.getValue();
                            // 1.9 — validation gate for face textures (static only)
                            int faceFrames = com.customblocks.ImageProcessor.getVerticalFrames(faceBytes);
                            if (faceFrames == 1) faceBytes = com.customblocks.ImageProcessor.ensurePowerOf2(faceBytes);
                            String facePath = "assets/" + MOD_ID + "/textures/block/" + slotKey + "_" + faceKey + ".png";
                            addZipEntry(zos, facePath, faceBytes, writtenPaths);

                            int frames = com.customblocks.ImageProcessor.getVerticalFrames(faceBytes);
                            if (frames > 1) {
                                // Prefer real per-frame timing from processAnimation;
                                // fall back to shared synthesizer for legacy/null cases.
                                String mcmetaJson = (data.animMeta != null && !data.animMeta.isEmpty()
                                        && data.animMeta.contains("\"frames\""))
                                        ? data.animMeta
                                        : com.customblocks.ImageProcessor.synthesizeDefaultMcmeta(frames);
                                addZipEntry(zos, facePath + ".mcmeta", mcmetaJson.getBytes(StandardCharsets.UTF_8), writtenPaths);
                            }
                        }
                        // Legacy texture stitching: for faces WITHOUT overrides,
                        // write a copy of the main texture so each face has its own file.
                        if (data.texture != null && data.texture.length > 0) {
                            for (String face : SlotData.FACE_KEYS) {
                                if (!data.faceTextures.containsKey(face)) {
                                    String inheritPath = "assets/" + MOD_ID + "/textures/block/" + slotKey + "_" + face + ".png";
                                    addZipEntry(zos, inheritPath, data.texture, writtenPaths);
                                }
                            }
                        }
                    }

                    // Blockstate
                    JsonObject variant = new JsonObject(); variant.addProperty("model", modelRef);
                    JsonObject variants = new JsonObject(); variants.add("", variant);
                    JsonObject bs = new JsonObject(); bs.add("variants", variants);
                    addZipEntry(zos, "assets/" + MOD_ID + "/blockstates/" + slotKey + ".json", GSON.toJson(bs).getBytes(StandardCharsets.UTF_8), writtenPaths);

                    // Block Model
                    JsonObject bm = new JsonObject();
                    if (data.isShaped()) {
                        JsonObject tex = new JsonObject();
                        tex.addProperty("particle", MOD_ID + ":block/" + slotKey);
                        for (String face : SlotData.FACE_KEYS) {
                            String mcFace = FACE_TO_MC.get(face);
                            String texRef = data.faceTextures.containsKey(face) ? MOD_ID + ":block/" + slotKey + "_" + face : MOD_ID + ":block/" + slotKey;
                            tex.addProperty(mcFace, texRef);
                        }
                        bm.add("textures", tex);
                        com.google.gson.JsonArray elements = new com.google.gson.JsonArray();
                        for (SlotData.ShapeBox box : data.shapeBoxes) {
                            JsonObject el = new JsonObject();
                            com.google.gson.JsonArray from = new com.google.gson.JsonArray();
                            from.add(box.x1()); from.add(box.y1()); from.add(box.z1());
                            com.google.gson.JsonArray to = new com.google.gson.JsonArray();
                            to.add(box.x2()); to.add(box.y2()); to.add(box.z2());
                            el.add("from", from); el.add("to", to);
                            
                            JsonObject faces = new JsonObject();
                            float x1 = box.x1(), y1 = box.y1(), z1 = box.z1();
                            float x2 = box.x2(), y2 = box.y2(), z2 = box.z2();
                            addModelFace(faces, "down", "bottom", x1, z1, x2, z2);
                            addModelFace(faces, "up", "top", x1, z1, x2, z2);
                            addModelFace(faces, "north", "north", 16 - x2, 16 - y2, 16 - x1, 16 - y1);
                            addModelFace(faces, "south", "south", x1, 16 - y2, x2, 16 - y1);
                            addModelFace(faces, "west", "west", z1, 16 - y2, z2, 16 - y1);
                            addModelFace(faces, "east", "east", 16 - z2, 16 - y2, 16 - z1, 16 - y1);
                            el.add("faces", faces);
                            elements.add(el);
                        }
                        bm.add("elements", elements);
                    } else if (data.hasFaces()) {
                        // Legacy stitching: every face references its own file.
                        bm.addProperty("parent", "minecraft:block/cube");
                        JsonObject tex = new JsonObject();
                        tex.addProperty("particle", MOD_ID + ":block/" + slotKey);
                        for (String face : SlotData.FACE_KEYS) {
                            String mcFace = FACE_TO_MC.get(face);
                            tex.addProperty(mcFace, MOD_ID + ":block/" + slotKey + "_" + face);
                        }
                        bm.add("textures", tex);
                    } else {
                        bm.addProperty("parent", "minecraft:block/cube_all");
                        JsonObject tex = new JsonObject();
                        tex.addProperty("all", MOD_ID + ":block/" + slotKey);
                        bm.add("textures", tex);
                    }
                    addZipEntry(zos, "assets/" + MOD_ID + "/models/block/" + slotKey + ".json", GSON.toJson(bm).getBytes(StandardCharsets.UTF_8), writtenPaths);

                    // Item model
                    JsonObject im = new JsonObject();
                    im.addProperty("parent", modelRef);
                    addZipEntry(zos, "assets/" + MOD_ID + "/models/item/" + slotKey + ".json", GSON.toJson(im).getBytes(StandardCharsets.UTF_8), writtenPaths);
                }

                // ── 1.8: Placeholder files for empty registered slots ─────────────
                // Every slot index 0..maxSlots-1 that has no SlotData must still have
                // blockstate + model files so Minecraft doesn't log "missing model" errors.
                // All empty slots share one model (customblocks:block/empty_slot) and one
                // 1×1 transparent texture.  This adds zero visible geometry to the world.
                int totalRegistered = com.customblocks.CustomBlocksConfig.maxSlots;
                boolean emptySlotTextureWritten = false;
                boolean emptySlotModelWritten   = false;
                for (int ei = 0; ei < totalRegistered; ei++) {
                    if (dedupedByIndex.containsKey(ei)) continue; // has real data
                    String eslotKey = "slot_" + ei;

                    // Shared texture — write only once
                    if (!emptySlotTextureWritten) {
                        addZipEntry(zos,
                            "assets/" + MOD_ID + "/textures/block/empty_slot_tex.png",
                            PLACEHOLDER_PNG, writtenPaths);
                        emptySlotTextureWritten = true;
                    }

                    // Shared model — write only once
                    if (!emptySlotModelWritten) {
                        // A model with no elements renders as completely invisible
                        JsonObject emTex = new JsonObject();
                        emTex.addProperty("particle", MOD_ID + ":block/empty_slot_tex");
                        JsonObject emModel = new JsonObject();
                        emModel.addProperty("parent", "minecraft:block/block");
                        emModel.add("textures", emTex);
                        emModel.add("elements", new com.google.gson.JsonArray()); // no geometry
                        addZipEntry(zos,
                            "assets/" + MOD_ID + "/models/block/empty_slot.json",
                            GSON.toJson(emModel).getBytes(StandardCharsets.UTF_8), writtenPaths);
                        emptySlotModelWritten = true;
                    }

                    // Per-empty-slot blockstate pointing to the shared model
                    JsonObject emVariant = new JsonObject();
                    emVariant.addProperty("model", MOD_ID + ":block/empty_slot");
                    JsonObject emVariants = new JsonObject();
                    emVariants.add("", emVariant);
                    JsonObject emBs = new JsonObject();
                    emBs.add("variants", emVariants);
                    addZipEntry(zos,
                        "assets/" + MOD_ID + "/blockstates/" + eslotKey + ".json",
                        GSON.toJson(emBs).getBytes(StandardCharsets.UTF_8), writtenPaths);

                    // Per-empty-slot item model pointing to the shared block model
                    JsonObject emItemModel = new JsonObject();
                    emItemModel.addProperty("parent", MOD_ID + ":block/empty_slot");
                    addZipEntry(zos,
                        "assets/" + MOD_ID + "/models/item/" + eslotKey + ".json",
                        GSON.toJson(emItemModel).getBytes(StandardCharsets.UTF_8), writtenPaths);
                }
                // ─────────────────────────────────────────────────────────────────

                // Tab Icon
                byte[] tabIcon = snapshot.tabIcon();
                if (tabIcon != null && tabIcon.length > 0) {
                    addZipEntry(zos, "assets/" + MOD_ID + "/textures/item/tab_icon.png", tabIcon, writtenPaths);
                    addZipEntry(zos, "pack.png", tabIcon, writtenPaths);
                } else {
                    addZipEntry(zos, "assets/" + MOD_ID + "/textures/item/tab_icon.png", PLACEHOLDER_PNG, writtenPaths);
                }
                addGeneratedItemModel(zos, "tab_icon", "customblocks:item/tab_icon", writtenPaths);

                addGeneratedItemModel(zos, "black_square", "minecraft:item/black_dye", writtenPaths);
                addGeneratedItemModel(zos, "yellow_square", "minecraft:item/yellow_dye", writtenPaths);
                addGeneratedItemModel(zos, "green_square", "minecraft:item/green_dye", writtenPaths);
                addGeneratedItemModel(zos, "red_square", "minecraft:item/red_dye", writtenPaths);
                addGeneratedItemModel(zos, "black_triangle", "minecraft:item/black_dye", writtenPaths);
                addGeneratedItemModel(zos, "yellow_triangle", "minecraft:item/yellow_dye", writtenPaths);
                addGeneratedItemModel(zos, "green_triangle", "minecraft:item/green_dye", writtenPaths);
                addGeneratedItemModel(zos, "red_triangle", "minecraft:item/red_dye", writtenPaths);
                addGeneratedItemModel(zos, "custom_square", "minecraft:item/light_blue_dye", writtenPaths);
                addGeneratedItemModel(zos, "custom_triangle", "minecraft:item/light_blue_dye", writtenPaths);
                addGeneratedItemModel(zos, "rainbow_rectangle", "minecraft:item/painting", writtenPaths);
                addGeneratedItemModel(zos, "golden_hexagon", "minecraft:item/golden_apple", writtenPaths);
                addGeneratedItemModel(zos, "lumina_brush", "minecraft:item/blaze_rod", writtenPaths);
                addGeneratedItemModel(zos, "amethyst_chisel", "minecraft:item/amethyst_shard", writtenPaths);
                addGeneratedItemModel(zos, "diamond_triangle", "minecraft:item/diamond", writtenPaths);
            }
            // Move atomically to prevent TOCTOU race conditions
            java.nio.file.Files.move(tmpFile.toPath(), outputFile.toPath(), 
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to generate server pack ZIP", e);
        }
    }

    private static void addModelFace(JsonObject faces, String mcFaceName, String cbFaceName, float u1, float v1, float u2, float v2) {
        JsonObject faceObj = new JsonObject();
        com.google.gson.JsonArray uv = new com.google.gson.JsonArray();
        uv.add(u1); uv.add(v1); uv.add(u2); uv.add(v2);
        faceObj.add("uv", uv);
        faceObj.addProperty("texture", "#" + FACE_TO_MC.get(cbFaceName));
        // Removed cullface to fix rendering of custom shaped volumes
        faces.add(mcFaceName, faceObj);
    }

    /**
     * Dedupe-safe ZIP writer — silently skips paths already written to this stream.
     * Prevents a single corrupt/duplicated slot from aborting the whole pack build
     * with {@code java.util.zip.ZipException: duplicate entry}.
     */
    private static void addGeneratedItemModel(ZipOutputStream zos, String itemId, String texture,
                                              java.util.Set<String> writtenPaths) throws Exception {
        JsonObject tex = new JsonObject();
        tex.addProperty("layer0", texture);
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:item/generated");
        model.add("textures", tex);
        addZipEntry(zos, "assets/" + MOD_ID + "/models/item/" + itemId + ".json",
            GSON.toJson(model).getBytes(StandardCharsets.UTF_8), writtenPaths);
    }

    private static void addZipEntry(ZipOutputStream zos, String path, byte[] data,
                                     java.util.Set<String> writtenPaths) throws Exception {
        if (!writtenPaths.add(path)) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Skipping duplicate ZIP entry: {}", path);
            return;
        }
        ZipEntry entry = new ZipEntry(path);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static final byte[] PLACEHOLDER_PNG = {
        (byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0x00,0x00,0x00,0x0D,0x49,0x48,0x44,0x52,
        0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x01,0x08,0x02,0x00,0x00,0x00,(byte)0x90,0x77,0x53,(byte)0xDE,
        0x00,0x00,0x00,0x0C,0x49,0x44,0x41,0x54,0x08,(byte)0xD7,0x63,(byte)0xF8,(byte)0x0F,(byte)0xF0,
        0x00,0x00,0x00,0x02,0x00,0x01,(byte)0xE2,0x21,(byte)0xBC,0x33,0x00,0x00,0x00,0x00,
        0x49,0x45,0x4E,0x44,(byte)0xAE,0x42,0x60,(byte)0x82
    };
}
