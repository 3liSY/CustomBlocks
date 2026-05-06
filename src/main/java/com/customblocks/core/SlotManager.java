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

import java.util.stream.Stream;



/**

 * Thread-safe slot manager using immutable {@link SlotData} values.

 */

public final class SlotManager {



    /** Atomic container for the entire custom-block database. */

    public record Snapshot(List<SlotData> slots, byte[] tabIcon) {}



    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final java.util.concurrent.ExecutorService IO_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "CustomBlocks-IO"));

    // ── Debounced save ────────────────────────────────────────────────────────
    private static volatile long lastDirtyTime = 0;
    private static volatile boolean dirty = false;
    private static final java.util.concurrent.ScheduledExecutorService SAVE_SCHEDULER =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CustomBlocks-SaveScheduler");
            t.setDaemon(true);
            return t;
        });

    private static final String DATA_DIR  = "config/customblocks";

    private static final String DATA_FILE = "slots.json";

    private static final String TEXTURES_DIR = DATA_DIR + "/textures";



    // ── Storage ──────────────────────────────────────────────────────────────



    private static final ConcurrentHashMap<String, SlotData> byId   = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, SlotData> bySlot = new ConcurrentHashMap<>();

    /** Free slot indices for O(log n) findFreeSlot(). Maintained by put()/remove()/loadAll(). */
    private static final TreeSet<Integer> freeSlotIndices = new TreeSet<>();

    /** Cached sorted slot list — invalidated on put()/remove(). */
    private static volatile List<SlotData> cachedSortedSlots = null;

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

        List<SlotData> cached = cachedSortedSlots;

        if (cached != null) return cached;

        cached = byId.values().stream()

                .filter(d -> !"tab_icon".equals(d.customId))

                .sorted(Comparator.comparing(d -> d.displayNameLower))

                .collect(Collectors.toList());

        cachedSortedSlots = cached;

        return cached;

    }



    /** @return All slots that are currently considered broken. */

    public static java.util.List<SlotData> brokenBlocks() {

        return byId.values().stream()

                .filter(d -> !"tab_icon".equals(d.customId))

                .filter(d -> d.isBroken || d.texture == null || d.texture.length <= 4)

                .collect(Collectors.toList());

    }



    /**

     * Captures an atomic, immutable snapshot of the current state.

     * This is the Royal Architect fix for 'Griefing' — prevents desync during ZIP generation.

     */

    public static synchronized Snapshot getSnapshot() {

        return new Snapshot(new ArrayList<>(byId.values()), tabIconTexture);

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

        SlotData data = SlotData.createTrusted(idx, customId, displayName, texture);

        put(data);

        // Phase 1: write texture to individual file
        if (data.texture != null && data.texture.length > 0) {
            final int slotIdx = data.index;
            final byte[] texCopy = data.texture.clone();
            IO_EXECUTOR.submit(() -> writeTextureFile(slotIdx, texCopy));
        }

        // Auto-categorize: run rules; if no rule fires, fall back to default category
        try {
            String applied = com.customblocks.core.AutoCategorizeManager.applyRulesTo(customId);
            if (applied == null) {
                for (com.customblocks.core.Category c : com.customblocks.core.CategoryManager.getAllCategories()) {
                    if (c.isDefault()) {
                        com.customblocks.core.CategoryManager.assignBlock(customId, c.key());
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            // Never let categorization break block creation
            LOGGER.warn("[CustomBlocks] Auto-categorize failed for '{}': {}", customId, t.getMessage());
        }

        return data;

    }



    /**

     * Assign at a specific index (used for sync from server).

     */

    public static synchronized SlotData assignAtIndex(int index, String customId, String displayName, byte[] texture) {

        SlotData existing = bySlot.get("slot_" + index);

        if (existing != null) remove(existing.customId);

        SlotData data = SlotData.createTrusted(index, customId, displayName, texture);

        put(data);

        // Write texture to .dat file (same as assign()) — prevents loss on crash
        if (data.texture != null && data.texture.length > 0) {
            final int slotIdx = data.index;
            final byte[] texCopy = data.texture.clone();
            IO_EXECUTOR.submit(() -> writeTextureFile(slotIdx, texCopy));
        }

        return data;

    }



    /** Update a field atomically — replaces the SlotData in both maps. */

    public static synchronized void update(String customId, java.util.function.UnaryOperator<SlotData> mutator) {

        SlotData old = byId.get(customId);

        if (old == null) return;

        SlotData updated = mutator.apply(old);

        put(updated);

        // Phase 1: detect texture changes and write to file
        if (old.texture != updated.texture) {
            final int slotIdx = updated.index;
            final byte[] texCopy = updated.texture != null ? updated.texture.clone() : null;
            IO_EXECUTOR.submit(() -> writeTextureFile(slotIdx, texCopy));
        }
        for (String face : SlotData.FACE_KEYS) {
            byte[] oldFace = old.faceTextures.get(face);
            byte[] newFace = updated.faceTextures.get(face);
            if (oldFace != newFace) {
                final int slotIdx = updated.index;
                final String faceName = face;
                final byte[] faceCopy = newFace != null ? newFace.clone() : null;
                IO_EXECUTOR.submit(() -> writeFaceTextureFile(slotIdx, faceName, faceCopy));
            }
        }
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

        if (data != null) {
            bySlot.remove("slot_" + data.index);
            if (data.index < maxSlots()) freeSlotIndices.add(data.index);
            cachedSortedSlots = null;
            // Phase 1: clean up texture files
            final int slotIdx = data.index;
            IO_EXECUTOR.submit(() -> deleteTextureFiles(slotIdx));
            CategoryManager.clearAssignments(customId);
        }

        return data;

    }



    /** Clear all blocks. */

    public static synchronized void clearAll() {

        byId.clear();

        bySlot.clear();

    }



    // ── Convenience mutation methods ─────────────────────────────────────────



    /**
     * Update the default texture. If the new texture is a vertical frame strip
     * (height > width, height divisible by width) AND the current animMeta is
     * null or empty, auto-populate a default animMeta so the block animates
     * even when a real animMeta packet has not arrived yet or was lost.
     *
     * <p>Never overwrites a non-empty animMeta - real per-frame timing always
     * wins. This is strictly a safety net for the "strip texture exists but
     * animMeta is null" class of bugs (e.g. legacy save data, dropped packets).
     */
    public static void updateTexture(String id, byte[] tex) {
        update(id, d -> {
            SlotData updated = d.withTexture(tex);
            if (updated.animMeta == null || updated.animMeta.isEmpty()) {
                int frames = com.customblocks.ImageProcessor.getVerticalFrames(tex);
                if (frames > 1) {
                    updated = updated.withAnimMeta(
                        com.customblocks.ImageProcessor.synthesizeDefaultMcmeta(frames));
                }
            }
            return updated;
        });
    }

    public static void rename(String id, String name)              { update(id, d -> d.withDisplayName(name)); }

    public static void setLightLevel(String id, int level)         { update(id, d -> d.withLightLevel(level)); }

    public static void setHardness(String id, float h)             { update(id, d -> d.withHardness(h)); }

    public static void setSoundType(String id, String sound)       { update(id, d -> d.withSoundType(sound)); }

    public static void setAnimMeta(String id, String meta)         { update(id, d -> d.withAnimMeta(meta)); }

    public static void setCollision(String id, boolean collision)  { update(id, d -> d.withNoCollision(!collision)); }

    public static void setFaceTexture(String id, String face, byte[] tex) {
        // Validation: reject null/empty inputs to prevent downstream crashes
        if (id == null || id.isEmpty()) {
            LOGGER.warn("[CustomBlocks] setFaceTexture rejected: null/empty id");
            return;
        }
        if (face == null || face.isEmpty()) {
            LOGGER.warn("[CustomBlocks] setFaceTexture rejected: null/empty face for id '{}'", id);
            return;
        }
        if (tex == null || tex.length == 0) {
            LOGGER.warn("[CustomBlocks] setFaceTexture rejected: null/empty texture for id '{}' face '{}'", id, face);
            return;
        }
        update(id, d -> d.withFaceTexture(face, tex));
    }

    public static void clearFaceTexture(String id, String face)    { update(id, d -> d.withoutFaceTexture(face)); }

    public static void clearAllFaces(String id)                    { update(id, SlotData::withClearedFaces); }

    public static void setShape(String id, List<SlotData.ShapeBox> boxes) {
        SlotData old = byId.get(id);
        update(id, d -> d.withShapeBoxes(boxes));
        if (old != null) com.customblocks.block.SlotBlock.invalidateShape(old.index);
    }



    public static void setProperties(String id, int light, float hard, String sound) {

        update(id, d -> d.withProperties(light, hard, sound));

    }



    public static void addBox(String id, SlotData.ShapeBox box) {

        SlotData old = byId.get(id);

        update(id, d -> {

            List<SlotData.ShapeBox> newBoxes = new ArrayList<>(d.shapeBoxes != null ? d.shapeBoxes : List.of());

            if (newBoxes.size() < 16) newBoxes.add(box);

            return d.withShapeBoxes(newBoxes);

        });

        if (old != null) com.customblocks.block.SlotBlock.invalidateShape(old.index);

    }



    public static void removeBox(String id, int boxIndex) {

        SlotData old = byId.get(id);

        update(id, d -> {

            if (d.shapeBoxes == null || boxIndex < 0 || boxIndex >= d.shapeBoxes.size()) return d;

            List<SlotData.ShapeBox> newBoxes = new ArrayList<>(d.shapeBoxes);

            newBoxes.remove(boxIndex);

            return d.withShapeBoxes(newBoxes.isEmpty() ? null : newBoxes);

        });

        if (old != null) com.customblocks.block.SlotBlock.invalidateShape(old.index);

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

        java.util.Set<String> cats = CategoryManager.getCategoriesForBlock(oldId);
        if (!cats.isEmpty()) {
            for (String cat : cats) {
                CategoryManager.assignBlock(newId, cat);
            }
            CategoryManager.clearAssignments(oldId);
        }

        return true;

    }



    // ── Tab icon ─────────────────────────────────────────────────────────────



    public static byte[] getTabIconTexture()             { return tabIconTexture; }

    public static void setTabIconTexture(byte[] texture) { tabIconTexture = texture; }



    // ── Persistence ──────────────────────────────────────────────────────────



    /** 
     * Extracts rotating backup logic into a generic utility for any JSON config file.
     * TRD § 5.7 Comprehensive Backups
     */
    public static void rotateBackups(Path file) {
        try {
            String base = file.getFileName().toString().replace(".json", "");
            Path dir = file.getParent();
            Path bak3 = dir.resolve(base + ".bak3.json");
            Path bak2 = dir.resolve(base + ".bak2.json");
            Path bak1 = dir.resolve(base + ".bak1.json");

            if (Files.exists(bak2)) Files.move(bak2, bak3, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(bak1)) Files.move(bak1, bak2, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(file)) Files.copy(file, bak1, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[CustomBlocks] Backup saved to {}", bak1.getFileName());
        } catch (Exception bakEx) {
            LOGGER.warn("[CustomBlocks] Could not create backup: {}", bakEx.getMessage());
        }
    }

    public static void loadAll() {
        Path dir = Path.of(DATA_DIR);
        Path file = dir.resolve(DATA_FILE);
        Path bak1 = dir.resolve("slots.bak1.json");

        try {
            Files.createDirectories(dir);

            if (!Files.exists(file)) {
                if (Files.exists(bak1)) {
                    LOGGER.warn("[CustomBlocks] Primary file slots.json missing! Auto-restoring from backup.");
                    Files.copy(bak1, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    LOGGER.info("[CustomBlocks] No slot data file found, starting fresh.");
                    // Layer 1: In-Memory State Materialization (Zero-Defect Fix)
                    synchronized (SlotManager.class) {
                        byId.clear();
                        bySlot.clear();
                        rebuildFreeSlotSet();
                    }
                    // Layer 2: Global Directory & File Auto-Generation (Atomic Operations)
                    try {
                        Path tmpSlots = dir.resolve("slots.json.tmp");
                        Files.writeString(tmpSlots, "{ \"slots\": [] }", StandardCharsets.UTF_8);
                        Files.move(tmpSlots, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                        Path catFile = dir.resolve("categories.json");
                        if (!Files.exists(catFile)) {
                            Path tmpCat = dir.resolve("categories.json.tmp");
                            Files.writeString(tmpCat, "{ \"categories\": [], \"assignments\": {} }", StandardCharsets.UTF_8);
                            Files.move(tmpCat, catFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception e) {
                        LOGGER.error("[CustomBlocks] Failed to generate fresh config files", e);
                    }
                    return;
                }
            }

            // ── Load & Parse with Layer 3 Backup Fallback ─────────────────────
            JsonObject root = null;
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                root = JsonParser.parseString(json).getAsJsonObject();
            } catch (Exception parseEx) {
                LOGGER.error("[CustomBlocks] slots.json is corrupted! Attempting auto-restore from .bak1...", parseEx);
                if (Files.exists(bak1)) {
                    Files.copy(bak1, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    root = JsonParser.parseString(json).getAsJsonObject();
                    LOGGER.info("[CustomBlocks] Successfully restored and parsed from .bak1!");
                } else {
                    throw new RuntimeException("Corrupted slots.json and no backup available!");
                }
            }

            rotateBackups(file);



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

                    // ── Duplicate slot index repair ─────────────────────
                    {
                        // indexToKeeper: slot index → first customId that claimed it
                        Map<Integer, String> indexToKeeper = new HashMap<>();
                        // Each entry: [0]=duplicate ID to reassign, [1]=keeper ID that stays
                        List<String[]> toReassign = new ArrayList<>();
                        for (SlotData d : new ArrayList<>(byId.values())) {
                            String existing = indexToKeeper.putIfAbsent(d.index, d.customId);
                            if (existing != null) {
                                LOGGER.warn("[CustomBlocks] Duplicate slot index {} claimed by '{}' and '{}'. Will reassign '{}'.",
                                        d.index, existing, d.customId, d.customId);
                                toReassign.add(new String[]{d.customId, existing});
                            }
                        }
                        for (String[] pair : toReassign) {
                            SlotData d = byId.get(pair[0]);
                            if (d == null) continue;
                            int oldIdx = d.index;
                            // Remove the duplicate from both maps
                            byId.remove(pair[0]);
                            bySlot.remove("slot_" + oldIdx);
                            // Restore the keeper's bySlot entry so findFreeSlot() won't reuse it
                            SlotData keeper = byId.get(pair[1]);
                            if (keeper != null) bySlot.put("slot_" + oldIdx, keeper);
                            int newIdx = findFreeSlot();
                            if (newIdx >= 0) {
                                put(d.withIndex(newIdx));
                                LOGGER.info("[CustomBlocks] Reassigned '{}' from slot {} → slot {}", pair[0], oldIdx, newIdx);
                            } else {
                                LOGGER.error("[CustomBlocks] No free slot for '{}' — block dropped!", pair[0]);
                            }
                        }
                        if (!toReassign.isEmpty()) {
                            LOGGER.info("[CustomBlocks] {} duplicate(s) repaired. Saving corrected data.", toReassign.size());
                            saveAll();  // trigger debounced save to persist the fix
                        }
                    }

                    // Phase 1: load textures from individual .dat files
                    Path texDir = Path.of(TEXTURES_DIR);
                    if (Files.exists(texDir)) {
                        int texLoaded = 0, faceLoaded = 0;
                        for (SlotData d : new ArrayList<>(byId.values())) {
                            // Load main texture from file if not already present from legacy Base64
                            if (d.texture == null || d.texture.length == 0) {
                                byte[] tex = readTextureFile(d.index);
                                if (tex != null) {
                                    put(d.withTexture(tex));
                                    texLoaded++;
                                    d = byId.get(d.customId); // re-fetch after put
                                }
                            }
                            // Load face textures from files
                            for (String face : SlotData.FACE_KEYS) {
                                if (!d.faceTextures.containsKey(face)) {
                                    byte[] faceTex = readFaceTextureFile(d.index, face);
                                    if (faceTex != null) {
                                        put(d.withFaceTexture(face, faceTex));
                                        faceLoaded++;
                                        d = byId.get(d.customId); // re-fetch after put
                                    }
                                }
                            }
                        }
                        if (texLoaded > 0 || faceLoaded > 0) {
                            LOGGER.info("[CustomBlocks] Loaded {} textures and {} face textures from files.", texLoaded, faceLoaded);
                        }
                    }

                    // Phase 1: one-time migration — if textures dir doesn't exist but slots have textures,
                    // write all textures to individual files
                    if (!Files.exists(texDir)) {
                        int migrated = 0;
                        for (SlotData d : byId.values()) {
                            if (d.texture != null && d.texture.length > 0) {
                                writeTextureFile(d.index, d.texture);
                                migrated++;
                            }
                            for (var face : d.faceTextures.entrySet()) {
                                writeFaceTextureFile(d.index, face.getKey(), face.getValue());
                            }
                        }
                        if (migrated > 0) {
                            LOGGER.info("[CustomBlocks] Migration complete: wrote {} texture files to {}.", migrated, texDir);
                        }
                    }

                    rebuildFreeSlotSet();

                }

            }



            LOGGER.info("[CustomBlocks] Loaded {} slots ({} free).", byId.size(), freeSlotIndices.size());

        } catch (Exception e) {

            LOGGER.error("[CustomBlocks] Failed to load slot data", e);

            com.customblocks.gui.GuiManager.logError();

        }

    }



    public static void saveAll() {
        markDirty();
    }

    /** Marks data as dirty. Actual save is debounced to avoid thrashing. */
    private static void markDirty() {
        dirty = true;
        lastDirtyTime = System.currentTimeMillis();
        long debounce = CustomBlocksConfig.reloadDebounceMs;
        SAVE_SCHEDULER.schedule(() -> {
            if (dirty && (System.currentTimeMillis() - lastDirtyTime) >= (debounce - 200)) {
                dirty = false;
                saveAllAsync();
            }
        }, debounce, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Force immediate save — called on shutdown and /cb reload. */
    public static void flushSave() {
        if (dirty) {
            dirty = false;
            saveAllAsync();
        }
    }



    public static void saveAllAsync() {

        // Capture a snapshot of the current state immediately (Snapshot Atomicity)

        Snapshot snapshot = getSnapshot();



        IO_EXECUTOR.submit(() -> {

            Path dir = Path.of(DATA_DIR);

            Path file = dir.resolve(DATA_FILE);

            try {

                Files.createDirectories(dir);



                // ── Safety check: prevent catastrophic texture loss ──────────
                // Fix 3: Uses a lightweight textured_count.txt instead of parsing
                // the entire 50MB slots.json. Zero memory allocation.
                int newTextured = 0;
                for (SlotData d : snapshot.slots) {
                    if (!"tab_icon".equals(d.customId) && d.texture != null && d.texture.length > 0)
                        newTextured++;
                }

                Path countFile = dir.resolve("textured_count.txt");
                int diskTextured = -1;
                if (Files.exists(countFile)) {
                    try {
                        diskTextured = Integer.parseInt(Files.readString(countFile, StandardCharsets.UTF_8).trim());
                    } catch (Exception ignored) { /* will fallback to skip check */ }
                }

                if (diskTextured >= 0) {
                    int lost = diskTextured - newTextured;
                    if (lost > 10) {
                        // Create a backup and REFUSE to overwrite
                        Path bakFile = dir.resolve(DATA_FILE + ".bak");
                        if (!Files.exists(bakFile)) {
                            try { Files.copy(file, bakFile); } catch (Exception ignored) {}
                            LOGGER.warn("[CustomBlocks] Created backup at {} (disk had {} textured, memory has {})",
                                    bakFile, diskTextured, newTextured);
                        }
                        LOGGER.error("[CustomBlocks] SAVE ABORTED: {} slots would lose textures ({} on disk → {} in memory). " +
                                "This is a safety check to prevent data loss. A .bak file has been preserved.",
                                lost, diskTextured, newTextured);
                        return;
                    }
                }



                // ── Streaming JSON writer: writes one slot at a time ──────
                // Instead of building a 200MB+ JSON tree in memory, we stream
                // each slot directly to disk. Peak memory: ~3MB vs 200-400MB.
                Path tempFile = dir.resolve(DATA_FILE + ".tmp");

                try (com.google.gson.stream.JsonWriter writer = new com.google.gson.stream.JsonWriter(
                        new BufferedWriter(new OutputStreamWriter(
                                new FileOutputStream(tempFile.toFile()), StandardCharsets.UTF_8)))) {
                    writer.setIndent("  ");
                    writer.beginObject();

                    // Tab icon
                    if (snapshot.tabIcon != null) {
                        writer.name("tabIconTexture").value(
                                Base64.getEncoder().encodeToString(snapshot.tabIcon));
                    }

                    // Slots — written one at a time, each GC'd after write
                    writer.name("slots");
                    writer.beginArray();
                    for (SlotData data : snapshot.slots) {
                        if ("tab_icon".equals(data.customId)) continue;
                        GSON.toJson(serializeSlot(data), writer);
                    }
                    writer.endArray();

                    writer.endObject();
                }

                Files.move(tempFile, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // Fix 3: Update textured_count.txt for next save's safety check
                try {
                    Files.writeString(countFile, String.valueOf(newTextured), StandardCharsets.UTF_8);
                } catch (Exception ignored) { /* non-critical */ }

                // Pass the EXACT same snapshot to the Resource Pack generator

                ResourcePackServer.updatePackWithSnapshot(snapshot);

            } catch (Exception e) {

                LOGGER.error("[CustomBlocks] Failed to save slot data asynchronously", e);

            }

        });

    }



    /** Client-side: save to the .minecraft directory. */

    public static void saveToClientDir(File mcDir) {

        Path dir = mcDir.toPath().resolve("customblocks_data");

        Path file = dir.resolve(DATA_FILE);

        try {

            Files.createDirectories(dir);

            Path tmpFile = dir.resolve(DATA_FILE + ".tmp");
            // Streaming JSON writer — same as saveAllAsync(), avoids 200MB+ memory spike
            try (com.google.gson.stream.JsonWriter writer = new com.google.gson.stream.JsonWriter(
                    new BufferedWriter(new OutputStreamWriter(
                            new FileOutputStream(tmpFile.toFile()), StandardCharsets.UTF_8)))) {
                writer.setIndent("  ");
                writer.beginObject();

                if (tabIconTexture != null) {
                    writer.name("tabIconTexture").value(
                            Base64.getEncoder().encodeToString(tabIconTexture));
                }

                writer.name("slots");
                writer.beginArray();
                for (SlotData data : byId.values()) {
                    GSON.toJson(serializeSlotWithTextures(data), writer);
                }
                writer.endArray();

                writer.endObject();
            }
            // Layered Defense: Atomic rename prevents corrupted slots.json if crash occurs mid-write
            Files.move(tmpFile, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

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

        // Phase 1: textures are stored as separate .dat files, not inline Base64
        obj.addProperty("lightLevel", d.lightLevel);

        obj.addProperty("hardness", d.hardness);

        obj.addProperty("soundType", d.soundType);

        if (d.animMeta != null)

            obj.addProperty("animMeta", d.animMeta);

        if (d.noCollision)

            obj.addProperty("noCollision", true);



        // Phase 1: face textures are stored as separate .dat files, not inline Base64
        // We still record WHICH faces have overrides (keys only) so loadAll() knows to look for files
        if (d.hasFaces()) {
            JsonArray faceKeys = new JsonArray();
            d.faceTextures.keySet().forEach(faceKeys::add);
            obj.add("faceKeys", faceKeys);
        }



        // Shape boxes

        if (d.isShaped()) {

            JsonArray boxes = new JsonArray();

            for (SlotData.ShapeBox box : d.shapeBoxes) boxes.add(box.toSerialString());

            obj.add("shapeBoxes", boxes);

        }



        return obj;

    }

    /**
     * Client-side serialization — includes textures as inline Base64.
     * The client has no texture files directory, so it needs textures in the JSON
     * for cache hash computation and offline access.
     */
    private static JsonObject serializeSlotWithTextures(SlotData d) {
        JsonObject obj = serializeSlot(d);
        // Add texture as Base64
        if (d.texture != null && d.texture.length > 0) {
            obj.addProperty("texture", Base64.getEncoder().encodeToString(d.texture));
        }
        // Add face textures as Base64
        if (d.hasFaces()) {
            obj.remove("faceKeys"); // remove keys-only format
            JsonObject faces = new JsonObject();
            d.faceTextures.forEach((k, v) -> faces.addProperty(k, Base64.getEncoder().encodeToString(v)));
            obj.add("faceTextures", faces);
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



        return SlotData.createTrustedFull(index, customId, displayName, texture,

                lightLevel, hardness, soundType, faceTextures, animMeta, shapeBoxes, noCol);

    }



    // ── Phase 3: Server-side texture hash ──────────────────────────────────

    private static volatile String cachedTextureHash = null;

    /**
     * Compute a SHA-256 hash of all slot IDs + texture bytes + animMeta + face textures.
     * Same algorithm as the client-side computeTextureHash() in CustomBlocksClient.
     * Result is cached and invalidated on any slot mutation.
     */
    public static String computeTextureHash() {
        String cached = cachedTextureHash;
        if (cached != null) return cached;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            // Sort by index for deterministic order — ConcurrentHashMap iteration
            // order varies between JVMs, so client and server must both sort.
            java.util.List<SlotData> sorted = new java.util.ArrayList<>(allSlots());
            sorted.sort(java.util.Comparator.comparingInt(d -> d.index));
            for (SlotData data : sorted) {
                md.update(data.customId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (data.texture != null) md.update(data.texture);
                if (data.animMeta != null) md.update(data.animMeta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                // Sort face keys for deterministic order (also ConcurrentHashMap)
                java.util.List<String> faceKeys = new java.util.ArrayList<>(data.faceTextures.keySet());
                java.util.Collections.sort(faceKeys);
                for (String faceKey : faceKeys) {
                    md.update(faceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    md.update(data.faceTextures.get(faceKey));
                }
            }
            cached = java.util.HexFormat.of().formatHex(md.digest());
            cachedTextureHash = cached;
            return cached;
        } catch (Exception e) {
            LOGGER.warn("[CustomBlocks] Server hash computation failed: {}", e.getMessage());
            return "";
        }
    }

    /** Invalidate cached hash — called whenever slots change. */
    private static void invalidateHash() { cachedTextureHash = null; }

    // ── Texture file I/O (Phase 1: separate texture files) ─────────────────

    /** Write a single texture file to disk. Called when a texture changes — NOT on every save. */
    private static void writeTextureFile(int slotIndex, byte[] data) {
        try {
            Path dir = Path.of(TEXTURES_DIR);
            Files.createDirectories(dir);
            if (data != null && data.length > 0) {
                Files.write(dir.resolve("slot_" + slotIndex + ".dat"), data);
            } else {
                Files.deleteIfExists(dir.resolve("slot_" + slotIndex + ".dat"));
            }
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to write texture for slot_{}", slotIndex, e);
        }
    }

    /** Write a face texture file. */
    private static void writeFaceTextureFile(int slotIndex, String face, byte[] data) {
        try {
            Path dir = Path.of(TEXTURES_DIR);
            Files.createDirectories(dir);
            if (data != null && data.length > 0) {
                Files.write(dir.resolve("slot_" + slotIndex + "_" + face + ".dat"), data);
            } else {
                Files.deleteIfExists(dir.resolve("slot_" + slotIndex + "_" + face + ".dat"));
            }
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to write face texture for slot_{}_{}", slotIndex, face, e);
        }
    }

    /** Delete all texture files for a slot. */
    private static void deleteTextureFiles(int slotIndex) {
        try {
            Path dir = Path.of(TEXTURES_DIR);
            Files.deleteIfExists(dir.resolve("slot_" + slotIndex + ".dat"));
            for (String face : SlotData.FACE_KEYS) {
                Files.deleteIfExists(dir.resolve("slot_" + slotIndex + "_" + face + ".dat"));
            }
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to delete textures for slot_{}", slotIndex, e);
        }
    }

    /** Read a texture file from disk. Returns null if not found. */
    private static byte[] readTextureFile(int slotIndex) {
        try {
            Path file = Path.of(TEXTURES_DIR, "slot_" + slotIndex + ".dat");
            return Files.exists(file) ? Files.readAllBytes(file) : null;
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to read texture for slot_{}", slotIndex, e);
            return null;
        }
    }

    /** Read a face texture file from disk. Returns null if not found. */
    private static byte[] readFaceTextureFile(int slotIndex, String face) {
        try {
            Path file = Path.of(TEXTURES_DIR, "slot_" + slotIndex + "_" + face + ".dat");
            return Files.exists(file) ? Files.readAllBytes(file) : null;
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to read face texture for slot_{}_{}", slotIndex, face, e);
            return null;
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────



    private static void put(SlotData data) {

        byId.put(data.customId, data);

        bySlot.put("slot_" + data.index, data);

        freeSlotIndices.remove(data.index);

        cachedSortedSlots = null;

        invalidateHash();

    }



    private static int findFreeSlot() {

        Integer first = freeSlotIndices.isEmpty() ? null : freeSlotIndices.first();

        return first != null ? first : -1;

    }

    /** Rebuild the freeSlotIndices set from scratch (called after loadAll clears maps). */
    private static void rebuildFreeSlotSet() {
        freeSlotIndices.clear();
        int max = maxSlots();
        for (int i = 0; i < max; i++) {
            if (!bySlot.containsKey("slot_" + i)) freeSlotIndices.add(i);
        }
    }

}

