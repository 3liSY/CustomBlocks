package com.customblocks.core;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.network.ResourcePackServer;
import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe slot manager using immutable {@link SlotData} values.
 * <p>
 * Slot data lives in two maps:
 * <ul>
 *     <li>{@code byId}   — customId → SlotData  (fast ID lookup)</li>
 *     <li>{@code bySlot} — "slot_N" → SlotData  (fast slot-index lookup)</li>
 * </ul>
 * All mutation methods atomically replace the SlotData in both maps.
 */
public final class SlotManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String DATA_DIR  = "config/customblocks";
    private static final String DATA_FILE = "slots.json";

    // ── Storage ──────────────────────────────────────────────────────────────

    private static final ConcurrentHashMap<String, SlotData> byId   = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, SlotData> bySlot = new ConcurrentHashMap<>();
    private static volatile byte[] tabIconTexture = null;

    // ── Shape presets (constant) ─────────────────────────────────────────────

    public static final Map<String, List<SlotData.ShapeBox>> SHAPE_PRESETS;
    static {
        Map<String, List<SlotData.ShapeBox>> m = new LinkedHashMap<>();
        m.put("full",      null);
        m.put("slab",      List.of(new SlotData.ShapeBox(0,0,0,16,8,16)));
        m.put("thin",      List.of(new SlotData.ShapeBox(0,0,0,16,4,16)));
        m.put("carpet",    List.of(new SlotData.ShapeBox(0,0,0,16,1,16)));
        m.put("pillar",    List.of(new SlotData.ShapeBox(4,0,4,12,16,12)));
        m.put("small",     List.of(new SlotData.ShapeBox(2,0,2,14,14,14)));
        m.put("micro",     List.of(new SlotData.ShapeBox(4,0,4,12,8,12)));
        m.put("pane",      List.of(new SlotData.ShapeBox(7,0,0,9,16,16)));
        m.put("trapdoor",  List.of(new SlotData.ShapeBox(0,0,0,16,3,16)));
        m.put("fence",     List.of(new SlotData.ShapeBox(6,0,6,10,16,10)));
        m.put("stairs",    List.of(new SlotData.ShapeBox(0,0,0,16,8,16), new SlotData.ShapeBox(0,8,8,16,16,16)));
        m.put("cross",     List.of(new SlotData.ShapeBox(0,0,7,16,16,9), new SlotData.ShapeBox(7,0,0,9,16,16)));
        SHAPE_PRESETS = Collections.unmodifiableMap(m);
    }

    // ── Slot capacity ────────────────────────────────────────────────────────

    public static int maxSlots() { return CustomBlocksConfig.maxSlots; }
    public static int usedSlots() { return byId.size(); }
    public static int freeSlots() { return Math.max(0, maxSlots() - usedSlots()); }

    // ── Query ────────────────────────────────────────────────────────────────

    public static SlotData getById(String customId) {
        return customId == null ? null : byId.get(customId);
    }

    public static SlotData getBySlot(String slotKey) {
        return slotKey == null ? null : bySlot.get(slotKey);
    }

    public static SlotData getByIndex(int index) {
        return bySlot.get("slot_" + index);
    }

    public static boolean hasId(String customId) {
        return customId != null && byId.containsKey(customId);
    }

    public static Collection<SlotData> allSlots() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public static List<SlotData> sortedSlots() {
        return byId.values().stream()
                .filter(d -> !"tab_icon".equals(d.customId))
                .sorted(Comparator.comparingInt(d -> d.index))
                .collect(Collectors.toList());
    }

    // ── Mutation ─────────────────────────────────────────────────────────────

    /**
     * Assign a new block to the first free slot.
     * Returns the new SlotData, or null if no free slot.
     */
    public static synchronized SlotData assign(String customId, String displayName, byte[] texture) {
        if (byId.containsKey(customId)) return null;
        int idx = findFreeSlot();
        if (idx < 0) return null;
        SlotData data = new SlotData(idx, customId, displayName, texture);
        put(data);
        return data;
    }

    /**
     * Assign at a specific index (used for sync from server).
     */
    public static synchronized SlotData assignAtIndex(int index, String customId, String displayName, byte[] texture) {
        SlotData existing = bySlot.get("slot_" + index);
        if (existing != null) remove(existing.customId);
        SlotData data = new SlotData(index, customId, displayName, texture);
        put(data);
        return data;
    }

    /** Update a field atomically — replaces the SlotData in both maps. */
    public static synchronized void update(String customId, java.util.function.UnaryOperator<SlotData> mutator) {
        SlotData old = byId.get(customId);
        if (old == null) return;
        SlotData updated = mutator.apply(old);
        put(updated);
    }

    /** Replace a slot data entirely (used for undo/redo restore). */
    public static synchronized boolean restoreSnapshot(SlotData snapshot, boolean wasDeleted) {
        if (wasDeleted) {
            // Re-create: check slot is free
            SlotData occupant = bySlot.get("slot_" + snapshot.index);
            if (occupant != null && !occupant.customId.equals(snapshot.customId)) return false;
        }
        put(snapshot.deepCopy());
        return true;
    }

    /** Remove a block by ID. */
    public static synchronized SlotData remove(String customId) {
        SlotData data = byId.remove(customId);
        if (data != null) bySlot.remove("slot_" + data.index);
        return data;
    }

    /** Clear all blocks. */
    public static synchronized void clearAll() {
        byId.clear();
        bySlot.clear();
    }

    // ── Convenience mutation methods ─────────────────────────────────────────

    public static void updateTexture(String id, byte[] tex)       { update(id, d -> d.withTexture(tex)); }
    public static void rename(String id, String name)              { update(id, d -> d.withDisplayName(name)); }
    public static void setLightLevel(String id, int level)         { update(id, d -> d.withLightLevel(level)); }
    public static void setHardness(String id, float h)             { update(id, d -> d.withHardness(h)); }
    public static void setSoundType(String id, String sound)       { update(id, d -> d.withSoundType(sound)); }
    public static void setAnimMeta(String id, String meta)         { update(id, d -> d.withAnimMeta(meta)); }
    public static void setCollision(String id, boolean collision)  { update(id, d -> d.withNoCollision(!collision)); }
    public static void setFaceTexture(String id, String face, byte[] tex) { update(id, d -> d.withFaceTexture(face, tex)); }
    public static void clearFaceTexture(String id, String face)    { update(id, d -> d.withoutFaceTexture(face)); }
    public static void clearAllFaces(String id)                    { update(id, SlotData::withClearedFaces); }
    public static void setShape(String id, List<SlotData.ShapeBox> boxes) { update(id, d -> d.withShapeBoxes(boxes)); }

    public static void setProperties(String id, int light, float hard, String sound) {
        update(id, d -> d.withProperties(light, hard, sound));
    }

    public static void addBox(String id, SlotData.ShapeBox box) {
        update(id, d -> {
            List<SlotData.ShapeBox> newBoxes = new ArrayList<>(d.shapeBoxes != null ? d.shapeBoxes : List.of());
            if (newBoxes.size() < 16) newBoxes.add(box);
            return d.withShapeBoxes(newBoxes);
        });
    }

    public static void removeBox(String id, int boxIndex) {
        update(id, d -> {
            if (d.shapeBoxes == null || boxIndex < 0 || boxIndex >= d.shapeBoxes.size()) return d;
            List<SlotData.ShapeBox> newBoxes = new ArrayList<>(d.shapeBoxes);
            newBoxes.remove(boxIndex);
            return d.withShapeBoxes(newBoxes.isEmpty() ? null : newBoxes);
        });
    }

    /**
     * Re-ID a block: change its customId while keeping the same slot index.
     */
    public static synchronized boolean reId(String oldId, String newId) {
        SlotData data = byId.get(oldId);
        if (data == null || byId.containsKey(newId)) return false;
        byId.remove(oldId);
        SlotData updated = data.withCustomId(newId);
        put(updated);
        return true;
    }

    // ── Tab icon ─────────────────────────────────────────────────────────────

    public static byte[] getTabIconTexture()             { return tabIconTexture; }
    public static void setTabIconTexture(byte[] texture) { tabIconTexture = texture; }

    // ── Persistence ──────────────────────────────────────────────────────────

    public static void loadAll() {
        Path dir = Path.of(DATA_DIR);
        Path file = dir.resolve(DATA_FILE);
        try {
            Files.createDirectories(dir);
            if (!Files.exists(file)) {
                LOGGER.info("[CustomBlocks] No slot data file found, starting fresh.");
                return;
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // Tab icon
            if (root.has("tabIconTexture")) {
                String b64 = root.get("tabIconTexture").getAsString();
                if (!b64.isEmpty()) tabIconTexture = Base64.getDecoder().decode(b64);
            }

            // Slots
            if (root.has("slots")) {
                JsonArray arr = root.getAsJsonArray("slots");
                synchronized (SlotManager.class) {
                    byId.clear();
                    bySlot.clear();
                    for (JsonElement el : arr) {
                        try {
                            SlotData data = deserializeSlot(el.getAsJsonObject());
                            put(data);
                        } catch (Exception e) {
                            LOGGER.warn("[CustomBlocks] Failed to load slot entry: {}", e.getMessage());
                        }
                    }
                }
            }

            LOGGER.info("[CustomBlocks] Loaded {} slots.", byId.size());
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load slot data", e);
        }
    }

    public static void saveAll() {
        Path dir = Path.of(DATA_DIR);
        Path file = dir.resolve(DATA_FILE);
        try {
            Files.createDirectories(dir);
            JsonObject root = new JsonObject();

            // Tab icon
            if (tabIconTexture != null)
                root.addProperty("tabIconTexture", Base64.getEncoder().encodeToString(tabIconTexture));

            // Slots
            JsonArray arr = new JsonArray();
            for (SlotData data : sortedSlots()) {
                arr.add(serializeSlot(data));
            }
            // Include tab_icon if present
            SlotData tabData = byId.get("tab_icon");
            if (tabData != null) arr.add(serializeSlot(tabData));

            root.add("slots", arr);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
            ResourcePackServer.updatePack();
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to save slot data", e);
        }
    }

    /** Client-side: save to the .minecraft directory. */
    public static void saveToClientDir(File mcDir) {
        Path dir = mcDir.toPath().resolve("customblocks_data");
        Path file = dir.resolve(DATA_FILE);
        try {
            Files.createDirectories(dir);
            JsonObject root = new JsonObject();
            if (tabIconTexture != null)
                root.addProperty("tabIconTexture", Base64.getEncoder().encodeToString(tabIconTexture));
            JsonArray arr = new JsonArray();
            for (SlotData data : byId.values()) arr.add(serializeSlot(data));
            root.add("slots", arr);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to save client data", e);
        }
    }

    /** Client-side: load from .minecraft directory. */
    public static void loadFromClientDir(File mcDir) {
        Path dir = mcDir.toPath().resolve("customblocks_data");
        Path file = dir.resolve(DATA_FILE);
        try {
            if (!Files.exists(file)) return;
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("tabIconTexture")) {
                String b64 = root.get("tabIconTexture").getAsString();
                if (!b64.isEmpty()) tabIconTexture = Base64.getDecoder().decode(b64);
            }
            if (root.has("slots")) {
                synchronized (SlotManager.class) {
                    byId.clear();
                    bySlot.clear();
                    for (JsonElement el : root.getAsJsonArray("slots")) {
                        try {
                            SlotData data = deserializeSlot(el.getAsJsonObject());
                            put(data);
                        } catch (Exception e) {
                            LOGGER.warn("[CustomBlocks] Client: failed to load slot: {}", e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load client data", e);
        }
    }

    // ── Serialization ────────────────────────────────────────────────────────

    private static JsonObject serializeSlot(SlotData d) {
        JsonObject obj = new JsonObject();
        obj.addProperty("index", d.index);
        obj.addProperty("customId", d.customId);
        obj.addProperty("displayName", d.displayName);
        if (d.texture != null)
            obj.addProperty("texture", Base64.getEncoder().encodeToString(d.texture));
        obj.addProperty("lightLevel", d.lightLevel);
        obj.addProperty("hardness", d.hardness);
        obj.addProperty("soundType", d.soundType);
        if (d.animMeta != null)
            obj.addProperty("animMeta", d.animMeta);
        if (d.noCollision)
            obj.addProperty("noCollision", true);

        // Face textures
        if (d.hasFaces()) {
            JsonObject faces = new JsonObject();
            d.faceTextures.forEach((k, v) -> faces.addProperty(k, Base64.getEncoder().encodeToString(v)));
            obj.add("faceTextures", faces);
        }

        // Shape boxes
        if (d.isShaped()) {
            JsonArray boxes = new JsonArray();
            for (SlotData.ShapeBox box : d.shapeBoxes) boxes.add(box.toSerialString());
            obj.add("shapeBoxes", boxes);
        }

        return obj;
    }

    private static SlotData deserializeSlot(JsonObject obj) {
        int index         = obj.get("index").getAsInt();
        String customId   = obj.get("customId").getAsString();
        String displayName= obj.has("displayName") ? obj.get("displayName").getAsString() : customId;
        byte[] texture    = null;
        if (obj.has("texture")) {
            String b64 = obj.get("texture").getAsString();
            if (!b64.isEmpty()) texture = Base64.getDecoder().decode(b64);
        }
        int lightLevel    = obj.has("lightLevel") ? obj.get("lightLevel").getAsInt() : 0;
        float hardness    = obj.has("hardness") ? obj.get("hardness").getAsFloat() : 1.5f;
        String soundType  = obj.has("soundType") ? obj.get("soundType").getAsString() : "stone";
        String animMeta   = obj.has("animMeta") ? obj.get("animMeta").getAsString() : null;
        boolean noCol     = obj.has("noCollision") && obj.get("noCollision").getAsBoolean();

        // Face textures
        Map<String, byte[]> faceTextures = null;
        if (obj.has("faceTextures")) {
            faceTextures = new ConcurrentHashMap<>();
            JsonObject faces = obj.getAsJsonObject("faceTextures");
            for (var entry : faces.entrySet()) {
                faceTextures.put(entry.getKey(), Base64.getDecoder().decode(entry.getValue().getAsString()));
            }
        }

        // Shape boxes
        List<SlotData.ShapeBox> shapeBoxes = null;
        if (obj.has("shapeBoxes")) {
            shapeBoxes = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("shapeBoxes")) {
                try { shapeBoxes.add(SlotData.ShapeBox.parse(el.getAsString())); }
                catch (Exception ignored) {}
            }
            if (shapeBoxes.isEmpty()) shapeBoxes = null;
        }

        return new SlotData(index, customId, displayName, texture,
                lightLevel, hardness, soundType, faceTextures, animMeta, shapeBoxes, noCol);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static void put(SlotData data) {
        byId.put(data.customId, data);
        bySlot.put("slot_" + data.index, data);
    }

    private static int findFreeSlot() {
        int max = maxSlots();
        for (int i = 0; i < max; i++) {
            if (!bySlot.containsKey("slot_" + i)) return i;
        }
        return -1;
    }
}
