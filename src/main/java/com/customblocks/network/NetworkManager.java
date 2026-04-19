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

    // ── Packet size limits ───────────────────────────────────────────────────
    /** Warning threshold - textures larger than this will log a warning. */
    private static final int PACKET_WARN_SIZE = 500 * 1024; // 500KB
    /** Hard limit - textures larger than this are rejected to prevent client crashes. */
    private static final int PACKET_MAX_SIZE = 8 * 1024 * 1024; // 8MB (below 10MB codec limit)

    // ── Per-player pending texture queues ────────────────────────────────────
    private static final ConcurrentHashMap<UUID, TextureQueue> PLAYER_QUEUES = new ConcurrentHashMap<>();

    /**
     * Validate payload size before queueing. Returns true if payload is safe to send.
     * Logs warnings for oversized textures and rejects payloads exceeding hard limit.
     */
    private static boolean validatePayloadSize(SlotUpdatePayload payload) {
        if (payload == null) return false;
        byte[] tex = payload.texture();
        if (tex == null) return true;
        int size = tex.length;
        if (size > PACKET_MAX_SIZE) {
            LOGGER.error("[CustomBlocks] Rejected oversized payload for slot {} (action={}, face={}): {} bytes exceeds {} byte limit",
                    payload.slotIndex(), payload.action(), payload.face(), size, PACKET_MAX_SIZE);
            return false;
        }
        if (size > PACKET_WARN_SIZE) {
            LOGGER.warn("[CustomBlocks] Large texture payload for slot {} (action={}, face={}): {} bytes - consider smaller textures",
                    payload.slotIndex(), payload.action(), payload.face(), size);
        }
        return true;
    }

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
        int texCount = 0, faceCount = 0, nullCount = 0;
        for (SlotData data : SlotManager.allSlots()) {
            if (data.texture != null && data.texture.length > 0) {
                queue.enqueue(new SlotUpdatePayload("add", data.index, data.customId,
                        data.displayName, data.texture,
                        data.lightLevel, data.hardness, data.soundType, null, null, data.animMeta));
                texCount++;
            } else {
                nullCount++;
            }
            // Also send per-face texture overrides
            for (var face : data.faceTextures.entrySet()) {
                queue.enqueue(new SlotUpdatePayload("setface", data.index, data.customId,
                        null, face.getValue(),
                        data.lightLevel, data.hardness, data.soundType, face.getKey()));
                faceCount++;
            }
        }
        LOGGER.info("[CustomBlocks] Drip-feed queued for {}: {} textures, {} faces, {} null-texture slots",
                player.getName().getString(), texCount, faceCount, nullCount);

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

    /** Max bytes of texture data to send per player per tick. Prevents saturating
     *  the game socket on shared hosting with limited bandwidth. */
    private static final int BYTES_PER_TICK_BUDGET = 256 * 1024; // 256KB

    /**
     * Called every server tick. Drains pending payloads and sends them,
     * respecting both a packet-count cap AND a bytes-per-tick budget so
     * large textures don't flood the connection.
     */
    public static void onServerTick(MinecraftServer server) {
        int perTick = CustomBlocksConfig.texturePayloadsPerTick;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            TextureQueue queue = PLAYER_QUEUES.get(player.getUuid());
            if (queue == null || queue.isEmpty()) continue;

            SlotUpdatePayload[] batch = queue.drain(perTick);
            int bytesSent = 0;
            int i;
            for (i = 0; i < batch.length; i++) {
                SlotUpdatePayload payload = batch[i];
                try {
                    int payloadSize = payload.texture() != null ? payload.texture().length : 0;
                    if (bytesSent > 0 && bytesSent + payloadSize > BYTES_PER_TICK_BUDGET) {
                        // Re-queue this and all remaining payloads — reverse order to maintain FIFO
                        for (int j = batch.length - 1; j >= i; j--) {
                            queue.requeueFront(batch[j]);
                        }
                        break;
                    }
                    ServerPlayNetworking.send(player, payload);
                    bytesSent += payloadSize;
                } catch (Exception e) {
                    LOGGER.warn("[CustomBlocks] Failed to send payload to {}: {}",
                            player.getName().getString(), e.getMessage());
                }
            }

            // Log when drip-feed completes for a player
            if (queue.isEmpty()) {
                LOGGER.info("[CustomBlocks] Drip-feed complete for {}", player.getName().getString());
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
                        // Stable UUID from hash — Minecraft caches accepted packs by UUID,
                        // so using a hash-derived ID skips the prompt if the pack hasn't changed.
                        java.util.UUID packUuid = hash != null && !hash.isEmpty()
                                ? java.util.UUID.nameUUIDFromBytes(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                : java.util.UUID.randomUUID();
                        net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket packet =
                                new net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket(
                                        packUuid,
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
        if (!validatePayloadSize(payload)) return;
        getOrCreateQueue(player).enqueue(payload);
    }

    private static TextureQueue getOrCreateQueue(ServerPlayerEntity player) {
        return PLAYER_QUEUES.computeIfAbsent(player.getUuid(), k -> new TextureQueue());
    }
}
