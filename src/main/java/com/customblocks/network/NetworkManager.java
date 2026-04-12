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

        // Enqueue all textures for drip-feed delivery
        TextureQueue queue = getOrCreateQueue(player);
        for (SlotData data : SlotManager.allSlots()) {
            if (data.texture != null && data.texture.length > 0) {
                queue.enqueue(new SlotUpdatePayload("retexture", data.index, data.customId,
                        null, data.texture, data.lightLevel, data.hardness, data.soundType));
            }
            // Also send face textures
            for (var faceEntry : data.faceTextures.entrySet()) {
                queue.enqueue(new SlotUpdatePayload("setface", data.index, data.customId,
                        null, faceEntry.getValue(), data.lightLevel, data.hardness,
                        data.soundType, faceEntry.getKey()));
            }
        }
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
            if (queue == null) continue;

            if (queue.isEmpty()) {
                if (!queue.hasNotifiedSyncComplete) {
                    queue.hasNotifiedSyncComplete = true;
                    try {
                        ServerPlayNetworking.send(player, new SyncCompletePayload());
                    } catch (Exception e) {
                        LOGGER.warn("[CustomBlocks] Failed to send SyncCompletePayload to {}: {}",
                                player.getName().getString(), e.getMessage());
                    }
                }
                continue;
            }

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

    /** Called when a player joins — sends full sync. */
    public static void onPlayerJoin(ServerPlayerEntity player) {
        sendFullSync(player);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static void enqueueForPlayer(ServerPlayerEntity player, SlotUpdatePayload payload) {
        getOrCreateQueue(player).enqueue(payload);
    }

    private static TextureQueue getOrCreateQueue(ServerPlayerEntity player) {
        return PLAYER_QUEUES.computeIfAbsent(player.getUuid(), k -> new TextureQueue());
    }
}
