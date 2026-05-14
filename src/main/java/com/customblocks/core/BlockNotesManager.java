package com.customblocks.core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase H₁ — Per-block admin notes.
 * Persisted as {@code config/customblocks/notes.json}.
 * Each entry is blockId → note string (max 200 chars).
 */
public final class BlockNotesManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/Notes");
    private static final String DATA_DIR  = "config/customblocks";
    private static final String DATA_FILE = "notes.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_NOTE_LENGTH = 200;

    private static final ConcurrentHashMap<String, String> NOTES = new ConcurrentHashMap<>();

    private BlockNotesManager() {}

    // ── Query ────────────────────────────────────────────────────────────

    public static String getNote(String blockId) {
        return blockId == null ? null : NOTES.get(blockId);
    }

    // ── Mutation ─────────────────────────────────────────────────────────

    /** Set a note. Returns true if changed. */
    public static boolean setNote(String blockId, String note) {
        if (blockId == null) return false;
        if (note == null || note.isBlank()) {
            return removeNote(blockId);
        }
        String trimmed = note.length() > MAX_NOTE_LENGTH ? note.substring(0, MAX_NOTE_LENGTH) : note;
        String prev = NOTES.put(blockId, trimmed);
        if (!trimmed.equals(prev)) {
            saveAsync();
            return true;
        }
        return false;
    }

    /** Remove a note. Returns true if one existed. */
    public static boolean removeNote(String blockId) {
        if (blockId == null) return false;
        boolean had = NOTES.remove(blockId) != null;
        if (had) saveAsync();
        return had;
    }

    /** Clean up notes for deleted blocks. */
    public static void onBlockDeleted(String blockId) {
        NOTES.remove(blockId);
        // saveAsync not needed — will be saved on next mutation or shutdown
    }

    // ── Persistence ──────────────────────────────────────────────────────

    public static void load() {
        Path file = Path.of(DATA_DIR, DATA_FILE);
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            NOTES.clear();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                String v = e.getValue().getAsString();
                if (v != null && !v.isBlank()) {
                    NOTES.put(e.getKey(), v.length() > MAX_NOTE_LENGTH ? v.substring(0, MAX_NOTE_LENGTH) : v);
                }
            }
            LOGGER.info("[CustomBlocks] Loaded {} block notes.", NOTES.size());
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load notes.json: {}", e.getMessage());
        }
    }

    private static void saveAsync() {
        Thread t = new Thread(BlockNotesManager::save, "CustomBlocks-NotesSave");
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
            NOTES.forEach(root::addProperty);
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8))) {
                GSON.toJson(root, w);
            }
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to save notes.json: {}", e.getMessage());
        }
    }
}
