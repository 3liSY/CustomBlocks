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

@Environment(EnvType.CLIENT)
public final class HudConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks-HudConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("customblocks").resolve("hud-config.json");

    // ── Position ──────────────────────────────────────────────────────────────
    public static int x = -1;
    public static int y = -1;

    // ── Field toggles (Phase 1) ───────────────────────────────────────────────
    public static boolean showName      = true;
    public static boolean showId        = true;
    public static boolean showLight     = true;
    public static boolean showHardness  = true;
    public static boolean showSound     = true;
    public static boolean showCollision = true;
    public static boolean showFace      = true;

    // ── Style (Phase 2) ───────────────────────────────────────────────────────
    // 0 = Pill (default), 1 = Glow Box, 2 = Plain Text
    public static int style = 0;

    // ── Appearance (Phase 2) ─────────────────────────────────────────────────
    // 0–100 percentage values, stored as 0.0–1.0 in JSON
    public static float bgOpacity   = 0.60f;
    public static float textOpacity = 1.00f;
    public static float scale       = 1.00f;
    // ARGB accent color — used for Glow Box border and Pill accent bar
    public static int   accentColor = 0xFF5B8DFF;

    // ── Visibility / behavior (Phase 2) ──────────────────────────────────────
    public static boolean hudVisible   = true;    // show/hide keybind toggle
    public static boolean fadeEnabled  = true;    // fade in/out when looking at block
    public static boolean stickyMode  = false;   // stay visible N seconds after looking away
    public static float   stickySeconds = 3.0f;

    // ── Runtime-only fade state (not persisted) ───────────────────────────────
    public static float currentAlpha = 0f;       // 0.0 = fully hidden, 1.0 = fully visible

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try (Reader r = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
            if (obj.has("x"))             x             = obj.get("x").getAsInt();
            if (obj.has("y"))             y             = obj.get("y").getAsInt();
            if (obj.has("showName"))      showName      = obj.get("showName").getAsBoolean();
            if (obj.has("showId"))        showId        = obj.get("showId").getAsBoolean();
            if (obj.has("showLight"))     showLight     = obj.get("showLight").getAsBoolean();
            if (obj.has("showHardness"))  showHardness  = obj.get("showHardness").getAsBoolean();
            if (obj.has("showSound"))     showSound     = obj.get("showSound").getAsBoolean();
            if (obj.has("showCollision")) showCollision = obj.get("showCollision").getAsBoolean();
            if (obj.has("showFace"))      showFace      = obj.get("showFace").getAsBoolean();
            if (obj.has("style"))         style         = obj.get("style").getAsInt();
            if (obj.has("bgOpacity"))     bgOpacity     = obj.get("bgOpacity").getAsFloat();
            if (obj.has("textOpacity"))   textOpacity   = obj.get("textOpacity").getAsFloat();
            if (obj.has("scale"))         scale         = obj.get("scale").getAsFloat();
            if (obj.has("accentColor"))   accentColor   = obj.get("accentColor").getAsInt();
            if (obj.has("hudVisible"))    hudVisible    = obj.get("hudVisible").getAsBoolean();
            if (obj.has("fadeEnabled"))   fadeEnabled   = obj.get("fadeEnabled").getAsBoolean();
            if (obj.has("stickyMode"))    stickyMode    = obj.get("stickyMode").getAsBoolean();
            if (obj.has("stickySeconds")) stickySeconds = obj.get("stickySeconds").getAsFloat();
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load hud-config.json", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("x",             x);
            obj.addProperty("y",             y);
            obj.addProperty("showName",      showName);
            obj.addProperty("showId",        showId);
            obj.addProperty("showLight",     showLight);
            obj.addProperty("showHardness",  showHardness);
            obj.addProperty("showSound",     showSound);
            obj.addProperty("showCollision", showCollision);
            obj.addProperty("showFace",      showFace);
            obj.addProperty("style",         style);
            obj.addProperty("bgOpacity",     bgOpacity);
            obj.addProperty("textOpacity",   textOpacity);
            obj.addProperty("scale",         scale);
            obj.addProperty("accentColor",   accentColor);
            obj.addProperty("hudVisible",    hudVisible);
            obj.addProperty("fadeEnabled",   fadeEnabled);
            obj.addProperty("stickyMode",    stickyMode);
            obj.addProperty("stickySeconds", stickySeconds);
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
