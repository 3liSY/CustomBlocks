package com.customblocks.core;

import com.customblocks.CustomBlocksMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Per-player favorite block IDs (reads gated by {@link com.customblocks.command.PermissionHelper#canFavorite}).
 */
public final class FavoritesManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH = Path.of("config/customblocks/favorites.json.gz");
    private static final Map<UUID, Set<String>> FAV = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private FavoritesManager() {}

    /** IDs that still exist in {@link SlotManager}. */
    public static Set<String> validatedSet(UUID player) {
        Set<String> raw = FAV.get(player);
        if (raw == null || raw.isEmpty()) return Set.of();
        return raw.stream().filter(SlotManager::hasId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Whether {@code blockId} is starred for this player (normalized to lowercase). */
    public static boolean isFavorite(UUID player, String blockId) {
        if (blockId == null || blockId.isBlank()) return false;
        String id = blockId.trim().toLowerCase(Locale.ROOT);
        Set<String> s = FAV.get(player);
        return s != null && s.contains(id);
    }

    public static boolean toggle(UUID player, String blockId, MinecraftServer server) {
        if (blockId == null || blockId.isBlank()) return false;
        String id = blockId.trim().toLowerCase(Locale.ROOT);
        Set<String> set = FAV.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet());
        synchronized (set) {
            if (!set.remove(id)) {
                set.add(id);
            }
        }
        scheduleFlush(server);
        return true;
    }

    private static void scheduleFlush(MinecraftServer server) {
        if (server == null) {
            flushSave();
            return;
        }
        server.execute(FavoritesManager::flushSave);
    }

    public static void load() {
        loaded = true;
        if (!Files.isRegularFile(DATA_PATH)) {
            FAV.clear();
            return;
        }
        try (GZIPInputStream gzin = new GZIPInputStream(Files.newInputStream(DATA_PATH));
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzin, StandardCharsets.UTF_8))) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return;
            FAV.clear();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(e.getKey());
                    Set<String> ids = ConcurrentHashMap.newKeySet();
                    if (e.getValue().isJsonArray()) {
                        for (JsonElement el : e.getValue().getAsJsonArray()) {
                            if (el.isJsonPrimitive()) {
                                String id = el.getAsString().trim().toLowerCase(Locale.ROOT);
                                if (!id.isEmpty() && SlotManager.hasId(id)) ids.add(id);
                            }
                        }
                    }
                    if (!ids.isEmpty()) FAV.put(uuid, ids);
                } catch (Exception ignored) {
                    // Skip malformed player rows
                }
            }
        } catch (Exception ex) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not load favorites (starting empty): {}", ex.getMessage());
            FAV.clear();
        }
    }

    public static void flushSave() {
        if (!loaded) return;
        try {
            Path favParent = DATA_PATH.getParent();
            if (favParent != null) Files.createDirectories(favParent);
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, Set<String>> e : FAV.entrySet()) {
                var arr = new com.google.gson.JsonArray();
                Set<String> cleaned = e.getValue().stream()
                    .filter(SlotManager::hasId)
                    .sorted()
                    .collect(Collectors.toCollection(LinkedHashSet::new));
                for (String id : cleaned) arr.add(id);
                if (!cleaned.isEmpty()) root.add(e.getKey().toString(), arr);
            }
            Path tmp = Path.of(DATA_PATH + ".tmp");
            try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(tmp));
                 BufferedWriter w = new BufferedWriter(new OutputStreamWriter(gz, StandardCharsets.UTF_8))) {
                GSON.toJson(root, w);
            }
            Files.move(tmp, DATA_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ex) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to save favorites", ex);
        }
    }
}
