package com.customblocks;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralised configuration for CustomBlocks.
 * Persisted as {@code config/customblocks/config.json}.
 * <p>
 * All fields are volatile so read access from any thread sees the latest value
 * without synchronisation; writes only happen on the server thread via {@link #load()} / {@link #save()}.
 */
public final class CustomBlocksConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_DIR  = "config/customblocks";
    private static final String CONFIG_FILE = "config.json";

    // ── Slot System ──────────────────────────────────────────────────────────
    /** Maximum number of block slots. Requires restart to take effect. */
    public static volatile int maxSlots = 2048;

    // ── Image Processing ─────────────────────────────────────────────────────
    /** Default texture size in pixels (16–256). */
    public static volatile int defaultTextureSize = 128;
    /** Background-removal colour-distance tolerance (0 = disabled, 1–100). */
    public static volatile int bgRemovalTolerance = 30;
    /** HTTP download timeout in seconds. */
    public static volatile int downloadTimeoutSeconds = 15;

    // ── Tool Sessions ────────────────────────────────────────────────────────
    /** Tool session timeout in seconds (rectangle wand, etc.). 0 = no timeout. */
    public static volatile int sessionTimeoutSeconds = 300;

    // ── Undo System ──────────────────────────────────────────────────────────
    /** Undo mode: "global" = single shared stack, "per_player" = one stack per player, "both" = both available. */
    public static volatile String undoMode = "both";
    /** Maximum undo depth (per stack). */
    public static volatile int maxUndoDepth = 20;

    // ── Permissions ──────────────────────────────────────────────────────────
    /** Default OP level required for admin commands (create/delete/edit). */
    public static volatile int permissionLevelAdmin = 2;
    /** Default OP level required for use commands (give/gui). */
    public static volatile int permissionLevelUse = 0;

    // ── Network ──────────────────────────────────────────────────────────────
    /** Number of texture payloads to drip-feed per server tick. */
    public static volatile int texturePayloadsPerTick = 8;
    /** Internal HTTP server port for resource pack hosting (0 = disabled/auto). */
    public static volatile int resourcePackPort = 8080;
    /** Debounce time for live-edit resource pack reloads (ms). */
    public static volatile long reloadDebounceMs = 2000;
    /** Debounce time for initial join burst (ms). */
    public static volatile long joinDebounceMs = 4000;

    // ── Resource Pack Enforcement ─────────────────────────────────────────
    /** Whether to send the resource pack as mandatory on player join. */
    public static volatile boolean rpEnforceOnJoin = true;
    /** Branded prompt message shown when the RP download dialog appears. */
    public static volatile String rpPromptMessage = "§b§lCustomBlocks §7requires a resource pack to display custom blocks.";
    /** Kick message shown to players who decline the resource pack. */
    public static volatile String rpKickMessage = "§cYou must accept the CustomBlocks resource pack to play on this server.";

    // ── Assistant NPC (The Helper) ───────────────────────────────────────────
    /** Whether the helper is currently spawned in the world. */
    public static volatile boolean helperEnabled = false;
    /** The helper's display name. */
    public static volatile String helperName = "CustomBlocks Helper";
    /** The helper's skin (Minecraft username or texture URL). */
    public static volatile String helperSkin = "Architect";
    /** Whether the live status hologram is visible. */
    public static volatile boolean helperHologram = true;

    // ── Public API ───────────────────────────────────────────────────────────

    public static void setResourcePackPort(int port) {
        resourcePackPort = port;
        save();
    }

    /** Load configuration from disk, creating defaults if missing. */
    public static void load() {
        Path dir = Path.of(CONFIG_DIR);
        Path file = dir.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(dir);
            if (!Files.exists(file)) {
                save(); // write defaults
                LOGGER.info("[CustomBlocks] Created default config at {}", file);
                return;
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            maxSlots              = getInt(root, "maxSlots", maxSlots);
            defaultTextureSize    = getInt(root, "defaultTextureSize", defaultTextureSize);
            bgRemovalTolerance    = getInt(root, "bgRemovalTolerance", bgRemovalTolerance);
            downloadTimeoutSeconds= getInt(root, "downloadTimeoutSeconds", downloadTimeoutSeconds);
            sessionTimeoutSeconds = getInt(root, "sessionTimeoutSeconds", sessionTimeoutSeconds);
            undoMode              = getString(root, "undoMode", undoMode);
            maxUndoDepth          = getInt(root, "maxUndoDepth", maxUndoDepth);
            permissionLevelAdmin  = getInt(root, "permissionLevelAdmin", permissionLevelAdmin);
            permissionLevelUse    = getInt(root, "permissionLevelUse", permissionLevelUse);
            texturePayloadsPerTick= getInt(root, "texturePayloadsPerTick", texturePayloadsPerTick);
            resourcePackPort      = getInt(root, "resourcePackPort", resourcePackPort);
            reloadDebounceMs      = getLong(root, "reloadDebounceMs", reloadDebounceMs);
            joinDebounceMs        = getLong(root, "joinDebounceMs", joinDebounceMs);
            helperEnabled         = getBool(root, "helperEnabled", helperEnabled);
            helperName            = getString(root, "helperName", helperName);
            helperSkin            = getString(root, "helperSkin", helperSkin);
            helperHologram        = getBool(root, "helperHologram", helperHologram);
            rpEnforceOnJoin       = getBool(root, "rpEnforceOnJoin", rpEnforceOnJoin);
            rpPromptMessage       = getString(root, "rpPromptMessage", rpPromptMessage);
            rpKickMessage         = getString(root, "rpKickMessage", rpKickMessage);

            // Clamp values
            maxSlots              = Math.max(1, Math.min(8192, maxSlots));
            defaultTextureSize    = Math.max(16, Math.min(256, defaultTextureSize));
            bgRemovalTolerance    = Math.max(0, Math.min(100, bgRemovalTolerance));
            downloadTimeoutSeconds= Math.max(1, Math.min(120, downloadTimeoutSeconds));
            sessionTimeoutSeconds = Math.max(0, Math.min(3600, sessionTimeoutSeconds));
            maxUndoDepth          = Math.max(1, Math.min(100, maxUndoDepth));
            permissionLevelAdmin  = Math.max(0, Math.min(4, permissionLevelAdmin));
            permissionLevelUse    = Math.max(0, Math.min(4, permissionLevelUse));
            texturePayloadsPerTick= Math.max(1, Math.min(50, texturePayloadsPerTick));

            if (!undoMode.equals("global") && !undoMode.equals("per_player") && !undoMode.equals("both")) {
                LOGGER.warn("[CustomBlocks] Invalid undoMode '{}', defaulting to 'both'", undoMode);
                undoMode = "both";
            }

            LOGGER.info("[CustomBlocks] Config loaded: maxSlots={}, undoMode={}, bgTolerance={}",
                    maxSlots, undoMode, bgRemovalTolerance);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load config, using defaults", e);
        }
    }

    /** Save current configuration to disk. */
    public static void save() {
        Path dir = Path.of(CONFIG_DIR);
        Path file = dir.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(dir);
            JsonObject root = new JsonObject();
            root.addProperty("maxSlots", maxSlots);
            root.addProperty("defaultTextureSize", defaultTextureSize);
            root.addProperty("bgRemovalTolerance", bgRemovalTolerance);
            root.addProperty("downloadTimeoutSeconds", downloadTimeoutSeconds);
            root.addProperty("sessionTimeoutSeconds", sessionTimeoutSeconds);
            root.addProperty("undoMode", undoMode);
            root.addProperty("maxUndoDepth", maxUndoDepth);
            root.addProperty("permissionLevelAdmin", permissionLevelAdmin);
            root.addProperty("permissionLevelUse", permissionLevelUse);
            root.addProperty("texturePayloadsPerTick", texturePayloadsPerTick);
            root.addProperty("resourcePackPort", resourcePackPort);
            root.addProperty("reloadDebounceMs", reloadDebounceMs);
            root.addProperty("joinDebounceMs", joinDebounceMs);
            root.addProperty("helperEnabled", helperEnabled);
            root.addProperty("helperName", helperName);
            root.addProperty("helperSkin", helperSkin);
            root.addProperty("helperHologram", helperHologram);
            root.addProperty("rpEnforceOnJoin", rpEnforceOnJoin);
            root.addProperty("rpPromptMessage", rpPromptMessage);
            root.addProperty("rpKickMessage", rpKickMessage);
            Path tempFile = dir.resolve(CONFIG_FILE + ".tmp");
            Files.writeString(tempFile, GSON.toJson(root), StandardCharsets.UTF_8);
            java.nio.file.Files.move(tempFile, file,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to save config", e);
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private static int getInt(JsonObject obj, String key, int def) {
        return obj.has(key) ? obj.get(key).getAsInt() : def;
    }

    private static long getLong(JsonObject obj, String key, long def) {
        return obj.has(key) ? obj.get(key).getAsLong() : def;
    }

    private static String getString(JsonObject obj, String key, String def) {
        return obj.has(key) ? obj.get(key).getAsString() : def;
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }

    private CustomBlocksConfig() {} // static-only
}
