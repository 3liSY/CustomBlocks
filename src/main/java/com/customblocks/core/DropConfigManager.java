package com.customblocks.core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 12.2 — Manages per-block drop behaviour overrides.
 * <p>
 * By default every custom block drops itself (SELF). This manager allows
 * per-block overrides stored in {@code config/customblocks/drop_config.json}.
 */
public final class DropConfigManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
    private static final String DATA_FILE = "config/customblocks/drop_config.json";

    public enum DropMode {
        /** Block drops nothing. */
        NOTHING,
        /** Block drops itself as an item (default). */
        SELF,
        /** Drops cobblestone — requires pickaxe. */
        STONE_LIKE,
        /** Drops a log — requires axe. */
        WOOD_LIKE,
        /** Drops 1-3 XP orbs. */
        EXPERIENCE
    }

    public record DropConfig(DropMode mode) {}

    private static final ConcurrentHashMap<String, DropMode> OVERRIDES = new ConcurrentHashMap<>();

    private DropConfigManager() {}

    // ── Query / mutate ────────────────────────────────────────────────────────

    public static DropConfig getConfig(String blockId) {
        return new DropConfig(OVERRIDES.getOrDefault(blockId, DropMode.SELF));
    }

    public static void setMode(String blockId, DropMode mode) {
        if (mode == DropMode.SELF) {
            OVERRIDES.remove(blockId); // no need to store the default
        } else {
            OVERRIDES.put(blockId, mode);
        }
        save();
    }

    public static void remove(String blockId) {
        OVERRIDES.remove(blockId);
        save();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public static void load() {
        Path path = Path.of(DATA_FILE);
        if (!Files.exists(path)) return;
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(br).getAsJsonObject();
            for (var entry : root.entrySet()) {
                try {
                    OVERRIDES.put(entry.getKey(), DropMode.valueOf(entry.getValue().getAsString()));
                } catch (IllegalArgumentException ignored) {}
            }
            LOGGER.info("[CustomBlocks] DropConfigManager loaded ({} overrides).", OVERRIDES.size());
        } catch (Exception ex) {
            LOGGER.warn("[CustomBlocks] Could not load drop_config.json: {}", ex.getMessage());
        }
    }

    public static void save() {
        Path path = Path.of(DATA_FILE);
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling("drop_config.json.tmp");
            JsonObject root = new JsonObject();
            OVERRIDES.forEach((id, mode) -> root.addProperty(id, mode.name()));
            try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                bw.write(new GsonBuilder().setPrettyPrinting().create().toJson(root));
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            LOGGER.warn("[CustomBlocks] Could not save drop_config.json: {}", ex.getMessage());
        }
    }
}
