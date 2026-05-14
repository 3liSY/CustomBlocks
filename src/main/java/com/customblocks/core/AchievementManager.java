package com.customblocks.core;

import com.google.gson.*;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * R1/R2 — In-game achievement system.
 * <p>
 * Tracks per-player milestones and fires title + actionBar + sound notifications
 * exactly once per player per achievement.
 * Persisted to {@code config/customblocks/achievements.json.gz}.
 */
public final class AchievementManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DATA_FILE = "config/customblocks/achievements.json.gz";

    // ── Achievement definitions ──────────────────────────────────────────────

    public enum Achievement {
        FIRST_BLOCK    ("first_block",     "§6§l✦ First Creation!",   "You created your first Custom Block"),
        BLOCK_10       ("block_10",        "§a§l✦ Block Collector",    "You've created 10 custom blocks"),
        BLOCK_50       ("block_50",        "§b§l✦ Master Builder",     "You've created 50 custom blocks"),
        BLOCK_100      ("block_100",       "§d§l✦ Legendary Architect","You've created 100 custom blocks"),
        FIRST_PLACEMENT("first_placement", "§e§l✦ First Placement!",  "You placed your first custom block in the world"),
        FIRST_FAVORITE ("first_favorite",  "§c§l✦ Favourited!",       "You starred your first block as a favourite"),
        FIRST_UNDO     ("first_undo",      "§7§l✦ Time Traveller",     "You used undo for the first time"),
        FIRST_CATEGORY ("first_category",  "§3§l✦ Organised!",        "You created your first block category");

        public final String id;
        public final String title;
        public final String subtitle;

        Achievement(String id, String title, String subtitle) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    // ── Per-player state ─────────────────────────────────────────────────────

    /** Set of achievement IDs unlocked per player (UUID → set of IDs). */
    private static final ConcurrentHashMap<UUID, Set<String>> UNLOCKED = new ConcurrentHashMap<>();
    /** Per-player block creation counter (for BLOCK_10/50/100). */
    private static final ConcurrentHashMap<UUID, Integer> BLOCK_COUNT = new ConcurrentHashMap<>();

    private static volatile boolean dirty = false;

    private AchievementManager() {}

    // ── Trigger hooks ────────────────────────────────────────────────────────

    /** Call after a player successfully creates a new block. */
    public static void onBlockCreated(ServerPlayerEntity player) {
        if (player == null) return;
        UUID uuid = player.getUuid();
        grant(player, Achievement.FIRST_BLOCK);
        int count = BLOCK_COUNT.merge(uuid, 1, Integer::sum);
        if (count >= 10)  grant(player, Achievement.BLOCK_10);
        if (count >= 50)  grant(player, Achievement.BLOCK_50);
        if (count >= 100) grant(player, Achievement.BLOCK_100);
    }

    /** Call after a player places a custom block in the world. */
    public static void onBlockPlaced(ServerPlayerEntity player) {
        if (player == null) return;
        grant(player, Achievement.FIRST_PLACEMENT);
    }

    /** Call after a player adds a block to their favourites. */
    public static void onFavoriteAdded(ServerPlayerEntity player) {
        if (player == null) return;
        grant(player, Achievement.FIRST_FAVORITE);
    }

    /** Call after a player undoes an action. */
    public static void onUndo(ServerPlayerEntity player) {
        if (player == null) return;
        grant(player, Achievement.FIRST_UNDO);
    }

    /** Call after a player creates a category. */
    public static void onCategoryCreated(ServerPlayerEntity player) {
        if (player == null) return;
        grant(player, Achievement.FIRST_CATEGORY);
    }

    // ── Core grant logic ─────────────────────────────────────────────────────

    private static void grant(ServerPlayerEntity player, Achievement achievement) {
        UUID uuid = player.getUuid();
        Set<String> set = UNLOCKED.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        if (!set.add(achievement.id)) return; // already unlocked
        dirty = true;
        LOGGER.info("[CustomBlocks] Achievement unlocked for {}: {}", player.getName().getString(), achievement.id);
        notify(player, achievement);
    }

    private static void notify(ServerPlayerEntity player, Achievement achievement) {
        // Royal Directive: toast + sound + full-screen title (R2)
        com.customblocks.gui.FeedbackHelper.title(player,
            "§6§l🏆 Achievement Unlocked!",
            achievement.title + "\n§7" + achievement.subtitle);
        com.customblocks.gui.FeedbackHelper.actionBar(player,
            "§6§l🏆 §r" + achievement.title + " §8— §7" + achievement.subtitle);
        com.customblocks.gui.GuiManager.playSuccess(player);
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public static void load() {
        Path path = Path.of(DATA_FILE);
        if (!Files.exists(path)) return;
        try (InputStream fis = Files.newInputStream(path);
             GZIPInputStream gzip = new GZIPInputStream(fis);
             BufferedReader br = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(br).getAsJsonObject();
            if (root.has("unlocked")) {
                for (var pe : root.getAsJsonObject("unlocked").entrySet()) {
                    UUID uuid = UUID.fromString(pe.getKey());
                    Set<String> ids = ConcurrentHashMap.newKeySet();
                    for (var e : pe.getValue().getAsJsonArray()) ids.add(e.getAsString());
                    UNLOCKED.put(uuid, ids);
                }
            }
            if (root.has("blockCounts")) {
                for (var pe : root.getAsJsonObject("blockCounts").entrySet()) {
                    BLOCK_COUNT.put(UUID.fromString(pe.getKey()), pe.getValue().getAsInt());
                }
            }
            dirty = false;
            LOGGER.info("[CustomBlocks] AchievementManager loaded ({} players tracked).", UNLOCKED.size());
        } catch (Exception ex) {
            LOGGER.warn("[CustomBlocks] Could not load achievements.json.gz: {}", ex.getMessage());
        }
    }

    public static void save() {
        if (!dirty) return;
        Path path = Path.of(DATA_FILE);
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling("achievements.json.gz.tmp");
            try (OutputStream fos = Files.newOutputStream(tmp);
                 GZIPOutputStream gzip = new GZIPOutputStream(fos);
                 OutputStreamWriter w = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
                JsonObject root = new JsonObject();
                JsonObject unlocked = new JsonObject();
                UNLOCKED.forEach((uuid, ids) -> {
                    JsonArray arr = new JsonArray();
                    ids.forEach(arr::add);
                    unlocked.add(uuid.toString(), arr);
                });
                root.add("unlocked", unlocked);
                JsonObject counts = new JsonObject();
                BLOCK_COUNT.forEach((uuid, n) -> counts.addProperty(uuid.toString(), n));
                root.add("blockCounts", counts);
                w.write(GSON.toJson(root));
            }
            Files.move(tmp, path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (Exception ex) {
            LOGGER.warn("[CustomBlocks] Could not save achievements.json.gz: {}", ex.getMessage());
        }
    }
}
