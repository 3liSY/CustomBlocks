package com.customblocks.core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 3.2 — Per-player personal color palette.
 * <p>
 * Stores up to {@value #MAX_PALETTE_SIZE} named hex colors per player.
 * Persisted to {@code config/customblocks/palettes/<uuid>.json}.
 */
public final class PlayerPaletteManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
    private static final String PALETTE_DIR = "config/customblocks/palettes";
    /** Maximum saved colors per player. */
    public static final int MAX_PALETTE_SIZE = 18;
    /** Maximum recently-used colors tracked per player. */
    public static final int MAX_RECENT_SIZE = 9;

    /** Per-player ordered palette: hex strings (already normalized "#RRGGBB"). */
    private static final Map<UUID, List<String>> PALETTES = new ConcurrentHashMap<>();
    /** Per-player recently-used color queue (most recent first). */
    private static final Map<UUID, Deque<String>> RECENT  = new ConcurrentHashMap<>();

    private PlayerPaletteManager() {}

    // ── Palette ──────────────────────────────────────────────────────────────

    /** Immutable view of a player's saved palette (normalized hex strings). */
    public static List<String> getPalette(UUID player) {
        return Collections.unmodifiableList(PALETTES.getOrDefault(player, List.of()));
    }

    /**
     * Add a color to the player's palette.
     * @return false if already present or palette is full
     */
    public static boolean addColor(UUID player, String hex) {
        String normalized = normalize(hex);
        if (normalized == null) return false;
        List<String> pal = PALETTES.computeIfAbsent(player, k -> new ArrayList<>());
        if (pal.contains(normalized)) return false;
        if (pal.size() >= MAX_PALETTE_SIZE) return false;
        pal.add(normalized);
        saveAsync(player);
        return true;
    }

    /**
     * Remove a color from the player's palette by index.
     * @return true if removed
     */
    public static boolean removeColor(UUID player, int index) {
        List<String> pal = PALETTES.get(player);
        if (pal == null || index < 0 || index >= pal.size()) return false;
        pal.remove(index);
        saveAsync(player);
        return true;
    }

    /** Remove a color from the player's palette by hex value. */
    public static boolean removeColor(UUID player, String hex) {
        String normalized = normalize(hex);
        if (normalized == null) return false;
        List<String> pal = PALETTES.get(player);
        if (pal == null) return false;
        boolean removed = pal.remove(normalized);
        if (removed) saveAsync(player);
        return removed;
    }

    /** Toggle a color — add if not present, remove if present. */
    public static boolean toggleColor(UUID player, String hex) {
        String normalized = normalize(hex);
        if (normalized == null) return false;
        List<String> pal = PALETTES.computeIfAbsent(player, k -> new ArrayList<>());
        if (pal.contains(normalized)) {
            pal.remove(normalized);
            saveAsync(player);
            return false; // removed
        } else {
            if (pal.size() < MAX_PALETTE_SIZE) {
                pal.add(normalized);
                saveAsync(player);
            }
            return true; // added
        }
    }

    public static boolean isPaletteColor(UUID player, String hex) {
        String normalized = normalize(hex);
        return normalized != null && PALETTES.getOrDefault(player, List.of()).contains(normalized);
    }

    // ── Recently Used ────────────────────────────────────────────────────────

    /** Record a color as recently used. Called whenever a color is selected. */
    public static void trackRecent(UUID player, String hex) {
        String normalized = normalize(hex);
        if (normalized == null) return;
        Deque<String> recent = RECENT.computeIfAbsent(player, k -> new ArrayDeque<>());
        recent.remove(normalized); // deduplicate
        recent.addFirst(normalized);
        while (recent.size() > MAX_RECENT_SIZE) recent.removeLast();
    }

    /** Returns up to {@value #MAX_RECENT_SIZE} recently-used hex strings, most recent first. */
    public static List<String> getRecent(UUID player) {
        Deque<String> recent = RECENT.get(player);
        return recent != null ? List.copyOf(recent) : List.of();
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public static void load(UUID player) {
        Path file = Path.of(PALETTE_DIR, player.toString() + ".json");
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            List<String> pal = new ArrayList<>();
            if (obj.has("palette")) {
                for (JsonElement el : obj.getAsJsonArray("palette")) {
                    String hex = normalize(el.getAsString());
                    if (hex != null) pal.add(hex);
                }
            }
            PALETTES.put(player, pal);
            if (obj.has("recent")) {
                Deque<String> recent = new ArrayDeque<>();
                for (JsonElement el : obj.getAsJsonArray("recent")) {
                    String hex = normalize(el.getAsString());
                    if (hex != null) recent.add(hex);
                }
                RECENT.put(player, recent);
            }
        } catch (Exception e) {
            LOGGER.warn("[PlayerPaletteManager] Failed to load palette for {}: {}", player, e.getMessage());
        }
    }

    public static void saveAsync(UUID player) {
        List<String> pal    = new ArrayList<>(PALETTES.getOrDefault(player, List.of()));
        List<String> recent = new ArrayList<>(RECENT.getOrDefault(player, new ArrayDeque<>()));
        Thread.ofVirtual().start(() -> save(player, pal, recent));
    }

    private static void save(UUID player, List<String> pal, List<String> recent) {
        try {
            Path dir = Path.of(PALETTE_DIR);
            Files.createDirectories(dir);
            JsonObject obj = new JsonObject();
            JsonArray palArr = new JsonArray();
            pal.forEach(palArr::add);
            obj.add("palette", palArr);
            JsonArray recArr = new JsonArray();
            recent.forEach(recArr::add);
            obj.add("recent", recArr);
            Path file = dir.resolve(player.toString() + ".json");
            Path tmp  = dir.resolve(player.toString() + ".json.tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("[PlayerPaletteManager] Failed to save palette for {}: {}", player, e.getMessage());
        }
    }

    public static void onPlayerDisconnect(UUID player) {
        // Keep in memory for session reuse; only evict if server wants to free memory
        RECENT.remove(player);
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    /** Normalize to uppercase #RRGGBB or return null if invalid. */
    public static String normalize(String hex) {
        if (hex == null) return null;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        try { Integer.parseInt(s, 16); }
        catch (NumberFormatException e) { return null; }
        return "#" + s.toUpperCase(java.util.Locale.ROOT);
    }
}
