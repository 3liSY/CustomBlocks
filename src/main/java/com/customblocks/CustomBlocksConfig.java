package com.customblocks;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.customblocks.core.VoiceCatalog;
import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Centralised configuration for CustomBlocks.
 * Persisted as {@code config/customblocks/config.json}.
 * <p>
 * All fields are volatile so read access from any thread sees the latest value
 * without synchronisation; writes only happen on the server thread via {@link #load()} / {@link #save()}.
 */
@SuppressFBWarnings("PA_PUBLIC_PRIMITIVE_ATTRIBUTE")
public final class CustomBlocksConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_DIR  = "config/customblocks";
    private static final String CONFIG_FILE = "config.json";

    // ── Slot System ──────────────────────────────────────────────────────────
    /** Maximum number of block slots. Requires restart to take effect. */
    public static volatile int maxSlots = 600;

    // ── Image Processing ─────────────────────────────────────────────────────
    /** Default texture size in pixels (16–256). */
    public static volatile int defaultTextureSize = 128;
    /** Background-removal colour-distance tolerance (0 = disabled, 1–100). */
    public static volatile int bgRemovalTolerance = 30;
    /** Use YCbCr luminance/chroma math for white-background cleanup; false falls back to CIE-Lab Delta-E. */
    public static volatile boolean bgRemovalUseYcbcr = true;
    /** HTTP download timeout in seconds. */
    public static volatile int downloadTimeoutSeconds = 15;
    /**
     * 1.25 — Maximum GIF file size in MB before upload is rejected (pre-check to prevent OOM/crash).
     * Minimum enforced at runtime: 1. Default: 2. Range: 1–10.
     */
    public static volatile int maxGifSizeMb = 2;

    // ── Tool Sessions ────────────────────────────────────────────────────────
    /** Tool session timeout in seconds (rectangle wand, etc.). 0 = no timeout. */
    public static volatile int sessionTimeoutSeconds = 300;

    // ── Undo System ──────────────────────────────────────────────────────────
    /** Undo mode: "global" = single shared stack, "per_player" = one stack per player, "both" = both available. */
    public static volatile String undoMode = "both";
    /** Maximum undo depth (per stack). Expanded to 10000 in v2 for "unlimited undo" experience. */
    public static volatile int maxUndoDepth = 10000;
    /** Minimum selection size that requires a second click to confirm bulk deletion (H2). */
    public static volatile int bulkConfirmThreshold = 10;

    // ── Permissions ──────────────────────────────────────────────────────────
    /** Default OP level required for admin commands (create/delete/edit). Hardened in v2 per A6. */
    public static volatile int permissionLevelAdmin = 4;
    /** Default OP level required for use commands (give/gui). */
    public static volatile int permissionLevelUse = 0;
    /**
     * When {@code true}, a saved {@link #permissionLevelAdmin} value of {@code 2} is kept on load (v1-era opt-out).
     * When {@code false} (default), loading {@code permissionLevelAdmin: 2} from disk is migrated to {@code 4} (X1 / A6).
     */
    public static volatile boolean permissionLevelAdminUser = false;

    /**
     * Per-node vanilla OP fallbacks when Fabric Permissions API is absent (A6).
     * Each value is clamped to 0–4; LuckPerms still overrides when installed.
     */
    public static volatile int permissionFallbackUse = 0;
    public static volatile int permissionFallbackEdit = 0;
    public static volatile int permissionFallbackCreate = 4;
    public static volatile int permissionFallbackDelete = 4;
    public static volatile int permissionFallbackBulk = 4;
    public static volatile int permissionFallbackFavorites = 4;
    public static volatile int permissionFallbackConfig = 4;
    public static volatile int permissionFallbackAdmin = 4;
    public static volatile int permissionFallbackPanic = 4;
    public static volatile int permissionFallbackUndo = 4;
    public static volatile int permissionFallbackMarketplacePublish = 2;
    public static volatile int permissionFallbackAi = 4;
    public static volatile int permissionFallbackDevConsole = 4;

    // ── Item Lore & UI ───────────────────────────────────────────────────────
    /** Whether to hide the "Custom Blocks" creative tab text from item lore. */
    public static volatile boolean hideCustomBlockText = false;
    /** Whether to hide category badges from item lore entirely. */
    public static volatile boolean hideCategoryBadge = false;
    /** Colour tool mode: "unset", "corners_only", "corners_and_trapped". */
    public static volatile String colorToolBackgroundMode = "unset";
    /** Editable built-in shade for green recolor tools. */
    public static volatile String triangleGreenHex = "#1E8C1E";
    /** Editable built-in shade for yellow recolor tools. */
    public static volatile String triangleYellowHex = "#F0C814";
    /**
     * 1.28 — What to do when a color square/triangle targets a variant that doesn't exist yet.
     * "use_base" (default): silently use the base block texture and create the variant automatically.
     * "auto_create": same as use_base but shows a brief action bar note to the player.
     * "error": show error and stop (old behavior).
     */
    public static volatile String colorSquareFallbackMode = "use_base";

    // ── Network ──────────────────────────────────────────────────────────────
    /** Number of texture payloads to drip-feed per server tick. */
    public static volatile int texturePayloadsPerTick = 8;
    /** 1.12 — Max uncompressed GIF frame-strip bytes before trimming frames (4 MB default). */
    public static volatile long maxGifStripBytes = 4_000_000L;
    /** Internal HTTP server port for resource pack hosting (0 = disabled/auto). */
    public static volatile int resourcePackPort = 8080;
    /** Debounce time for live-edit resource pack reloads (ms). */
    public static volatile long reloadDebounceMs = 2000;
    // joinDebounceMs removed in 1.20 — replaced by count-verified SyncCompletePayload.
    /** Hardcoded Cloud Vault URL — not configurable to prevent OPs redirecting uploads. */
    public static final String cloudShareUrl = "https://cb-cloud-vault.cbbblocksvault.workers.dev";
    /** Enables Cloud Vault upload/download. */
    public static volatile boolean cloudShareEnabled = true;
    /** 1.13 — Shared secret for authenticating pack uploads to Cloud Vault (set in config file only, never in GUI). */
    public static volatile String cloudPackSecret = "";


    // ── Assistant NPC (The Helper) ───────────────────────────────────────────
    /** Whether the assistant AI is currently active in the world. */
    public static volatile boolean aiEnabled = false;
    /** The assistant AI display name. */
    public static volatile String aiName = "CustomBlocks AI";
    /** Branded chat voice (Phase B2 phrases); migrated from legacy {@code aiStyle}. */
    public static volatile String voiceMode = "friendly";

    public static final Set<String> VOICE_MODES =
        Set.of("friendly", "professional", "royal", "minimal", "arabic", "silly");
    /** Whether the live status hologram is visible. */
    public static volatile boolean aiHologram = true;
    /** Phase 11.1 — AI image provider: "openai" or "stability". Empty = procedural only. */
    public static volatile String aiApiProvider = "";
    /** Phase 11.1 — API key for the AI image provider. Empty = procedural fallback. */
    public static volatile String aiApiKey = "";
    /** Phase 11.1 — Maximum number of AI texture variations to generate (1–3). */
    public static volatile int aiMaxVariations = 3;
    /** Phase 11.1 — Texture generation style hint: "pixel_art" or "natural". */
    public static volatile String aiTextureStyle = "pixel_art";

    /**
     * Phase B4 did-you-mean for unknown first argument: {@code off}, {@code strict} (distance ≤1),
     * {@code smart} (≤3), {@code genius} (smart + block-id gift + light memory).
     */
    public static volatile String didYouMeanMode = "smart";

    // ── Safety Nets (Phase Q) ────────────────────────────────────────────────
    /** Minutes between auto-snapshots (0 = disabled). Default 30. */
    public static volatile int autoSnapshotMinutes = 30;

    // ── Performance (Phase D) ────────────────────────────────────────────────
    /** Controls client-side pack-reload debounce timing (ms).
     *  Read by CustomBlocksClient.fastReloadDebounceMs() and fullReloadDebounceMs().
     *  0 = reload immediately on every edit. 10000 = lazy/batched reloads. Default 300. */
    public static volatile int instantClickAggressivenessMs = 300;

    // ── Feedback Layers (Phase F) ────────────────────────────────────────────
    /** Master toggle for feedback sounds. */
    public static volatile boolean soundsEnabled = true;
    /** Master toggle for feedback particles. */
    public static volatile boolean particlesEnabled = true;
    /** Per-category sound toggles (Phase F4). */
    @SuppressFBWarnings("MS_MUTABLE_COLLECTION_PKGPROTECT")
    public static final Map<String, Boolean> soundCategories = new LinkedHashMap<>();
    /** Per-category particle toggles (Phase F5). */
    @SuppressFBWarnings("MS_MUTABLE_COLLECTION_PKGPROTECT")
    public static final Map<String, Boolean> particleCategories = new LinkedHashMap<>();

    static {
        resetFeedbackCategoryDefaults(soundCategories);
        resetFeedbackCategoryDefaults(particleCategories);
    }

    // ── Marketplace (Phase L) ────────────────────────────────────────────────
    /** Enable the in-game marketplace UI (separate from cloudShareEnabled). */
    public static volatile boolean marketplaceEnabled = true; // 8.4 — gate wired in GuiManager.openMarketGui()

    // ── Holograms (Phase I) ──────────────────────────────────────────────────
    /** Whether floating name holograms appear above placed custom blocks. */
    public static volatile boolean hologramEnabled = false;
    /** Height in blocks above the block's base at which the hologram marker floats. */
    public static volatile float hologramHeight = 1.5f;
    /** MC formatting-code prefix applied to all hologram labels (e.g. "§e§l"). */
    public static volatile String hologramColor = "§e§l";

    // ── Notifications (Phase M) ──────────────────────────────────────────────
    /** Discord webhook URL for block create/delete/panic events. Empty = disabled. */
    public static volatile String discordWebhookUrl = "";

    public static void setResourcePackPort(int port) {
        resourcePackPort = port;
        save();
    }

    public static boolean isCloudShareEnabled() {
        return cloudShareEnabled; // URL is now a hardcoded constant — always present
    }

    public static String normalizedCloudShareUrl() {
        return cloudShareUrl; // URL is a hardcoded constant — no trimming needed
    }

    public static String normalizeVoiceMode(String raw) {
        if (raw == null) return "friendly";
        String v = raw.toLowerCase(Locale.ROOT).trim();
        return VOICE_MODES.contains(v) ? v : "friendly";
    }

    public static String normalizeDidYouMeanMode(String raw) {
        if (raw == null) return "smart";
        String s = raw.toLowerCase(Locale.ROOT).trim();
        return switch (s) {
            case "off", "strict", "smart", "genius" -> s;
            default -> "smart";
        };
    }

    public static String normalizeAiProvider(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(Locale.ROOT).trim();
        return switch (s) {
            case "openai", "stability" -> s;
            default -> "";
        };
    }

    public static String normalizeAiTextureStyle(String raw) {
        if (raw == null) return "pixel_art";
        String s = raw.toLowerCase(Locale.ROOT).trim();
        return switch (s) {
            case "pixel_art", "natural", "flat" -> s;
            default -> "pixel_art";
        };
    }

    private static String mapLegacyAiStyleToVoiceMode(String legacy) {
        if (legacy == null) return "friendly";
        String s = legacy.trim().toLowerCase(Locale.ROOT);
        if ("echo".equals(s)) return "friendly";
        if ("pro".equals(s)) return "professional";
        return normalizeVoiceMode(s);
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
            boolean shouldRewrite = missingManagedKeys(root);

            maxSlots              = getInt(root, "maxSlots", maxSlots);
            defaultTextureSize    = getInt(root, "defaultTextureSize", defaultTextureSize);
            bgRemovalTolerance    = getInt(root, "bgRemovalTolerance", bgRemovalTolerance);
            bgRemovalUseYcbcr     = getBool(root, "bgRemovalUseYcbcr", bgRemovalUseYcbcr);
            downloadTimeoutSeconds= getInt(root, "downloadTimeoutSeconds", downloadTimeoutSeconds);
            maxGifSizeMb          = Math.max(1, getInt(root, "maxGifSizeMb", maxGifSizeMb)); // 1.25 — min 1
            sessionTimeoutSeconds = getInt(root, "sessionTimeoutSeconds", sessionTimeoutSeconds);
            undoMode              = getString(root, "undoMode", undoMode);
            maxUndoDepth          = getInt(root, "maxUndoDepth", maxUndoDepth);
            bulkConfirmThreshold  = getInt(root, "bulkConfirmThreshold", bulkConfirmThreshold);
            permissionLevelAdminUser = getBool(root, "permissionLevelAdminUser", permissionLevelAdminUser);
            int rawPermissionLevelAdmin = root.has("permissionLevelAdmin")
                ? root.get("permissionLevelAdmin").getAsInt()
                : permissionLevelAdmin;
            permissionLevelAdmin = rawPermissionLevelAdmin;
            if (rawPermissionLevelAdmin == 2 && !permissionLevelAdminUser) {
                permissionLevelAdmin = 4;
                shouldRewrite = true;
                LOGGER.warn("[CustomBlocks] Hardened permissionLevelAdmin from 2 to 4 (v2 default). "
                    + "To keep OP level 2, set \"permissionLevelAdminUser\": true and \"permissionLevelAdmin\": 2 in config.json.");
            }
            permissionLevelUse    = getInt(root, "permissionLevelUse", permissionLevelUse);

            if (!root.has("permissionFallbackUse")) {
                permissionFallbackUse = permissionLevelUse;
                permissionFallbackEdit = permissionLevelUse;
                permissionFallbackCreate = permissionLevelAdmin;
                permissionFallbackDelete = permissionLevelAdmin;
                permissionFallbackBulk = permissionLevelAdmin;
                permissionFallbackFavorites = permissionLevelAdmin;
                permissionFallbackConfig = permissionLevelAdmin;
                permissionFallbackAdmin = permissionLevelAdmin;
                permissionFallbackPanic = permissionLevelAdmin;
                permissionFallbackUndo = permissionLevelAdmin;
                permissionFallbackMarketplacePublish = 2;
                permissionFallbackAi = permissionLevelAdmin;
                permissionFallbackDevConsole = permissionLevelAdmin;
                shouldRewrite = true;
            } else {
                permissionFallbackUse = getInt(root, "permissionFallbackUse", permissionFallbackUse);
                permissionFallbackEdit = getInt(root, "permissionFallbackEdit", permissionFallbackEdit);
                permissionFallbackCreate = getInt(root, "permissionFallbackCreate", permissionFallbackCreate);
                permissionFallbackDelete = getInt(root, "permissionFallbackDelete", permissionFallbackDelete);
                permissionFallbackBulk = getInt(root, "permissionFallbackBulk", permissionFallbackBulk);
                permissionFallbackFavorites = getInt(root, "permissionFallbackFavorites", permissionFallbackFavorites);
                permissionFallbackConfig = getInt(root, "permissionFallbackConfig", permissionFallbackConfig);
                permissionFallbackAdmin = getInt(root, "permissionFallbackAdmin", permissionFallbackAdmin);
                permissionFallbackPanic = getInt(root, "permissionFallbackPanic", permissionFallbackPanic);
                permissionFallbackUndo = getInt(root, "permissionFallbackUndo", permissionFallbackUndo);
                permissionFallbackMarketplacePublish = getInt(root, "permissionFallbackMarketplacePublish", permissionFallbackMarketplacePublish);
                permissionFallbackAi = getInt(root, "permissionFallbackAi", permissionFallbackAi);
                permissionFallbackDevConsole = getInt(root, "permissionFallbackDevConsole", permissionFallbackDevConsole);
            }
            texturePayloadsPerTick= getInt(root, "texturePayloadsPerTick", texturePayloadsPerTick);
            maxGifStripBytes      = getLong(root, "maxGifStripBytes", maxGifStripBytes);
            resourcePackPort      = getInt(root, "resourcePackPort", resourcePackPort);
            reloadDebounceMs      = getLong(root, "reloadDebounceMs", reloadDebounceMs);
            // joinDebounceMs removed in 1.20
            cloudShareEnabled     = getBool(root, "cloudShareEnabled", cloudShareEnabled);
            cloudPackSecret       = getString(root, "cloudPackSecret", cloudPackSecret);
            aiEnabled             = getBool(root, "aiEnabled", getBool(root, "helperEnabled", aiEnabled));
            aiName                = getString(root, "aiName", getString(root, "helperName", aiName));
            if (root.has("voiceMode")) {
                voiceMode = normalizeVoiceMode(getString(root, "voiceMode", voiceMode));
            } else if (root.has("aiStyle")) {
                voiceMode = mapLegacyAiStyleToVoiceMode(getString(root, "aiStyle", getString(root, "helperSkin", "Echo")));
                shouldRewrite = true;
            } else {
                voiceMode = normalizeVoiceMode(voiceMode);
            }
            aiHologram            = getBool(root, "aiHologram", getBool(root, "helperHologram", aiHologram));
            aiApiProvider         = normalizeAiProvider(getString(root, "aiApiProvider", aiApiProvider));
            aiApiKey              = getString(root, "aiApiKey", aiApiKey);
            aiMaxVariations       = Math.max(1, Math.min(8, getInt(root, "aiMaxVariations", aiMaxVariations)));
            aiTextureStyle        = normalizeAiTextureStyle(getString(root, "aiTextureStyle", aiTextureStyle));
            didYouMeanMode         = normalizeDidYouMeanMode(getString(root, "didYouMeanMode", didYouMeanMode));
            autoSnapshotMinutes    = Math.max(0, Math.min(1440, getInt(root, "autoSnapshotMinutes", autoSnapshotMinutes)));
            instantClickAggressivenessMs = Math.max(0, Math.min(10000, getInt(root, "instantClickAggressivenessMs", instantClickAggressivenessMs)));
            soundsEnabled          = getBool(root, "soundsEnabled", soundsEnabled);
            particlesEnabled       = getBool(root, "particlesEnabled", particlesEnabled);
            loadFeedbackCategories(root, "soundCategories", soundCategories);
            loadFeedbackCategories(root, "particleCategories", particleCategories);
            marketplaceEnabled     = getBool(root, "marketplaceEnabled", marketplaceEnabled);
            discordWebhookUrl     = getString(root, "discordWebhookUrl", discordWebhookUrl);
            hologramEnabled       = getBool(root, "hologramEnabled", hologramEnabled);
            hologramHeight        = root.has("hologramHeight") ? root.get("hologramHeight").getAsFloat() : hologramHeight;
            hologramHeight        = Math.max(0.5f, Math.min(5.0f, hologramHeight));
            hologramColor         = getString(root, "hologramColor", hologramColor);
            hideCustomBlockText   = getBool(root, "hideCustomBlockText", hideCustomBlockText);
            hideCategoryBadge     = getBool(root, "hideCategoryBadge", hideCategoryBadge);
            colorToolBackgroundMode = getString(root, "colorToolBackgroundMode", colorToolBackgroundMode);
            triangleGreenHex      = normalizeHexColor(getString(root, "triangleGreenHex", triangleGreenHex), triangleGreenHex);
            triangleYellowHex     = normalizeHexColor(getString(root, "triangleYellowHex", triangleYellowHex), triangleYellowHex);
            colorSquareFallbackMode = getString(root, "colorSquareFallbackMode", colorSquareFallbackMode);

            // Clamp values
            int clampedMaxSlots = Math.max(1, Math.min(8192, maxSlots));
            int clampedDefaultTextureSize = Math.max(16, Math.min(256, defaultTextureSize));
            int clampedBgRemovalTolerance = Math.max(0, Math.min(100, bgRemovalTolerance));
            int clampedDownloadTimeoutSeconds = Math.max(1, Math.min(120, downloadTimeoutSeconds));
            int clampedSessionTimeoutSeconds = Math.max(0, Math.min(3600, sessionTimeoutSeconds));
            int clampedMaxUndoDepth = Math.max(1, Math.min(100_000, maxUndoDepth));
            int clampedBulkConfirmThreshold = Math.max(1, Math.min(1000, bulkConfirmThreshold));
            int clampedPermissionLevelAdmin = Math.max(0, Math.min(4, permissionLevelAdmin));
            int clampedPermissionLevelUse = Math.max(0, Math.min(4, permissionLevelUse));
            int cU = Math.max(0, Math.min(4, permissionFallbackUse));
            int cE = Math.max(0, Math.min(4, permissionFallbackEdit));
            int cC = Math.max(0, Math.min(4, permissionFallbackCreate));
            int cD = Math.max(0, Math.min(4, permissionFallbackDelete));
            int cB = Math.max(0, Math.min(4, permissionFallbackBulk));
            int cF = Math.max(0, Math.min(4, permissionFallbackFavorites));
            int cCfg = Math.max(0, Math.min(4, permissionFallbackConfig));
            int cAd = Math.max(0, Math.min(4, permissionFallbackAdmin));
            int cPn = Math.max(0, Math.min(4, permissionFallbackPanic));
            int cUn = Math.max(0, Math.min(4, permissionFallbackUndo));
            int cMp = Math.max(0, Math.min(4, permissionFallbackMarketplacePublish));
            int cAi = Math.max(0, Math.min(4, permissionFallbackAi));
            int cDc = Math.max(0, Math.min(4, permissionFallbackDevConsole));
            int clampedTexturePayloadsPerTick = Math.max(1, Math.min(50, texturePayloadsPerTick));
            long clampedMaxGifStripBytes = Math.max(500_000L, Math.min(16_000_000L, maxGifStripBytes));

            shouldRewrite |= clampedMaxSlots != maxSlots;
            shouldRewrite |= clampedDefaultTextureSize != defaultTextureSize;
            shouldRewrite |= clampedBgRemovalTolerance != bgRemovalTolerance;
            shouldRewrite |= clampedDownloadTimeoutSeconds != downloadTimeoutSeconds;
            shouldRewrite |= clampedSessionTimeoutSeconds != sessionTimeoutSeconds;
            shouldRewrite |= clampedMaxUndoDepth != maxUndoDepth;
            shouldRewrite |= clampedBulkConfirmThreshold != bulkConfirmThreshold;
            shouldRewrite |= clampedPermissionLevelAdmin != permissionLevelAdmin;
            shouldRewrite |= clampedPermissionLevelUse != permissionLevelUse;
            shouldRewrite |= cU != permissionFallbackUse;
            shouldRewrite |= cE != permissionFallbackEdit;
            shouldRewrite |= cC != permissionFallbackCreate;
            shouldRewrite |= cD != permissionFallbackDelete;
            shouldRewrite |= cB != permissionFallbackBulk;
            shouldRewrite |= cF != permissionFallbackFavorites;
            shouldRewrite |= cCfg != permissionFallbackConfig;
            shouldRewrite |= cAd != permissionFallbackAdmin;
            shouldRewrite |= cPn != permissionFallbackPanic;
            shouldRewrite |= cUn != permissionFallbackUndo;
            shouldRewrite |= cMp != permissionFallbackMarketplacePublish;
            shouldRewrite |= cAi != permissionFallbackAi;
            shouldRewrite |= cDc != permissionFallbackDevConsole;
            shouldRewrite |= clampedTexturePayloadsPerTick != texturePayloadsPerTick;
            shouldRewrite |= clampedMaxGifStripBytes != maxGifStripBytes;

            maxSlots              = clampedMaxSlots;
            defaultTextureSize    = clampedDefaultTextureSize;
            bgRemovalTolerance    = clampedBgRemovalTolerance;
            downloadTimeoutSeconds= clampedDownloadTimeoutSeconds;
            sessionTimeoutSeconds = clampedSessionTimeoutSeconds;
            maxUndoDepth          = clampedMaxUndoDepth;
            bulkConfirmThreshold  = clampedBulkConfirmThreshold;
            permissionLevelAdmin  = clampedPermissionLevelAdmin;
            permissionLevelUse    = clampedPermissionLevelUse;
            permissionFallbackUse = cU;
            permissionFallbackEdit = cE;
            permissionFallbackCreate = cC;
            permissionFallbackDelete = cD;
            permissionFallbackBulk = cB;
            permissionFallbackFavorites = cF;
            permissionFallbackConfig = cCfg;
            permissionFallbackAdmin = cAd;
            permissionFallbackPanic = cPn;
            permissionFallbackUndo = cUn;
            permissionFallbackMarketplacePublish = cMp;
            permissionFallbackAi = cAi;
            permissionFallbackDevConsole = cDc;
            texturePayloadsPerTick= clampedTexturePayloadsPerTick;
            maxGifStripBytes      = clampedMaxGifStripBytes;

            if (!undoMode.equals("global") && !undoMode.equals("per_player") && !undoMode.equals("both")) {
                LOGGER.warn("[CustomBlocks] Invalid undoMode '{}', defaulting to 'both'", undoMode);
                undoMode = "both";
                shouldRewrite = true;
            }
            if (!colorToolBackgroundMode.equals("unset")
                    && !colorToolBackgroundMode.equals("corners_only")
                    && !colorToolBackgroundMode.equals("corners_and_trapped")) {
                LOGGER.warn("[CustomBlocks] Invalid colorToolBackgroundMode '{}', defaulting to 'unset'", colorToolBackgroundMode);
                colorToolBackgroundMode = "unset";
                shouldRewrite = true;
            }

            if (shouldRewrite) {
                save();
                LOGGER.info("[CustomBlocks] Config migrated/backfilled at {}", file);
            }

            LOGGER.info("[CustomBlocks] Config loaded: maxSlots={}, undoMode={}, bgTolerance={}, bgMath={}",
                    maxSlots, undoMode, bgRemovalTolerance, bgRemovalUseYcbcr ? "YCbCr" : "Lab");
            if (isCloudShareEnabled()) {
                LOGGER.info("[CustomBlocks] Cloud Vault: ENABLED -> {}", normalizedCloudShareUrl());
            } else {
                LOGGER.info("[CustomBlocks] Cloud Vault: DISABLED -> local-only share codes");
            }
            VoiceCatalog.invalidate();
        } catch (IOException | RuntimeException e) {
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
            root.addProperty("bgRemovalUseYcbcr", bgRemovalUseYcbcr);
            root.addProperty("downloadTimeoutSeconds", downloadTimeoutSeconds);
            root.addProperty("maxGifSizeMb", maxGifSizeMb); // 1.25
            root.addProperty("sessionTimeoutSeconds", sessionTimeoutSeconds);
            root.addProperty("undoMode", undoMode);
            root.addProperty("maxUndoDepth", maxUndoDepth);
            root.addProperty("bulkConfirmThreshold", bulkConfirmThreshold);
            root.addProperty("permissionLevelAdmin", permissionLevelAdmin);
            root.addProperty("permissionLevelAdminUser", permissionLevelAdminUser);
            root.addProperty("permissionLevelUse", permissionLevelUse);
            root.addProperty("permissionFallbackUse", permissionFallbackUse);
            root.addProperty("permissionFallbackEdit", permissionFallbackEdit);
            root.addProperty("permissionFallbackCreate", permissionFallbackCreate);
            root.addProperty("permissionFallbackDelete", permissionFallbackDelete);
            root.addProperty("permissionFallbackBulk", permissionFallbackBulk);
            root.addProperty("permissionFallbackFavorites", permissionFallbackFavorites);
            root.addProperty("permissionFallbackConfig", permissionFallbackConfig);
            root.addProperty("permissionFallbackAdmin", permissionFallbackAdmin);
            root.addProperty("permissionFallbackPanic", permissionFallbackPanic);
            root.addProperty("permissionFallbackUndo", permissionFallbackUndo);
            root.addProperty("permissionFallbackMarketplacePublish", permissionFallbackMarketplacePublish);
            root.addProperty("permissionFallbackAi", permissionFallbackAi);
            root.addProperty("permissionFallbackDevConsole", permissionFallbackDevConsole);
            root.addProperty("texturePayloadsPerTick", texturePayloadsPerTick);
            root.addProperty("maxGifStripBytes", maxGifStripBytes);
            root.addProperty("resourcePackPort", resourcePackPort);
            root.addProperty("reloadDebounceMs", reloadDebounceMs);
            root.addProperty("cloudShareEnabled", cloudShareEnabled);
            root.addProperty("cloudPackSecret", cloudPackSecret);
            root.addProperty("aiEnabled", aiEnabled);
            root.addProperty("aiName", aiName);
            root.addProperty("voiceMode", voiceMode);
            root.addProperty("didYouMeanMode", didYouMeanMode);
            root.addProperty("aiHologram", aiHologram);
            root.addProperty("aiApiProvider", aiApiProvider);
            root.addProperty("aiApiKey", aiApiKey); // config.json only — never rendered in GUI
            root.addProperty("aiMaxVariations", aiMaxVariations);
            root.addProperty("aiTextureStyle", aiTextureStyle);
            root.addProperty("hideCustomBlockText", hideCustomBlockText);
            root.addProperty("hideCategoryBadge", hideCategoryBadge);
            root.addProperty("colorToolBackgroundMode", colorToolBackgroundMode);
            root.addProperty("triangleGreenHex", normalizeHexColor(triangleGreenHex, "#1E8C1E"));
            root.addProperty("triangleYellowHex", normalizeHexColor(triangleYellowHex, "#F0C814"));
            root.addProperty("colorSquareFallbackMode", colorSquareFallbackMode);
            root.addProperty("autoSnapshotMinutes", autoSnapshotMinutes);
            root.addProperty("instantClickAggressivenessMs", instantClickAggressivenessMs);
            root.addProperty("soundsEnabled", soundsEnabled);
            root.addProperty("particlesEnabled", particlesEnabled);
            root.add("soundCategories", writeFeedbackCategories(soundCategories));
            root.add("particleCategories", writeFeedbackCategories(particleCategories));
            root.addProperty("marketplaceEnabled", marketplaceEnabled);
            root.addProperty("discordWebhookUrl", discordWebhookUrl);
            root.addProperty("hologramEnabled", hologramEnabled);
            root.addProperty("hologramHeight", hologramHeight);
            root.addProperty("hologramColor", hologramColor);
            Path tempFile = dir.resolve(CONFIG_FILE + ".tmp");
            Files.writeString(tempFile, GSON.toJson(root), StandardCharsets.UTF_8);
            java.nio.file.Files.move(tempFile, file,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            VoiceCatalog.invalidate();
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

    private static boolean missingManagedKeys(JsonObject root) {
        return !root.has("maxSlots")
            || !root.has("defaultTextureSize")
            || !root.has("bgRemovalTolerance")
            || !root.has("bgRemovalUseYcbcr")
            || !root.has("downloadTimeoutSeconds")
            || !root.has("maxGifSizeMb") // 1.25
            || !root.has("sessionTimeoutSeconds")
            || !root.has("undoMode")
            || !root.has("maxUndoDepth")
            || !root.has("bulkConfirmThreshold")
            || !root.has("permissionLevelAdmin")
            || !root.has("permissionLevelUse")
            || !root.has("permissionFallbackUse")
            || !root.has("texturePayloadsPerTick")
            || !root.has("resourcePackPort")
            || !root.has("reloadDebounceMs")
            || !root.has("cloudShareEnabled")
            || !root.has("cloudPackSecret")
            || !root.has("aiEnabled")
            || !root.has("aiName")
            || !root.has("voiceMode")
            || !root.has("didYouMeanMode")
            || !root.has("aiHologram")
            || !root.has("aiApiProvider")
            || !root.has("aiApiKey")
            || !root.has("aiMaxVariations")
            || !root.has("aiTextureStyle")
            || !root.has("hideCustomBlockText")
            || !root.has("hideCategoryBadge")
            || !root.has("colorToolBackgroundMode")
            || !root.has("triangleGreenHex")
            || !root.has("triangleYellowHex")
            || !root.has("autoSnapshotMinutes")
            || !root.has("instantClickAggressivenessMs")
            || !root.has("soundsEnabled")
            || !root.has("particlesEnabled")
            || !root.has("soundCategories")
            || !root.has("particleCategories")
            || !root.has("marketplaceEnabled");
    }

    /** Returns true when player has chosen a color tool mode in /cb config. */
    public static boolean isColorToolModeConfigured() {
        return !"unset".equals(colorToolBackgroundMode);
    }

    /** Mode B: corner fill + trapped-hole fill. */
    public static boolean useTrappedHoleFill() {
        return "corners_and_trapped".equals(colorToolBackgroundMode);
    }

    /** Resolve built-in triangle colour, allowing config-tuned shades. */
    public static int[] builtInTriangleRgb(String colorKey, int fallbackR, int fallbackG, int fallbackB) {
        String key = colorKey == null ? "" : colorKey.toLowerCase().trim();
        String hex = switch (key) {
            case "green" -> triangleGreenHex;
            case "yellow" -> triangleYellowHex;
            default -> null;
        };
        if (hex == null) return new int[]{fallbackR, fallbackG, fallbackB};
        int rgb = parseHexColor(hex, (fallbackR << 16) | (fallbackG << 8) | fallbackB);
        return new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
    }

    private static String normalizeHexColor(String raw, String fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();
        if (!s.startsWith("#")) s = "#" + s;
        if (!s.matches("(?i)^#[0-9a-f]{6}$")) return fallback;
        return s.toUpperCase();
    }

    private static int parseHexColor(String raw, int fallback) {
        String normalized = normalizeHexColor(raw, null);
        if (normalized == null) return fallback;
        try {
            return Integer.parseInt(normalized.substring(1), 16);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void resetFeedbackCategoryDefaults(Map<String, Boolean> out) {
        out.clear();
        out.put("success", true);
        out.put("error", true);
        out.put("gui", true);
        out.put("selection", true);
        out.put("bulk_complete", true);
        out.put("rp_regenerate", true);
        out.put("achievement", true);
    }

    private static void loadFeedbackCategories(JsonObject root, String key, Map<String, Boolean> target) {
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        resetFeedbackCategoryDefaults(defaults);
        target.clear();
        target.putAll(defaults);
        if (!root.has(key) || !root.get(key).isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject(key);
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
            if (!target.containsKey(k)) continue;
            try {
                target.put(k, e.getValue().getAsBoolean());
            } catch (Exception ignored) {
            }
        }
    }

    private static JsonObject writeFeedbackCategories(Map<String, Boolean> src) {
        JsonObject out = new JsonObject();
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        resetFeedbackCategoryDefaults(defaults);
        for (String key : defaults.keySet()) {
            out.addProperty(key, src.getOrDefault(key, true));
        }
        return out;
    }

    public static boolean isSoundCategoryEnabled(String key) {
        return soundsEnabled && soundCategories.getOrDefault(key, true);
    }

    public static boolean isParticleCategoryEnabled(String key) {
        return particlesEnabled && particleCategories.getOrDefault(key, true);
    }

    private CustomBlocksConfig() {} // static-only
}
