package com.customblocks.core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * K1 — Per-block and per-player placement counters.
 * <p>
 * Tracks how many times each custom block has been placed and by whom.
 * Persisted to {@code config/customblocks/placement_stats.json.gz}.
 */
public final class PlacementStats {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DATA_FILE = "config/customblocks/placement_stats.json.gz";

    /** Total placements per custom block ID. */
    private static final ConcurrentHashMap<String, Long> BLOCK_COUNTS = new ConcurrentHashMap<>();
    /** Placements per player (UUID string) → (customId → count). */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> PLAYER_COUNTS = new ConcurrentHashMap<>();

    private static volatile boolean dirty = false;

    private PlacementStats() {}

    /** Record that {@code playerId} placed the block with {@code customId}. */
    public static void recordPlacement(String customId, UUID playerId) {
        if (customId == null || playerId == null) return;
        BLOCK_COUNTS.merge(customId, 1L, Long::sum);
        PLAYER_COUNTS
            .computeIfAbsent(playerId.toString(), k -> new ConcurrentHashMap<>())
            .merge(customId, 1L, Long::sum);
        dirty = true;
    }

    /** Total times {@code customId} has been placed, across all players. */
    public static long getTotal(String customId) {
        return BLOCK_COUNTS.getOrDefault(customId, 0L);
    }

    /** Top {@code n} most-placed blocks, sorted descending by placement count. */
    public static List<Map.Entry<String, Long>> getTopBlocks(int n) {
        return BLOCK_COUNTS.entrySet().stream()
            .filter(e -> SlotManager.hasId(e.getKey()))
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(n)
            .collect(Collectors.toList());
    }

    /** Top {@code n} blocks placed by {@code playerId}, sorted descending. */
    public static List<Map.Entry<String, Long>> getPlayerTop(UUID playerId, int n) {
        Map<String, Long> pc = PLAYER_COUNTS.getOrDefault(playerId.toString(), new ConcurrentHashMap<>());
        return pc.entrySet().stream()
            .filter(e -> SlotManager.hasId(e.getKey()))
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(n)
            .collect(Collectors.toList());
    }

    /** Total placements logged globally. */
    public static long grandTotal() {
        return BLOCK_COUNTS.values().stream().mapToLong(Long::longValue).sum();
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public static void load() {
        Path path = Path.of(DATA_FILE);
        if (!Files.exists(path)) return;
        try (InputStream fis = Files.newInputStream(path);
             GZIPInputStream gzip = new GZIPInputStream(fis);
             BufferedReader br = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(br).getAsJsonObject();
            if (root.has("blocks")) {
                for (var e : root.getAsJsonObject("blocks").entrySet()) {
                    BLOCK_COUNTS.put(e.getKey(), e.getValue().getAsLong());
                }
            }
            if (root.has("players")) {
                for (var pe : root.getAsJsonObject("players").entrySet()) {
                    ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();
                    for (var be : pe.getValue().getAsJsonObject().entrySet()) {
                        map.put(be.getKey(), be.getValue().getAsLong());
                    }
                    PLAYER_COUNTS.put(pe.getKey(), map);
                }
            }
            dirty = false;
            LOGGER.info("[CustomBlocks] PlacementStats loaded ({} blocks tracked).", BLOCK_COUNTS.size());
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("[CustomBlocks] Could not load placement_stats.json.gz: {}", ex.getMessage());
        }
    }

    public static void save() {
        if (!dirty) return;
        Path path = Path.of(DATA_FILE);
        try {
            Path statsParent = path.getParent();
            if (statsParent != null) Files.createDirectories(statsParent);
            Path tmp = path.resolveSibling("placement_stats.json.gz.tmp");
            try (OutputStream fos = Files.newOutputStream(tmp);
                 GZIPOutputStream gzip = new GZIPOutputStream(fos);
                 OutputStreamWriter w = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
                JsonObject root = new JsonObject();
                JsonObject blocks = new JsonObject();
                BLOCK_COUNTS.forEach(blocks::addProperty);
                root.add("blocks", blocks);
                JsonObject players = new JsonObject();
                PLAYER_COUNTS.forEach((uid, counts) -> {
                    JsonObject pj = new JsonObject();
                    counts.forEach(pj::addProperty);
                    players.add(uid, pj);
                });
                root.add("players", players);
                w.write(GSON.toJson(root));
            }
            Files.move(tmp, path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (Exception ex) {
            LOGGER.warn("[CustomBlocks] Could not save placement_stats.json.gz: {}", ex.getMessage());
        }
    }
}
