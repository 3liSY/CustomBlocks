package com.customblocks.core;

import com.customblocks.CustomBlocksMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MagicItemsManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "customblocks/magic_items.json");
    private static final File TMP_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "customblocks/magic_items.json.tmp");

    public static final ConcurrentHashMap<String, MagicItemConfig> ITEMS = new ConcurrentHashMap<>();

    public static class MagicItemConfig {
        public String id;
        public boolean enabled = true;
        public boolean requirePermission = false;
        public int cooldownTicks = 0;
        public boolean visualGlint = true;
        public String soundOnUse = "entity.experience_orb.pickup";
        public boolean particlesOnUse = true;
        public boolean consumeOnUse = false;
        public int maxUses = -1; // -1 for infinite
        
        // Custom visual
        public String displayName = "";
        public String tooltip1 = "";
        public String tooltip2 = "";
        public String tooltip3 = "";

        // Behavioral limits
        public boolean worksOnNonCustomBlocks = false;
        public boolean worksInCreativeOnly = false;
        public boolean allowSneakAction = true;
        
        public MagicItemConfig(String id, String defaultName) {
            this.id = id;
            this.displayName = defaultName;
        }
    }

    public static void loadAll() {
        ITEMS.clear();
        // Setup defaults
        ITEMS.put("diamond_triangle", new MagicItemConfig("diamond_triangle", "§b§lDiamond Triangle"));
        ITEMS.put("golden_hexagon", new MagicItemConfig("golden_hexagon", "§6§lGolden Hexagon"));
        ITEMS.put("amethyst_chisel", new MagicItemConfig("amethyst_chisel", "§5§lCrystal Editor"));
        ITEMS.put("lumina_brush", new MagicItemConfig("lumina_brush", "§b§lLumina Brush"));
        ITEMS.put("rainbow_rectangle", new MagicItemConfig("rainbow_rectangle", "§6§lRainbow Rectangle"));

        if (!CONFIG_FILE.exists()) {
            saveAll();
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return;

            for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    MagicItemConfig cfg = GSON.fromJson(entry.getValue(), MagicItemConfig.class);
                    cfg.id = entry.getKey();
                    ITEMS.put(entry.getKey(), cfg);
                }
            }
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to load magic_items.json", e);
        }
    }

    public static void saveAll() {
        File parent = CONFIG_FILE.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        JsonObject root = new JsonObject();
        for (Map.Entry<String, MagicItemConfig> entry : ITEMS.entrySet()) {
            root.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
        }

        try {
            try (FileWriter fw = new FileWriter(TMP_FILE)) {
                GSON.toJson(root, fw);
            }
            Files.move(TMP_FILE.toPath(), CONFIG_FILE.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to save magic_items.json", e);
        }
    }

    public static MagicItemConfig getConfig(String id) {
        return ITEMS.getOrDefault(id, new MagicItemConfig(id, "Unknown"));
    }
}
