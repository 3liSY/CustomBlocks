package com.customblocks.core;

import com.customblocks.CustomBlocksMod;
import com.customblocks.gui.ChatHelper;
import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Phase N — Welcome experience. Tracks first-time {@code /cb} users per server,
 * sends a welcome chat message with clickable buttons, and offers a voice picker.
 */
public final class WelcomeManager {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Path DATA_PATH = Path.of("config/customblocks/players.json.gz");
    private static final Set<UUID> KNOWN_PLAYERS = ConcurrentHashMap.newKeySet();
    private static volatile boolean loaded = false;

    private WelcomeManager() {}

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public static void load() {
        loaded = true;
        if (!Files.isRegularFile(DATA_PATH)) {
            KNOWN_PLAYERS.clear();
            return;
        }
        try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(DATA_PATH));
             BufferedReader reader = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8))) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            KNOWN_PLAYERS.clear();
            if (root != null && root.has("players")) {
                for (JsonElement e : root.getAsJsonArray("players")) {
                    try {
                        KNOWN_PLAYERS.add(UUID.fromString(e.getAsString()));
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ex) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not load players.json.gz: {}", ex.getMessage());
        }
    }

    public static void save() {
        if (!loaded) return;
        try {
            Files.createDirectories(DATA_PATH.getParent());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (UUID u : KNOWN_PLAYERS) arr.add(u.toString());
            root.add("players", arr);
            Path tmp = Path.of(DATA_PATH + ".tmp");
            try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(tmp));
                 BufferedWriter w = new BufferedWriter(new OutputStreamWriter(gz, StandardCharsets.UTF_8))) {
                GSON.toJson(root, w);
            }
            Files.move(tmp, DATA_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to save players.json.gz", ex);
        }
    }

    // ── First-use detection ──────────────────────────────────────────────────

    /** Returns true if this player has never used /cb on this server. */
    public static boolean isFirstUse(UUID uuid) {
        return !KNOWN_PLAYERS.contains(uuid);
    }

    /** Mark a player as known (called on first /cb command). */
    public static void markKnown(UUID uuid, MinecraftServer server) {
        if (KNOWN_PLAYERS.add(uuid)) {
            if (server != null) {
                server.execute(WelcomeManager::save);
            } else {
                save();
            }
        }
    }

    // ── Welcome message ──────────────────────────────────────────────────────

    /** Send the welcome chat with clickable buttons. */
    public static void sendWelcome(ServerPlayerEntity player) {
        String greeting = VoiceCatalog.format("welcome.greeting");

        // Main message
        ChatHelper.info(player, greeting);

        // Clickable action buttons
        MutableText buttons = Text.literal("     ");
        buttons.append(Text.literal("§a§l[Open Menu]").styled(s -> s
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cb menu"))
                .withUnderline(true)));
        buttons.append(Text.literal("  "));
        buttons.append(Text.literal("§e§l[Read Book]").styled(s -> s
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cb welcome"))
                .withUnderline(true)));
        buttons.append(Text.literal("  "));
        buttons.append(Text.literal("§d§l[Pick Voice]").styled(s -> s
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cb config"))
                .withUnderline(true)));
        player.sendMessage(buttons, false);
    }

    /**
     * Called on every /cb invocation. If first-use, sends welcome + marks as known.
     * Returns true if welcome was sent (so caller can skip normal flow if desired).
     */
    public static boolean checkAndWelcome(ServerPlayerEntity player) {
        if (!isFirstUse(player.getUuid())) return false;
        markKnown(player.getUuid(), player.getServer());
        sendWelcome(player);
        return true;
    }
}
