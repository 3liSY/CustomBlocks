package com.customblocks;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SlotManager {

    public static final int MAX_SLOTS = 512;
    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/SlotManager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, SlotData> SLOTS      = new ConcurrentHashMap<>();
    private static final Map<String, String>   ID_TO_SLOT = new ConcurrentHashMap<>();
    private static byte[] tabIconTexture = null;

    /** Valid face keys — exactly what the user types in commands. */
    public static final List<String> FACE_KEYS = List.of("top","bottom","north","south","east","west");

    // ── Data class ────────────────────────────────────────────────────────────

    public static class SlotData {
        public final int    index;
        public final String customId;
        public final String displayName;
        public       byte[] texture;
        public       int    lightLevel;
        public       float  hardness;
        public       String soundType;
        /** Per-face overrides. Keys: top bottom north south east west.
         *  Missing faces fall back to the default texture. Never null. */
        public final Map<String, byte[]> faceTextures;

        public SlotData(int index, String customId, String displayName, byte[] texture,
                        int lightLevel, float hardness, String soundType,
                        Map<String, byte[]> faceTextures) {
            this.index        = index;
            this.customId     = customId;
            this.displayName  = displayName;
            this.texture      = texture;
            this.lightLevel   = Math.max(0, Math.min(15, lightLevel));
            this.hardness     = hardness;
            this.soundType    = (soundType != null && !soundType.isEmpty()) ? soundType : "stone";
            this.faceTextures = (faceTextures != null)
                    ? new ConcurrentHashMap<>(faceTextures)
                    : new ConcurrentHashMap<>();
        }

        /** No face overrides */
        public SlotData(int index, String customId, String displayName, byte[] texture,
                        int lightLevel, float hardness, String soundType) {
            this(index, customId, displayName, texture, lightLevel, hardness, soundType, null);
        }

        /** All defaults */
        public SlotData(int index, String customId, String displayName, byte[] texture) {
            this(index, customId, displayName, texture, 0, 1.5f, "stone", null);
        }

        public String  slotKey()  { return "slot_" + index; }
        public boolean hasFaces() { return !faceTextures.isEmpty(); }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public static SlotData getBySlot(String slotKey)  { return SLOTS.get(slotKey); }
    public static SlotData getById(String customId) {
        String k = ID_TO_SLOT.get(customId);
        return k != null ? SLOTS.get(k) : null;
    }
    public static Collection<SlotData> allSlots()     { return Collections.unmodifiableCollection(SLOTS.values()); }
    public static Set<String>          allCustomIds() { return Collections.unmodifiableSet(ID_TO_SLOT.keySet()); }
    public static boolean              hasId(String id)  { return ID_TO_SLOT.containsKey(id); }
    public static int                  usedSlots()    { return SLOTS.size(); }
    public static int                  freeSlots()    { return MAX_SLOTS - SLOTS.size(); }
    public static byte[]               getTabIconTexture() { return tabIconTexture; }
    public static void                 setTabIconTexture(byte[] t) { tabIconTexture = t; }

    public static void clearAll() {
        SLOTS.clear();
        ID_TO_SLOT.clear();
        tabIconTexture = null;
    }

    public static String getDisplayName(String slotKey) {
        SlotData d = SLOTS.get(slotKey);
        return d != null ? d.displayName : null;
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    public static SlotData assign(String customId, String displayName, byte[] texture) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            String key = "slot_" + i;
            if (!SLOTS.containsKey(key)) {
                SlotData data = new SlotData(i, customId, displayName, texture);
                SLOTS.put(key, data);
                ID_TO_SLOT.put(customId, key);
                return data;
            }
        }
        return null;
    }

    public static SlotData assignAtIndex(int index, String customId, String displayName, byte[] texture) {
        if (index < 0 || index >= MAX_SLOTS) return null;
        String key = "slot_" + index;
        // Preserve face textures if slot already exists (e.g. re-join)
        SlotData existing = SLOTS.get(key);
        Map<String, byte[]> faces = (existing != null) ? existing.faceTextures : null;
        SlotData data = new SlotData(index, customId, displayName, texture, 0, 1.5f, "stone", faces);
        SLOTS.put(key, data);
        ID_TO_SLOT.put(customId, key);
        return data;
    }

    public static boolean remove(String customId) {
        String k = ID_TO_SLOT.remove(customId);
        if (k == null) return false;
        SLOTS.remove(k);
        return true;
    }

    public static boolean rename(String customId, String newName) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, newName, o.texture,
                o.lightLevel, o.hardness, o.soundType, o.faceTextures));
        return true;
    }

    public static boolean updateTexture(String customId, byte[] texture) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, texture,
                o.lightLevel, o.hardness, o.soundType, o.faceTextures));
        return true;
    }

    public static boolean setProperties(String customId, int lightLevel, float hardness, String soundType) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                lightLevel, hardness, soundType, o.faceTextures));
        return true;
    }

    public static boolean setLightLevel(String customId, int level) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                level, o.hardness, o.soundType, o.faceTextures));
        return true;
    }

    public static boolean setHardness(String customId, float hardness) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, hardness, o.soundType, o.faceTextures));
        return true;
    }

    public static boolean setSoundType(String customId, String soundType) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, soundType, o.faceTextures));
        return true;
    }

    /** Set or replace one face texture. face must be one of FACE_KEYS. */
    public static boolean setFaceTexture(String customId, String face, byte[] texture) {
        if (!FACE_KEYS.contains(face)) return false;
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        Map<String, byte[]> faces = new ConcurrentHashMap<>(o.faceTextures);
        faces.put(face, texture);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, faces));
        return true;
    }

    /** Remove a face override, reverting that face back to the default texture. */
    public static boolean clearFaceTexture(String customId, String face) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        Map<String, byte[]> faces = new ConcurrentHashMap<>(o.faceTextures);
        faces.remove(face);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, faces));
        return true;
    }

    /** Remove ALL face overrides from a block. */
    public static boolean clearAllFaces(String customId) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, null));
        return true;
    }

    // ── Persistence (server-side) ─────────────────────────────────────────────

    private static File getConfigDir() { return new File("config/customblocks"); }

    public static void saveAll() {
        File dir = getConfigDir();
        dir.mkdirs();
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (SlotData d : SLOTS.values()) {
            JsonObject e = new JsonObject();
            e.addProperty("index",       d.index);
            e.addProperty("customId",    d.customId);
            e.addProperty("displayName", d.displayName);
            e.addProperty("lightLevel",  d.lightLevel);
            e.addProperty("hardness",    d.hardness);
            e.addProperty("soundType",   d.soundType);
            // Record which faces have overrides so loadAll knows which files to look for
            if (!d.faceTextures.isEmpty()) {
                JsonArray faces = new JsonArray();
                d.faceTextures.keySet().forEach(faces::add);
                e.add("faces", faces);
            }
            arr.add(e);
        }
        root.add("slots", arr);
        try (FileWriter fw = new FileWriter(new File(dir, "slots.json"), StandardCharsets.UTF_8)) {
            GSON.toJson(root, fw);
        } catch (IOException ex) { LOGGER.error("Failed to save slots.json", ex); }

        // Track which slot+face files are still valid
        java.util.Set<String> validFiles = new java.util.HashSet<>();
        for (SlotData d : SLOTS.values()) {
            if (d.texture != null && d.texture.length > 0) {
                try {
                    Files.write(new File(dir, d.slotKey() + ".png").toPath(), d.texture);
                    validFiles.add(d.slotKey() + ".png"); // only protect if actually written
                } catch (IOException ex) { LOGGER.error("Failed to save texture for {}", d.customId, ex); }
            }
            // Save face textures and mark as valid
            for (Map.Entry<String, byte[]> face : d.faceTextures.entrySet()) {
                String faceFile = d.slotKey() + "_" + face.getKey() + ".png";
                try { Files.write(new File(dir, faceFile).toPath(), face.getValue()); }
                catch (IOException ex) { LOGGER.error("Failed to save face texture {} for {}", face.getKey(), d.customId, ex); }
                validFiles.add(faceFile);
            }
        }
        // Delete any orphaned face PNG files (from cleared faces or deleted blocks)
        File[] pngs = dir.listFiles((d2, n) -> n.matches("slot_\\d+(_[a-z]+)?\\.png"));
        if (pngs != null) {
            for (File f : pngs) {
                if (!validFiles.contains(f.getName())) {
                    try { Files.deleteIfExists(f.toPath()); }
                    catch (IOException ignored) {}
                }
            }
        }
        if (tabIconTexture != null) {
            try { Files.write(new File(dir, "tab_icon.png").toPath(), tabIconTexture); }
            catch (IOException ex) { LOGGER.error("Failed to save tab icon", ex); }
        }
    }

    public static void loadAll() {
        File dir = getConfigDir();
        if (!dir.exists()) return;
        File slotsFile = new File(dir, "slots.json");
        if (!slotsFile.exists()) return;
        try {
            JsonObject root = JsonParser.parseReader(new FileReader(slotsFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray slots = root.getAsJsonArray("slots");
            for (JsonElement el : slots) {
                JsonObject e = el.getAsJsonObject();
                int    index       = e.get("index").getAsInt();
                String customId    = e.get("customId").getAsString();
                String displayName = e.get("displayName").getAsString();
                int    lightLevel  = e.has("lightLevel") ? e.get("lightLevel").getAsInt()   : 0;
                float  hardness    = e.has("hardness")   ? e.get("hardness").getAsFloat()   : 1.5f;
                String soundType   = e.has("soundType")  ? e.get("soundType").getAsString() : "stone";
                File   texFile     = new File(dir, "slot_" + index + ".png");
                byte[] texture     = texFile.exists() ? Files.readAllBytes(texFile.toPath()) : null;
                // Load face textures
                Map<String, byte[]> faces = new ConcurrentHashMap<>();
                if (e.has("faces")) {
                    for (JsonElement faceEl : e.getAsJsonArray("faces")) {
                        String face = faceEl.getAsString();
                        File faceFile = new File(dir, "slot_" + index + "_" + face + ".png");
                        if (faceFile.exists()) faces.put(face, Files.readAllBytes(faceFile.toPath()));
                    }
                }
                SlotData data = new SlotData(index, customId, displayName, texture,
                        lightLevel, hardness, soundType, faces);
                SLOTS.put("slot_" + index, data);
                ID_TO_SLOT.put(customId, "slot_" + index);
            }
            LOGGER.info("[CustomBlocks] Loaded {} slot(s).", SLOTS.size());
        } catch (Exception ex) { LOGGER.error("Failed to load slots.json", ex); }

        File tabFile = new File(dir, "tab_icon.png");
        if (tabFile.exists()) {
            try { tabIconTexture = Files.readAllBytes(tabFile.toPath()); }
            catch (IOException ex) { LOGGER.error("Failed to load tab icon", ex); }
        }
    }

    // ── Client-side persistence ───────────────────────────────────────────────

    public static void loadFromClientDir(File mcDir) {
        File dir = new File(mcDir, "config/customblocks");
        if (!dir.exists()) return;
        File slotsFile = new File(dir, "slots.json");
        if (!slotsFile.exists()) return;
        try {
            SLOTS.clear();
            ID_TO_SLOT.clear();
            JsonObject root = JsonParser.parseReader(new FileReader(slotsFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray slots = root.getAsJsonArray("slots");
            for (JsonElement el : slots) {
                JsonObject e = el.getAsJsonObject();
                int    index       = e.get("index").getAsInt();
                String customId    = e.get("customId").getAsString();
                String displayName = e.get("displayName").getAsString();
                int    lightLevel  = e.has("lightLevel") ? e.get("lightLevel").getAsInt()   : 0;
                float  hardness    = e.has("hardness")   ? e.get("hardness").getAsFloat()   : 1.5f;
                String soundType   = e.has("soundType")  ? e.get("soundType").getAsString() : "stone";
                File   texFile     = new File(dir, "slot_" + index + ".png");
                byte[] texture     = texFile.exists() ? Files.readAllBytes(texFile.toPath()) : null;
                Map<String, byte[]> faces = new ConcurrentHashMap<>();
                if (e.has("faces")) {
                    for (JsonElement faceEl : e.getAsJsonArray("faces")) {
                        String face = faceEl.getAsString();
                        File faceFile = new File(dir, "slot_" + index + "_" + face + ".png");
                        if (faceFile.exists()) faces.put(face, Files.readAllBytes(faceFile.toPath()));
                    }
                }
                SLOTS.put("slot_" + index, new SlotData(index, customId, displayName, texture,
                        lightLevel, hardness, soundType, faces));
                ID_TO_SLOT.put(customId, "slot_" + index);
            }
            File tabFile = new File(dir, "tab_icon.png");
            if (tabFile.exists()) tabIconTexture = Files.readAllBytes(tabFile.toPath());
        } catch (Exception ex) { LOGGER.error("[CustomBlocks] Client failed to load slots", ex); }
    }

    public static void saveToClientDir(File mcDir) {
        File dir = new File(mcDir, "config/customblocks");
        dir.mkdirs();
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (SlotData d : SLOTS.values()) {
            JsonObject e = new JsonObject();
            e.addProperty("index",       d.index);
            e.addProperty("customId",    d.customId);
            e.addProperty("displayName", d.displayName);
            e.addProperty("lightLevel",  d.lightLevel);
            e.addProperty("hardness",    d.hardness);
            e.addProperty("soundType",   d.soundType);
            if (!d.faceTextures.isEmpty()) {
                JsonArray faces = new JsonArray();
                d.faceTextures.keySet().forEach(faces::add);
                e.add("faces", faces);
            }
            arr.add(e);
        }
        root.add("slots", arr);
        try (FileWriter fw = new FileWriter(new File(dir, "slots.json"), StandardCharsets.UTF_8)) {
            GSON.toJson(root, fw);
        } catch (IOException ex) { LOGGER.error("Failed to write client slots.json", ex); }
        for (SlotData d : SLOTS.values()) {
            if (d.texture != null) {
                try { Files.write(new File(dir, d.slotKey() + ".png").toPath(), d.texture); }
                catch (IOException ignored) {}
            }
            for (Map.Entry<String, byte[]> face : d.faceTextures.entrySet()) {
                try { Files.write(new File(dir, d.slotKey() + "_" + face.getKey() + ".png").toPath(), face.getValue()); }
                catch (IOException ignored) {}
            }
        }
        if (tabIconTexture != null) {
            try { Files.write(new File(dir, "tab_icon.png").toPath(), tabIconTexture); }
            catch (IOException ignored) {}
        }
        // Clean up orphaned face PNGs on client side too
        java.util.Set<String> clientValid = new java.util.HashSet<>();
        for (SlotData d : SLOTS.values()) {
            if (d.texture != null && d.texture.length > 0) clientValid.add(d.slotKey() + ".png");
            for (String face : d.faceTextures.keySet()) clientValid.add(d.slotKey() + "_" + face + ".png");
        }
        File[] clientPngs = dir.listFiles((d2, n) -> n.matches("slot_\\d+(_[a-z]+)?\\.png"));
        if (clientPngs != null) {
            for (File f : clientPngs) {
                if (!clientValid.contains(f.getName())) {
                    try { Files.deleteIfExists(f.toPath()); } catch (IOException ignored) {}
                }
            }
        }
    }
}
