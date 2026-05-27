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
    public static final String[] CHIP_KEYS   = {"name","id","light","hardness","sound","collision","face","category","health","anim"};
    public static final String[] CHIP_LABELS = {"Name","ID","Light","Hardness","Sound","Collision","Face","Category","Health","Anim"};

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
    public static boolean showCategory  = false;
    public static boolean showHealth    = false;
    public static boolean showAnim      = false;

    // ── Chip ordering ─────────────────────────────────────────────────────────
    // Stores display order: e.g. [2,0,1,...] means Light first, then Name, then ID
    public static int[] chipOrder = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    // ── Content mode ─────────────────────────────────────────────────────────
    // 0 = Visual fields, 1 = Template text
    public static int contentMode = 0;

    // Up to 3 independent template lines (empty string = line not shown)
    public static String[] templateLines = {
        "§f❖ {name}  §8[{id}]",
        "§7Light:{light}  §7Hard:{hardness}  §7🔊{sound}",
        ""
    };

    // ── Scope ─────────────────────────────────────────────────────────────────
    // false = personal (saves to local JSON only)
    // true  = all players on server (broadcasts via HudConfigSyncPayload)
    public static boolean scopeAllPlayers = false;

    // ── Fade speed ────────────────────────────────────────────────────────────
    // Ticks to fully fade in or out. 0 = instant. Default = 12 (~0.6s)
    public static int fadeTicks = 12;

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
            case 7 -> showCategory;
            case 8 -> showHealth;
            case 9 -> showAnim;
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
            case 7 -> showCategory  = v;
            case 8 -> showHealth    = v;
            case 9 -> showAnim      = v;
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
        obj.addProperty("style",           style);
        obj.addProperty("bgOpacity",       bgOpacity);
        obj.addProperty("textOpacity",     textOpacity);
        obj.addProperty("scale",           scale);
        obj.addProperty("accentColor",     accentColor);
        obj.addProperty("contentMode",     contentMode);
        obj.addProperty("templateLine0",   templateLines[0]);
        obj.addProperty("templateLine1",   templateLines[1]);
        obj.addProperty("templateLine2",   templateLines[2]);
        obj.addProperty("showName",        showName);
        obj.addProperty("showId",          showId);
        obj.addProperty("showLight",       showLight);
        obj.addProperty("showHardness",    showHardness);
        obj.addProperty("showSound",       showSound);
        obj.addProperty("showCollision",   showCollision);
        obj.addProperty("showFace",        showFace);
        obj.addProperty("showCategory",    showCategory);
        obj.addProperty("showHealth",      showHealth);
        obj.addProperty("showAnim",        showAnim);
        obj.addProperty("hudVisible",      hudVisible);
        obj.addProperty("fadeEnabled",     fadeEnabled);
        obj.addProperty("fadeTicks",       fadeTicks);
        obj.addProperty("stickyMode",      stickyMode);
        obj.addProperty("stickySeconds",   stickySeconds);
        obj.addProperty("scopeAllPlayers", scopeAllPlayers);
        JsonArray order = new JsonArray();
        for (int v : chipOrder) order.add(v);
        obj.add("chipOrder", order);
        return obj;
    }

    private static void applyJson(JsonObject obj) {
        if (obj.has("style"))           style           = obj.get("style").getAsInt();
        if (obj.has("bgOpacity"))       bgOpacity       = obj.get("bgOpacity").getAsFloat();
        if (obj.has("textOpacity"))     textOpacity     = obj.get("textOpacity").getAsFloat();
        if (obj.has("scale"))           scale           = obj.get("scale").getAsFloat();
        if (obj.has("accentColor"))     accentColor     = obj.get("accentColor").getAsInt();
        if (obj.has("contentMode"))     contentMode     = obj.get("contentMode").getAsInt();
        if (obj.has("templateLine0"))   templateLines[0] = obj.get("templateLine0").getAsString();
        if (obj.has("templateLine1"))   templateLines[1] = obj.get("templateLine1").getAsString();
        if (obj.has("templateLine2"))   templateLines[2] = obj.get("templateLine2").getAsString();
        if (obj.has("showName"))        showName        = obj.get("showName").getAsBoolean();
        if (obj.has("showId"))          showId          = obj.get("showId").getAsBoolean();
        if (obj.has("showLight"))       showLight       = obj.get("showLight").getAsBoolean();
        if (obj.has("showHardness"))    showHardness    = obj.get("showHardness").getAsBoolean();
        if (obj.has("showSound"))       showSound       = obj.get("showSound").getAsBoolean();
        if (obj.has("showCollision"))   showCollision   = obj.get("showCollision").getAsBoolean();
        if (obj.has("showFace"))        showFace        = obj.get("showFace").getAsBoolean();
        if (obj.has("showCategory"))    showCategory    = obj.get("showCategory").getAsBoolean();
        if (obj.has("showHealth"))      showHealth      = obj.get("showHealth").getAsBoolean();
        if (obj.has("showAnim"))        showAnim        = obj.get("showAnim").getAsBoolean();
        if (obj.has("hudVisible"))      hudVisible      = obj.get("hudVisible").getAsBoolean();
        if (obj.has("fadeEnabled"))     fadeEnabled     = obj.get("fadeEnabled").getAsBoolean();
        if (obj.has("fadeTicks"))       fadeTicks       = obj.get("fadeTicks").getAsInt();
        if (obj.has("stickyMode"))      stickyMode      = obj.get("stickyMode").getAsBoolean();
        if (obj.has("stickySeconds"))   stickySeconds   = obj.get("stickySeconds").getAsFloat();
        if (obj.has("scopeAllPlayers")) scopeAllPlayers = obj.get("scopeAllPlayers").getAsBoolean();
        if (obj.has("chipOrder")) {
            JsonArray arr = obj.getAsJsonArray("chipOrder");
            if (arr.size() == 10) {
                for (int i = 0; i < 10; i++) chipOrder[i] = arr.get(i).getAsInt();
            } else if (arr.size() == 7) {
                // Upgrade from old 7-chip config — keep old order, append new chips at end
                chipOrder = new int[10];
                for (int i = 0; i < 7; i++) chipOrder[i] = arr.get(i).getAsInt();
                chipOrder[7] = 7; chipOrder[8] = 8; chipOrder[9] = 9;
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
        chipOrder      = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        showCategory = showHealth = showAnim = false;
        contentMode    = 0;
        templateLines  = new String[]{
            "§f❖ {name}  §8[{id}]",
            "§7Light:{light}  §7Hard:{hardness}  §7🔊{sound}",
            ""
        };
        style          = 0;
        bgOpacity      = 0.60f;
        textOpacity    = 1.00f;
        scale          = 1.00f;
        accentColor    = 0xFF5B8DFF;
        hudVisible     = true;
        fadeEnabled    = true;
        fadeTicks      = 12;
        stickyMode     = false;
        stickySeconds  = 3.0f;
        scopeAllPlayers = false;
    }

    private HudConfig() {}
}
