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

    public static int     x             = -1;
    public static int     y             = -1;
    public static boolean showName      = true;
    public static boolean showId        = true;
    public static boolean showLight     = true;
    public static boolean showHardness  = true;
    public static boolean showSound     = true;
    public static boolean showCollision = true;
    public static boolean showFace      = true;

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
    }

    private HudConfig() {}
}
