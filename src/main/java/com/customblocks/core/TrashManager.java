package com.customblocks.core;

import com.customblocks.CustomBlocksConfig;
import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.*;

/**
 * V4-18 — Deleted-blocks trash bin.
 * When a block is deleted it moves here instead of disappearing.
 * Players can browse and restore entries via /cb deletedblocks.
 * Entries older than {@code CustomBlocksConfig.trashRetentionDays} are auto-purged on load.
 */
public final class TrashManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/Trash");
    private static final String TRASH_FILE = "config/customblocks/trash.json.gz";
    private static final Gson GSON = new GsonBuilder().create();

    public record TrashEntry(String originalId, String displayName, long deletedAt, SlotData data) {}

    private static final CopyOnWriteArrayList<TrashEntry> ENTRIES = new CopyOnWriteArrayList<>();

    // ── Init ─────────────────────────────────────────────────────────────────

    public static void load() {
        Path file = Path.of(TRASH_FILE);
        if (!Files.exists(file)) return;
        try (GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file)));
             InputStreamReader ir = new InputStreamReader(gz, java.nio.charset.StandardCharsets.UTF_8)) {
            JsonArray arr = JsonParser.parseReader(ir).getAsJsonArray();
            ENTRIES.clear();
            long cutoff = retentionCutoff();
            for (JsonElement el : arr) {
                try {
                    JsonObject obj = el.getAsJsonObject();
                    long ts = obj.has("deletedAt") ? obj.get("deletedAt").getAsLong() : 0L;
                    if (cutoff > 0 && ts > 0 && ts < cutoff) continue; // auto-purge old entries
                    SlotData d = deserialize(obj.getAsJsonObject("slotData"));
                    if (d == null) continue;
                    String oid = obj.has("originalId") ? obj.get("originalId").getAsString() : d.customId;
                    String dn  = obj.has("displayName") ? obj.get("displayName").getAsString() : d.displayName;
                    ENTRIES.add(new TrashEntry(oid, dn, ts, d));
                } catch (RuntimeException e) {
                    LOGGER.warn("[Trash] Skipping corrupt entry: {}", e.getMessage());
                }
            }
            LOGGER.info("[Trash] Loaded {} trash entries.", ENTRIES.size());
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("[Trash] Could not load {}: {}", TRASH_FILE, e.getMessage());
        }
    }

    private static long retentionCutoff() {
        int days = CustomBlocksConfig.trashRetentionDays > 0 ? CustomBlocksConfig.trashRetentionDays : 30;
        return Instant.now().toEpochMilli() - ((long) days * 86_400_000L);
    }

    // ── API ──────────────────────────────────────────────────────────────────

    /** Add a block to trash after deletion. Call right before SlotManager.remove(). */
    public static void addToTrash(SlotData data) {
        if (data == null) return;
        ENTRIES.add(0, new TrashEntry(data.customId, data.displayName, Instant.now().toEpochMilli(), data.deepCopy()));
        saveAsync();
    }

    /** Sorted newest-first. */
    public static List<TrashEntry> list() {
        return List.copyOf(ENTRIES);
    }

    public static int size() { return ENTRIES.size(); }

    /**
     * Restore a trash entry by original ID — assigns into a new slot.
     * Returns true on success.
     */
    public static boolean restore(String originalId) {
        TrashEntry entry = ENTRIES.stream()
            .filter(e -> e.originalId().equals(originalId))
            .findFirst().orElse(null);
        if (entry == null) return false;
        SlotData d = entry.data();
        // Assign into a fresh slot (ID must not already exist)
        String newId = d.customId;
        if (SlotManager.hasId(newId)) newId = newId + "_restored";
        boolean ok = SlotManager.assign(newId, d.displayName, d.texture) != null;
        if (ok) {
            ENTRIES.removeIf(e -> e.originalId().equals(originalId));
            saveAsync();
        }
        return ok;
    }

    /** Remove from trash permanently (no restore). */
    public static boolean permanentlyDelete(String originalId) {
        boolean removed = ENTRIES.removeIf(e -> e.originalId().equals(originalId));
        if (removed) saveAsync();
        return removed;
    }

    /** Remove all trash entries. */
    public static void clearTrash() {
        ENTRIES.clear();
        saveAsync();
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private static void saveAsync() {
        List<TrashEntry> snapshot = List.copyOf(ENTRIES);
        Thread.ofVirtual().start(() -> {
            try {
                Path file = Path.of(TRASH_FILE);
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                JsonArray arr = new JsonArray();
                for (TrashEntry e : snapshot) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("originalId", e.originalId());
                    obj.addProperty("displayName", e.displayName());
                    obj.addProperty("deletedAt", e.deletedAt());
                    obj.add("slotData", serialize(e.data()));
                    arr.add(obj);
                }
                try (GZIPOutputStream gz = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)));
                     OutputStreamWriter ow = new OutputStreamWriter(gz, java.nio.charset.StandardCharsets.UTF_8)) {
                    GSON.toJson(arr, ow);
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                LOGGER.warn("[Trash] Save failed: {}", e.getMessage());
            }
        });
    }

    // ── Serialization ────────────────────────────────────────────────────────

    private static JsonObject serialize(SlotData d) {
        JsonObject obj = new JsonObject();
        obj.addProperty("index", d.index);
        obj.addProperty("customId", d.customId);
        obj.addProperty("displayName", d.displayName);
        obj.addProperty("lightLevel", d.lightLevel);
        obj.addProperty("hardness", d.hardness);
        obj.addProperty("soundType", d.soundType);
        obj.addProperty("noCollision", d.noCollision);
        if (d.animMeta != null) obj.addProperty("animMeta", d.animMeta);
        if (d.texture != null && d.texture.length > 0)
            obj.addProperty("texture", Base64.getEncoder().encodeToString(d.texture));
        if (d.hasFaces()) {
            JsonObject faces = new JsonObject();
            d.faceTextures.forEach((k, v) -> faces.addProperty(k, Base64.getEncoder().encodeToString(v)));
            obj.add("faceTextures", faces);
        }
        if (d.isShaped()) {
            JsonArray boxes = new JsonArray();
            for (SlotData.ShapeBox b : d.shapeBoxes) boxes.add(b.toSerialString());
            obj.add("shapeBoxes", boxes);
        }
        return obj;
    }

    private static SlotData deserialize(JsonObject obj) {
        if (obj == null) return null;
        try {
            int index = obj.has("index") ? obj.get("index").getAsInt() : 0;
            String customId = obj.get("customId").getAsString();
            String displayName = obj.has("displayName") ? obj.get("displayName").getAsString() : customId;
            byte[] texture = null;
            if (obj.has("texture")) {
                String b64 = obj.get("texture").getAsString();
                if (!b64.isEmpty()) texture = Base64.getDecoder().decode(b64);
            }
            int lightLevel = obj.has("lightLevel") ? obj.get("lightLevel").getAsInt() : 0;
            float hardness = obj.has("hardness") ? obj.get("hardness").getAsFloat() : 1.5f;
            String soundType = obj.has("soundType") ? obj.get("soundType").getAsString() : "stone";
            boolean noCollision = obj.has("noCollision") && obj.get("noCollision").getAsBoolean();
            String animMeta = obj.has("animMeta") ? obj.get("animMeta").getAsString() : null;
            java.util.Map<String, byte[]> faceTextures = new java.util.HashMap<>();
            if (obj.has("faceTextures")) {
                JsonObject faces = obj.getAsJsonObject("faceTextures");
                for (Map.Entry<String, JsonElement> e : faces.entrySet())
                    faceTextures.put(e.getKey(), Base64.getDecoder().decode(e.getValue().getAsString()));
            }
            List<SlotData.ShapeBox> shapeBoxes = null;
            if (obj.has("shapeBoxes")) {
                shapeBoxes = new ArrayList<>();
                for (JsonElement el : obj.getAsJsonArray("shapeBoxes"))
                    shapeBoxes.add(SlotData.ShapeBox.parse(el.getAsString()));
            }
            return SlotData.createTrustedFull(index, customId, displayName, texture,
                lightLevel, hardness, soundType, faceTextures, animMeta, shapeBoxes, noCollision);
        } catch (Exception e) {
            LOGGER.warn("[Trash] deserialize failed: {}", e.getMessage());
            return null;
        }
    }

    private TrashManager() {}
}
