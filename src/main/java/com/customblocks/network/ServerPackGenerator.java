package com.customblocks.network;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.CustomBlocksMod;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ServerPackGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int PACK_FORMAT = 34;
    private static final String MOD_ID = CustomBlocksMod.MOD_ID;
    private static final Map<String, String> FACE_TO_MC = Map.of(
            "top", "up", "bottom", "down", "north", "north",
            "south", "south", "east", "east", "west", "west"
    );

    public static byte[] generateZipInMemory() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                
                // pack.mcmeta
                JsonObject pack = new JsonObject();
                pack.addProperty("pack_format", PACK_FORMAT);
                pack.addProperty("description", "CustomBlocks Server Pack");
                JsonObject meta = new JsonObject();
                meta.add("pack", pack);
                addZipEntry(zos, "pack.mcmeta", GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));

                for (int i = 0; i < CustomBlocksConfig.maxSlots; i++) {
                    String slotKey = "slot_" + i;
                    String modelRef = MOD_ID + ":block/" + slotKey;
                    SlotData data = SlotManager.getByIndex(i);

                    // default texture
                    if (data != null && data.texture != null && data.texture.length > 0) {
                        addZipEntry(zos, "assets/" + MOD_ID + "/textures/block/" + slotKey + ".png", data.texture);
                        if (data.isAnimated() && data.animMeta != null) {
                            addZipEntry(zos, "assets/" + MOD_ID + "/textures/block/" + slotKey + ".png.mcmeta", data.animMeta.getBytes(StandardCharsets.UTF_8));
                        }
                    } else {
                        addZipEntry(zos, "assets/" + MOD_ID + "/textures/block/" + slotKey + ".png", PLACEHOLDER_PNG);
                    }

                    // face textures
                    if (data != null && data.hasFaces()) {
                        for (Map.Entry<String, byte[]> face : data.faceTextures.entrySet()) {
                            String faceKey = face.getKey();
                            byte[] faceBytes = face.getValue();
                            String facePath = "assets/" + MOD_ID + "/textures/block/" + slotKey + "_" + faceKey + ".png";
                            addZipEntry(zos, facePath, faceBytes);
                            
                            try {
                                BufferedImage faceImg = ImageIO.read(new ByteArrayInputStream(faceBytes));
                                if (faceImg != null && faceImg.getHeight() > faceImg.getWidth()) {
                                    int frames = faceImg.getHeight() / faceImg.getWidth();
                                    StringBuilder sb = new StringBuilder("{\"animation\":{\"interpolate\":true,\"frames\":[");
                                    for (int fi = 0; fi < frames; fi++) {
                                        if (fi > 0) sb.append(",");
                                        sb.append("{\"index\":").append(fi).append(",\"time\":5}");
                                    }
                                    sb.append("]}}");
                                    addZipEntry(zos, facePath + ".mcmeta", sb.toString().getBytes(StandardCharsets.UTF_8));
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    // Blockstate
                    JsonObject variant = new JsonObject(); variant.addProperty("model", modelRef);
                    JsonObject variants = new JsonObject(); variants.add("", variant);
                    JsonObject bs = new JsonObject(); bs.add("variants", variants);
                    addZipEntry(zos, "assets/" + MOD_ID + "/blockstates/" + slotKey + ".json", GSON.toJson(bs).getBytes(StandardCharsets.UTF_8));

                    // Block Model
                    JsonObject bm = new JsonObject();
                    if (data != null && data.isShaped()) {
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
                    } else if (data != null && data.hasFaces()) {
                        bm.addProperty("parent", "minecraft:block/cube");
                        JsonObject tex = new JsonObject();
                        tex.addProperty("particle", MOD_ID + ":block/" + slotKey);
                        for (String face : SlotData.FACE_KEYS) {
                            String mcFace = FACE_TO_MC.get(face);
                            if (data.faceTextures.containsKey(face)) {
                                tex.addProperty(mcFace, MOD_ID + ":block/" + slotKey + "_" + face);
                            } else {
                                tex.addProperty(mcFace, MOD_ID + ":block/" + slotKey);
                            }
                        }
                        bm.add("textures", tex);
                    } else {
                        bm.addProperty("parent", "minecraft:block/cube_all");
                        JsonObject tex = new JsonObject();
                        tex.addProperty("all", MOD_ID + ":block/" + slotKey);
                        bm.add("textures", tex);
                    }
                    addZipEntry(zos, "assets/" + MOD_ID + "/models/block/" + slotKey + ".json", GSON.toJson(bm).getBytes(StandardCharsets.UTF_8));

                    // Item model
                    JsonObject im = new JsonObject();
                    im.addProperty("parent", modelRef);
                    addZipEntry(zos, "assets/" + MOD_ID + "/models/item/" + slotKey + ".json", GSON.toJson(im).getBytes(StandardCharsets.UTF_8));
                }

                // Tab Icon
                byte[] tabIcon = SlotManager.getTabIconTexture();
                if (tabIcon != null && tabIcon.length > 0) {
                    addZipEntry(zos, "assets/" + MOD_ID + "/textures/item/tab_icon.png", tabIcon);
                    addZipEntry(zos, "pack.png", tabIcon);
                } else {
                    addZipEntry(zos, "assets/" + MOD_ID + "/textures/item/tab_icon.png", PLACEHOLDER_PNG);
                }
            }
            return baos.toByteArray();
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to generate server pack ZIP", e);
            return null;
        }
    }

    private static void addModelFace(JsonObject faces, String mcFaceName, String cbFaceName, float u1, float v1, float u2, float v2) {
        JsonObject faceObj = new JsonObject();
        com.google.gson.JsonArray uv = new com.google.gson.JsonArray();
        uv.add(0f); uv.add(0f); uv.add(16f); uv.add(16f);
        faceObj.add("uv", uv);
        faceObj.addProperty("texture", "#" + FACE_TO_MC.get(cbFaceName));
        // Removed cullface to fix rendering of custom shaped volumes
        faces.add(mcFaceName, faceObj);
    }

    private static void addZipEntry(ZipOutputStream zos, String path, byte[] data) throws Exception {
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
