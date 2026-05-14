package com.customblocks.core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase H1 — Per-block lock.
 * Locked blocks cannot be edited or deleted without first unlocking.
 * Persisted as {@code config/customblocks/locks.json}.
 */
public final class LockManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/Locks");
    private static final String DATA_DIR  = "config/customblocks";
    private static final String DATA_FILE = "locks.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Set of locked block IDs. */
    private static final Set<String> LOCKED = ConcurrentHashMap.newKeySet();

    // ── Query ────────────────────────────────────────────────────────────────

    public static boolean isLocked(String blockId) {
        return blockId != null && LOCKED.contains(blockId);
    }

    public static Set<String> lockedIds() {
        return Collections.unmodifiableSet(LOCKED);
    }

    // ── Mutation ─────────────────────────────────────────────────────────────

    /** Locks a block. Returns true if it was not already locked. */
    public static boolean lock(String blockId) {
        if (blockId == null) return false;
        boolean added = LOCKED.add(blockId);
        if (added) saveAsync();
        return added;
    }

    /** Unlocks a block. Returns true if it was locked. */
    public static boolean unlock(String blockId) {
        if (blockId == null) return false;
        boolean removed = LOCKED.remove(blockId);
        if (removed) saveAsync();
        return removed;
    }

    /** Called when a block is deleted — cleans up its lock entry. */
    public static void onBlockDeleted(String blockId) {
        if (LOCKED.remove(blockId)) saveAsync();
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public static void load() {
        Path file = Path.of(DATA_DIR, DATA_FILE);
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            LOCKED.clear();
            if (root.has("locked")) {
                for (JsonElement el : root.getAsJsonArray("locked")) {
                    String id = el.getAsString();
                    if (!id.isBlank()) LOCKED.add(id);
                }
            }
            LOGGER.info("[CustomBlocks] Loaded {} locked blocks.", LOCKED.size());
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load locks.json: {}", e.getMessage());
        }
    }

    private static void saveAsync() {
        Thread t = new Thread(LockManager::save, "CustomBlocks-LockSave");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void save() {
        try {
            Path dir  = Path.of(DATA_DIR);
            Path tmp  = dir.resolve(DATA_FILE + ".tmp");
            Path dest = dir.resolve(DATA_FILE);
            Files.createDirectories(dir);
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            LOCKED.forEach(arr::add);
            root.add("locked", arr);
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8))) {
                GSON.toJson(root, w);
            }
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("[CustomBlocks] Failed to save locks.json: {}", e.getMessage());
        }
    }

    private LockManager() {}
}
