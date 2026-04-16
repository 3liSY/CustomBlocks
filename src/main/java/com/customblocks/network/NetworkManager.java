package com.customblocks.network;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.network.sync.TextureQueue;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralised network manager for all server → client communication.
 * <p>
 * Replaces scattered {@code broadcastUpdate()} calls with a single entry point.
 * Uses a {@link TextureQueue} for drip-feed sending and deduplication.
 */
public final class NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");

    // ── Per-player pending texture queues ────────────────────────────────────
    private static final ConcurrentHashMap<UUID, TextureQueue> PLAYER_QUEUES = new ConcurrentHashMap<>();

    // ── Broadcast API ────────────────────────────────────────────────────────

    /**
     * Broadcast a slot update to ALL online players.
     * The payload is enqueued and drip-fed over subsequent ticks.
     */
    public static void broadcastUpdate(MinecraftServer server, SlotUpdatePayload payload) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            enqueueForPlayer(player, payload);
        }
    }

    /**
     * Send a slot update to a SINGLE player.
     */
    public static void sendToPlayer(ServerPlayerEntity player, SlotUpdatePayload payload) {
        enqueueForPlayer(player, payload);
    }

    /**
     * Send a full metadata sync to a player (on join).
     * Only sends metadata — textures are loaded lazily or via drip-feed.
     * <p>
     * A {@code sync_done} sentinel is enqueued last so the client knows exactly
     * when all join textures have been queued, allowing it to fire a single
     * resource-pack reload rather than a time-based debounce that may fire
     * mid-burst on slow (internet) connections.
     */
    public static void sendFullSync(ServerPlayerEntity player) {
        List<FullSyncPayload.SlotEntry> entries = new ArrayList<>();
        for (SlotData data : SlotManager.allSlots()) {
            entries.add(new FullSyncPayload.SlotEntry(
                    data.index, data.customId, data.displayName,
                    null,  // no texture in full sync — sent via drip-feed
                    data.lightLevel, data.hardness, data.soundType, data.animMeta));
        }
        byte[] tabIcon = SlotManager.getTabIconTexture();
        FullSyncPayload syncPayload = new FullSyncPayload(entries, tabIcon);
        ServerPlayNetworking.send(player, syncPayload);

        // Drip-feed ALL textures so the client has them for resource-pack generation.
        // Without this, the client's SlotManager has null textures after FullSyncPayload
        // and ResourcePackGenerator writes placeholders (purple/black missing textures).
        TextureQueue queue = getOrCreateQueue(player);
        for (SlotData data : SlotManager.allSlots()) {
            if (data.texture != null && data.texture.length > 0) {
                queue.enqueue(new SlotUpdatePayload("add", data.index, data.customId,
                        data.displayName, data.texture,
                        data.lightLevel, data.hardness, data.soundType, null, null, data.animMeta));
            }
            // Also send per-face texture overrides
            for (var face : data.faceTextures.entrySet()) {
                queue.enqueue(new SlotUpdatePayload("setface", data.index, data.customId,
                        null, face.getValue(),
                        data.lightLevel, data.hardness, data.soundType, face.getKey()));
            }
        }

        // Sentinel: tells the client that every join texture has been queued.
        // The client uses this to fire exactly one resource-pack reload instead
        // of relying on a fixed debounce timer that can fire mid-burst on
        // slower internet connections, causing cascading reloads and a disconnect.
        queue.enqueue(new SlotUpdatePayload("sync_done", -1, "", null,
                new byte[0], 0, 0f, "stone"));
    }

    /**
     * Broadcast full sync to ALL online players (used for /cb reload).
     */
    public static void broadcastFullSync(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            sendFullSync(player);
        }
    }

    // ── Tick-based drip-feed ─────────────────────────────────────────────────

    /**
     * Called every server tick. Drains pending payloads and sends them.
     */
    public static void onServerTick(MinecraftServer server) {
        int perTick = CustomBlocksConfig.texturePayloadsPerTick;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            TextureQueue queue = PLAYER_QUEUES.get(player.getUuid());
            if (queue == null || queue.isEmpty()) continue;

            SlotUpdatePayload[] batch = queue.drain(perTick);
            for (SlotUpdatePayload payload : batch) {
                try {
                    ServerPlayNetworking.send(player, payload);
                } catch (Exception e) {
                    LOGGER.warn("[CustomBlocks] Failed to send payload to {}: {}",
                            player.getName().getString(), e.getMessage());
                }
            }
        }
    }

    // ── Player lifecycle ─────────────────────────────────────────────────────

    /** Called when a player disconnects — cleans up their queue. */
    public static void onPlayerDisconnect(ServerPlayerEntity player) {
        TextureQueue queue = PLAYER_QUEUES.remove(player.getUuid());
        if (queue != null) queue.clear();
    }

    /** Called when a player joins — sends full sync + mandatory resource pack. */
    public static void onPlayerJoin(ServerPlayerEntity player) {
        sendFullSync(player);

        // ── Mandatory Resource Pack Enforcement ──────────────────────────────
        // Delayed by 40 ticks (2 seconds) to ensure the HTTP server has the pack
        // ZIP ready. Uses Minecraft's native sendResourcePackUrl API.
        if (com.customblocks.CustomBlocksConfig.rpEnforceOnJoin
                && ResourcePackServer.isRunning()
                && ResourcePackServer.activePort() > 0) {
            player.getServer().execute(() -> {
                // Schedule on a slight delay so the pack ZIP is built
                final java.util.UUID playerId = player.getUuid();
                new Thread(() -> {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    player.getServer().execute(() -> {
                        // Verify player is still online
                        ServerPlayerEntity p = player.getServer().getPlayerManager().getPlayer(playerId);
                        if (p == null) return;
                        String url = ResourcePackServer.getPackUrl(p.getServer());
                        String hash = ResourcePackServer.getHash();
                        if (hash == null) hash = "";
                        net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket packet =
                                new net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket(
                                        java.util.UUID.randomUUID(),
                                        url,
                                        hash,
                                        true, // required
                                        java.util.Optional.of(net.minecraft.text.Text.literal(
                                                com.customblocks.CustomBlocksConfig.rpPromptMessage))
                                );
                        p.networkHandler.sendPacket(packet);
                        LOGGER.info("[CustomBlocks] Sent mandatory resource pack to {}", p.getName().getString());
                    });
                }, "CustomBlocks-RPSend").start();
            });
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static void enqueueForPlayer(ServerPlayerEntity player, SlotUpdatePayload payload) {
        getOrCreateQueue(player).enqueue(payload);
    }

    private static TextureQueue getOrCreateQueue(ServerPlayerEntity player) {
        return PLAYER_QUEUES.computeIfAbsent(player.getUuid(), k -> new TextureQueue());
    }
}
