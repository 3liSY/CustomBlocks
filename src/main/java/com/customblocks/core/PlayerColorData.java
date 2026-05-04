package com.customblocks.core;

import com.customblocks.CustomBlocksMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerColorData {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File DATA_DIR = new File(FabricLoader.getInstance().getConfigDir().toFile(), "customblocks/player_data");
    private static final ConcurrentHashMap<UUID, PlayerColorData> CACHE = new ConcurrentHashMap<>();

    public final List<Integer> favorites = new ArrayList<>();
    public final List<Integer> history = new ArrayList<>();

    public static PlayerColorData get(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, PlayerColorData::load);
    }

    private static PlayerColorData load(UUID uuid) {
        PlayerColorData data = new PlayerColorData();
        File file = new File(DATA_DIR, uuid.toString() + ".json");
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                if (json != null) {
                    if (json.has("favorites")) {
                        JsonArray favArr = json.getAsJsonArray("favorites");
                        for (int i = 0; i < favArr.size(); i++) {
                            data.favorites.add(favArr.get(i).getAsInt());
                        }
                    }
                    if (json.has("history")) {
                        JsonArray histArr = json.getAsJsonArray("history");
                        for (int i = 0; i < histArr.size(); i++) {
                            data.history.add(histArr.get(i).getAsInt());
                        }
                    }
                }
            } catch (Exception e) {
                CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to load player color data for " + uuid, e);
            }
        }
        // Ensure defaults if empty
        while (data.favorites.size() < 4) data.favorites.add(0xFFFFFF); // White as default empty
        return data;
    }

    public void save(UUID uuid) {
        DATA_DIR.mkdirs();
        File liveFile = new File(DATA_DIR, uuid.toString() + ".json");
        File tmpFile = new File(DATA_DIR, uuid.toString() + ".json.tmp");

        JsonObject json = new JsonObject();
        JsonArray favArr = new JsonArray();
        for (int fav : favorites) favArr.add(fav);
        json.add("favorites", favArr);

        JsonArray histArr = new JsonArray();
        for (int hist : history) histArr.add(hist);
        json.add("history", histArr);

        try {
            try (FileWriter fw = new FileWriter(tmpFile)) {
                GSON.toJson(json, fw);
            }
            Files.move(tmpFile.toPath(), liveFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to save player color data for " + uuid, e);
        }
    }

    public void addFavorite(UUID uuid, int index, int color) {
        while (favorites.size() <= index) favorites.add(0xFFFFFF);
        favorites.set(index, color);
        save(uuid);
    }

    public void addHistory(UUID uuid, int color) {
        history.remove((Integer) color); // Remove duplicate if exists
        history.add(0, color);
        if (history.size() > 2) {
            history.remove(history.size() - 1);
        }
        save(uuid);
    }
}
