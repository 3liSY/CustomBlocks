package com.customblocks.client;

import com.google.gson.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Environment(EnvType.CLIENT)
public final class HudConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks-HudConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("customblocks").resolve("hud-config.json");
    private static final Path PRESETS_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("customblocks").resolve("hud-presets.json");

    // ── Chip key definitions ──────────────────────────────────────────────────
    public static final String[] CHIP_KEYS   = {"name","id","light","hardness","sound","collision","face"};
    public static final String[] CHIP_LABELS = {"Name","ID","Light","Hardness","Sound","Collision","Face"};

    // ── Position ──────────────────────────────────────────────────────────────
    public static int x = -1;
    public static int y = -1;

    // ── Field toggles ─────────────────────────────────────────────────────────
    public static boolean showName      = true;
    public static boolean showId        = true;
    public static boolean showLight     = true;
    public static boolean showHardness  = true;
    public static boolean showSound     = true;
    public static boolean showCollision = true;
    public static boolean showFace      = true;

    // ── Chip ordering ─────────────────────────────────────────────────────────
    // Stores display order: e.g. [2,0,1,...] means Light first, then Name, then ID
    public static int[] chipOrder = {0, 1, 2, 3, 4, 5, 6};

    // ── Content mode ─────────────────────────────────────────────────────────
    // 0 = Visual chips, 1 = Template text
    public static int contentMode = 0;
    public static String template = "§f❖ {name}  §8[{id}]  §7Light:{light}  {sound}";

    // ── Style ─────────────────────────────────────────────────────────────────
    // 0 = Pill, 1 = Glow Box, 2 = Plain Text
    public static int style = 0;

    // ── Appearance ───────────────────────────────────────────────────────────
    public static float bgOpacity   = 0.60f;
    public static float textOpacity = 1.00f;
    public static float scale       = 1.00f;
    public static int   accentColor = 0xFF5B8DFF;

    // ── Visibility / behavior ─────────────────────────────────────────────────
    public static boolean hudVisible    = true;
    public static boolean fadeEnabled   = true;
    public static boolean stickyMode    = false;
    public static float   stickySeconds = 3.0f;

    // ── Runtime-only (not persisted) ──────────────────────────────────────────
    public static float currentAlpha  = 0f;
    public static long  lastSawBlockMs = 0L;

    // ── Chip helpers ──────────────────────────────────────────────────────────
    public static boolean isChipEnabled(int fieldIdx) {
        return switch (fieldIdx) {
            case 0 -> showName;
            case 1 -> showId;
            case 2 -> showLight;
            case 3 -> showHardness;
            case 4 -> showSound;
            case 5 -> showCollision;
            case 6 -> showFace;
            default -> false;
        };
    }

    public static void setChipEnabled(int fieldIdx, boolean v) {
        switch (fieldIdx) {
            case 0 -> showName      = v;
            case 1 -> showId        = v;
            case 2 -> showLight     = v;
            case 3 -> showHardness  = v;
            case 4 -> showSound     = v;
            case 5 -> showCollision = v;
            case 6 -> showFace      = v;
        }
    }

    // ── Preset system ─────────────────────────────────────────────────────────
    private static final LinkedHashMap<String, JsonObject> PRESETS = new LinkedHashMap<>();

    public static Map<String, JsonObject> getPresets() {
        return Collections.unmodifiableMap(PRESETS);
    }

    public static void loadPresets() {
        PRESETS.clear();
        if (!Files.exists(PRESETS_PATH)) return;
        try (Reader r = Files.newBufferedReader(PRESETS_PATH, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.has("presets")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("presets").entrySet()) {
                    PRESETS.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            }
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load hud-presets.json", e);
        }
    }

    private static void savePresets() {
        try {
            Files.createDirectories(PRESETS_PATH.getParent());
            JsonObject root = new JsonObject();
            JsonObject presetsObj = new JsonObject();
            for (Map.Entry<String, JsonObject> e : PRESETS.entrySet()) {
                presetsObj.add(e.getKey(), e.getValue());
            }
            root.add("presets", presetsObj);
            Path tmp = PRESETS_PATH.getParent().resolve("hud-presets.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            Files.move(tmp, PRESETS_PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to save hud-presets.json", e);
        }
    }

    public static void saveCurrentAsPreset(String name) {
        PRESETS.put(name, buildCurrentJson());
        if (PRESETS.size() > 10) {
            String oldest = PRESETS.keySet().iterator().next();
            PRESETS.remove(oldest);
        }
        savePresets();
    }

    public static void loadPreset(String name) {
        JsonObject obj = PRESETS.get(name);
        if (obj == null) return;
        applyJson(obj);
    }

    public static void deletePreset(String name) {
        PRESETS.remove(name);
        savePresets();
    }

    /** Returns a base64-encoded JSON string of the current config that can be shared. */
    public static String exportCode() {
        try {
            byte[] json = GSON.toJson(buildCurrentJson()).getBytes(StandardCharsets.UTF_8);
            return java.util.Base64.getEncoder().encodeToString(json);
        } catch (Exception e) {
            return "";
        }
    }

    /** Parses a base64 preset code and applies it. Returns false if invalid. */
    public static boolean importCode(String code) {
        try {
            byte[] json = java.util.Base64.getDecoder().decode(code.trim());
            JsonObject obj = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            applyJson(obj);
            save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonObject buildCurrentJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("style",         style);
        obj.addProperty("bgOpacity",     bgOpacity);
        obj.addProperty("textOpacity",   textOpacity);
        obj.addProperty("scale",         scale);
        obj.addProperty("accentColor",   accentColor);
        obj.addProperty("contentMode",   contentMode);
        obj.addProperty("template",      template);
        obj.addProperty("showName",      showName);
        obj.addProperty("showId",        showId);
        obj.addProperty("showLight",     showLight);
        obj.addProperty("showHardness",  showHardness);
        obj.addProperty("showSound",     showSound);
        obj.addProperty("showCollision", showCollision);
        obj.addProperty("showFace",      showFace);
        obj.addProperty("hudVisible",    hudVisible);
        obj.addProperty("fadeEnabled",   fadeEnabled);
        obj.addProperty("stickyMode",    stickyMode);
        obj.addProperty("stickySeconds", stickySeconds);
        JsonArray order = new JsonArray();
        for (int v : chipOrder) order.add(v);
        obj.add("chipOrder", order);
        return obj;
    }

    private static void applyJson(JsonObject obj) {
        if (obj.has("style"))         style         = obj.get("style").getAsInt();
        if (obj.has("bgOpacity"))     bgOpacity     = obj.get("bgOpacity").getAsFloat();
        if (obj.has("textOpacity"))   textOpacity   = obj.get("textOpacity").getAsFloat();
        if (obj.has("scale"))         scale         = obj.get("scale").getAsFloat();
        if (obj.has("accentColor"))   accentColor   = obj.get("accentColor").getAsInt();
        if (obj.has("contentMode"))   contentMode   = obj.get("contentMode").getAsInt();
        if (obj.has("template"))      template      = obj.get("template").getAsString();
        if (obj.has("showName"))      showName      = obj.get("showName").getAsBoolean();
        if (obj.has("showId"))        showId        = obj.get("showId").getAsBoolean();
        if (obj.has("showLight"))     showLight     = obj.get("showLight").getAsBoolean();
        if (obj.has("showHardness"))  showHardness  = obj.get("showHardness").getAsBoolean();
        if (obj.has("showSound"))     showSound     = obj.get("showSound").getAsBoolean();
        if (obj.has("showCollision")) showCollision = obj.get("showCollision").getAsBoolean();
        if (obj.has("showFace"))      showFace      = obj.get("showFace").getAsBoolean();
        if (obj.has("hudVisible"))    hudVisible    = obj.get("hudVisible").getAsBoolean();
        if (obj.has("fadeEnabled"))   fadeEnabled   = obj.get("fadeEnabled").getAsBoolean();
        if (obj.has("stickyMode"))    stickyMode    = obj.get("stickyMode").getAsBoolean();
        if (obj.has("stickySeconds")) stickySeconds = obj.get("stickySeconds").getAsFloat();
        if (obj.has("chipOrder")) {
            JsonArray arr = obj.getAsJsonArray("chipOrder");
            if (arr.size() == 7) {
                for (int i = 0; i < 7; i++) chipOrder[i] = arr.get(i).getAsInt();
            }
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────
    public static void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try (Reader r = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            applyJson(JsonParser.parseReader(r).getAsJsonObject());
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load hud-config.json", e);
        }
        loadPresets();
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = buildCurrentJson();
            obj.addProperty("x", x);
            obj.addProperty("y", y);
            Path tmp = CONFIG_PATH.getParent().resolve("hud-config.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(obj, w);
            }
            Files.move(tmp, CONFIG_PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to save hud-config.json", e);
        }
    }

    public static void resetToDefaults() {
        x = -1; y = -1;
        showName = showId = showLight = showHardness = showSound = showCollision = showFace = true;
        chipOrder   = new int[]{0, 1, 2, 3, 4, 5, 6};
        contentMode = 0;
        template    = "§f❖ {name}  §8[{id}]  §7Light:{light}  {sound}";
        style       = 0;
        bgOpacity   = 0.60f;
        textOpacity = 1.00f;
        scale       = 1.00f;
        accentColor = 0xFF5B8DFF;
        hudVisible  = true;
        fadeEnabled = true;
        stickyMode  = false;
        stickySeconds = 3.0f;
    }

    private HudConfig() {}
}
