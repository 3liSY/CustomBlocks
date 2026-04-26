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
    /**
     * Hard limit per single (unchunked) payload. Minecraft's real limit is
     * 1,048,576 bytes (1 MB) — the previous 8 MB value was useless because
     * Minecraft killed the connection before our check even mattered.
     * 900 KB leaves room for packet framing overhead.
     */
    private static final int PACKET_MAX_SIZE = 900 * 1024; // 900KB (below MC's 1MB hard limit)
    /** Payloads with textures above this threshold are automatically chunked. */
    private static final int CHUNK_THRESHOLD = 500 * 1024; // 500KB
    /** Max bytes per chunk — well under Minecraft's 1 MB limit. */
    private static final int CHUNK_SIZE = 500 * 1024; // 500KB
    /** Max total reassembly size — prevents abuse. */
    private static final int MAX_CHUNKED_TOTAL = 10 * 1024 * 1024; // 10MB

    // ── Per-player pending texture queues ────────────────────────────────────
    private static final ConcurrentHashMap<UUID, TextureQueue> PLAYER_QUEUES = new ConcurrentHashMap<>();

    /** Tracks when each player last received a full sync to prevent redundant re-syncs. */
    private static final ConcurrentHashMap<UUID, Long> LAST_FULL_SYNC = new ConcurrentHashMap<>();
    /** Minimum interval between full syncs per player (ms). Prevents double-syncing
     *  when a broadcastFullSync fires shortly after a join sync. */
    private static final long FULL_SYNC_COOLDOWN_MS = 30_000;

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
        // Skip if this player was already synced recently (e.g. broadcastFullSync shortly after join)
        long now = System.currentTimeMillis();
        Long lastSync = LAST_FULL_SYNC.get(player.getUuid());
        if (lastSync != null && (now - lastSync) < FULL_SYNC_COOLDOWN_MS) {
            LOGGER.info("[CustomBlocks] Skipping redundant full sync for {} (last sync {}ms ago)",
                    player.getName().getString(), now - lastSync);
            return;
        }
        LAST_FULL_SYNC.put(player.getUuid(), now);

        List<FullSyncPayload.SlotEntry> entries = new ArrayList<>();
        for (SlotData data : SlotManager.allSlots()) {
            entries.add(new FullSyncPayload.SlotEntry(
                    data.index, data.customId, data.displayName,
                    null,  // no texture in full sync — sent via drip-feed
                    data.lightLevel, data.hardness, data.soundType, data.animMeta));
        }
        byte[] tabIcon = SlotManager.getTabIconTexture();
        FullSyncPayload syncPayload = new FullSyncPayload(entries, tabIcon,
                com.customblocks.CustomBlocksConfig.maxSlots);
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
        // The server hash is embedded in customId so the client can echo it back
        // on the next join — guaranteeing a server-vs-server hash comparison.
        String serverHash = SlotManager.computeTextureHash();
        queue.enqueue(new SlotUpdatePayload("sync_done", -1, serverHash, null,
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
    private static final int BYTES_PER_TICK_BUDGET = 512 * 1024; // 512KB (Phase 5)

    /**
     * Called every server tick. Drains pending payloads and sends them,
     * respecting both a packet-count cap AND a bytes-per-tick budget so
     * large textures don't flood the connection.
     */
    public static void onServerTick(MinecraftServer server) {
        // ── Drip-feed texture payloads ───────────────────────────────────
        int perTick = CustomBlocksConfig.texturePayloadsPerTick;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            java.util.UUID uid = player.getUuid();

            // Fix 12: Traffic shaping — if this player is in cooldown, decrement and skip
            Integer cooldown = COOLDOWN_TICKS.get(uid);
            if (cooldown != null && cooldown > 0) {
                COOLDOWN_TICKS.put(uid, cooldown - 1);
                continue;
            }

            TextureQueue queue = PLAYER_QUEUES.get(uid);
            if (queue == null || queue.isEmpty()) continue;

            net.minecraft.network.packet.CustomPayload[] batch = queue.drain(perTick);
            int bytesSent = 0;
            int i;
            for (i = 0; i < batch.length; i++) {
                net.minecraft.network.packet.CustomPayload payload = batch[i];
                try {
                    int payloadSize = TextureQueue.payloadByteSize(payload);
                    // Fix 12: Enforce budget even on the FIRST packet of the tick
                    if (bytesSent + payloadSize > BYTES_PER_TICK_BUDGET && payloadSize > 0) {
                        if (bytesSent == 0) {
                            // Single oversized texture: send it but apply cooldown
                            ServerPlayNetworking.send(player, payload);
                            bytesSent += payloadSize;
                            // Cooldown = how many ticks this payload "consumed"
                            int cooldownTicks = Math.max(1, payloadSize / BYTES_PER_TICK_BUDGET);
                            COOLDOWN_TICKS.put(uid, cooldownTicks);
                            i++; // advance past this payload
                        }
                        // Re-queue remaining payloads — reverse order to maintain FIFO
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
                COOLDOWN_TICKS.remove(uid);
                LOGGER.info("[CustomBlocks] Drip-feed complete for {}", player.getName().getString());
            }
        }
    }

    // ── Player lifecycle ─────────────────────────────────────────────────────

    /** Called when a player disconnects — cleans up their queue. */
    public static void onPlayerDisconnect(ServerPlayerEntity player) {
        TextureQueue queue = PLAYER_QUEUES.remove(player.getUuid());
        if (queue != null) queue.clear();
        LAST_FULL_SYNC.remove(player.getUuid());
        COOLDOWN_TICKS.remove(player.getUuid());
    }

    /**
     * Called when the client sends SyncRequestPayload (Fix 7: client-initiated sync).
     * At this point the Netty pipeline is guaranteed ready for S2C packets.
     *
     * Phase 3: If the client's cached texture hash matches the server's, skip the
     * entire drip-feed and send metadata + sync_done only. This makes rejoins instant.
     */
    public static void onSyncRequest(ServerPlayerEntity player, String clientHash) {
        if (clientHash != null && !clientHash.isEmpty()) {
            String serverHash = SlotManager.computeTextureHash();
            if (clientHash.equals(serverHash)) {
                LOGGER.info("[CustomBlocks] Hash match for {} (hash={}). Skipping drip-feed.",
                        player.getName().getString(), serverHash.substring(0, Math.min(12, serverHash.length())));
                // Send metadata-only FullSyncPayload so client has slot names/properties
                List<FullSyncPayload.SlotEntry> entries = new ArrayList<>();
                for (SlotData data : SlotManager.allSlots()) {
                    entries.add(new FullSyncPayload.SlotEntry(
                            data.index, data.customId, data.displayName,
                            null, data.lightLevel, data.hardness, data.soundType, data.animMeta));
                }
                byte[] tabIcon = SlotManager.getTabIconTexture();
                ServerPlayNetworking.send(player, new FullSyncPayload(entries, tabIcon,
                        com.customblocks.CustomBlocksConfig.maxSlots));
                // Send immediate sync_done — no drip-feed needed
                TextureQueue queue = getOrCreateQueue(player);
                queue.enqueue(new SlotUpdatePayload("sync_done", -1, serverHash, null,
                        new byte[0], 0, 0f, "stone"));
                return;
            }
            LOGGER.info("[CustomBlocks] Hash mismatch for {} (client={}, server={}). Full drip-feed.",
                    player.getName().getString(),
                    clientHash.substring(0, Math.min(12, clientHash.length())),
                    serverHash.substring(0, Math.min(12, serverHash.length())));
        }
        sendFullSync(player);
    }

    /** Called when a player joins — mandatory resource pack only (no sync here). */
    public static void onPlayerJoin(ServerPlayerEntity player) {
        // NOTE: sendFullSync is NO LONGER called here.
        // The client will send SyncRequestPayload when ready → onSyncRequest().

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

    // ── Network Traffic Shaping (Fix 12) ──────────────────────────────────────
    /**
     * Per-player cooldown ticks. If > 0, that player's queue is paused to let
     * their connection digest a large payload without choking Keep-Alive.
     */
    private static final ConcurrentHashMap<java.util.UUID, Integer> COOLDOWN_TICKS = new ConcurrentHashMap<>();

    // ── Internal ─────────────────────────────────────────────────────────────

    private static void enqueueForPlayer(ServerPlayerEntity player, SlotUpdatePayload payload) {
        if (payload == null) return;
        byte[] tex = payload.texture();
        if (tex != null && tex.length > CHUNK_THRESHOLD) {
            // Auto-chunk: texture exceeds threshold → split into ChunkedTexturePayload pieces
            sendChunked(player, payload);
            return;
        }
        if (!validatePayloadSize(payload)) return;
        getOrCreateQueue(player).enqueue(payload);
    }

    /**
     * Splits a large {@link SlotUpdatePayload} into multiple
     * {@link ChunkedTexturePayload} packets and enqueues each one into
     * the player's drip-feed queue. The client reassembles them by
     * {@code transferId}.
     */
    private static void sendChunked(ServerPlayerEntity player, SlotUpdatePayload payload) {
        byte[] tex = payload.texture();
        if (tex == null || tex.length == 0) return;

        if (tex.length > MAX_CHUNKED_TOTAL) {
            LOGGER.error("[CustomBlocks] Texture too large even for chunking: {} bytes (max {}). Slot={}, action={}",
                    tex.length, MAX_CHUNKED_TOTAL, payload.slotIndex(), payload.action());
            return;
        }

        String transferId = java.util.UUID.randomUUID().toString();
        int totalChunks = (tex.length + CHUNK_SIZE - 1) / CHUNK_SIZE;

        LOGGER.info("[CustomBlocks] Chunking texture for {} → {} chunks ({} bytes). transferId={}, slot={}, action={}, face={}",
                player.getName().getString(), totalChunks, tex.length,
                transferId.substring(0, 8), payload.slotIndex(), payload.action(), payload.face());

        TextureQueue queue = getOrCreateQueue(player);
        for (int i = 0; i < totalChunks; i++) {
            int offset = i * CHUNK_SIZE;
            int length = Math.min(CHUNK_SIZE, tex.length - offset);
            byte[] chunkData = new byte[length];
            System.arraycopy(tex, offset, chunkData, 0, length);

            ChunkedTexturePayload chunk;
            if (i == 0) {
                // Chunk 0 carries full metadata
                chunk = new ChunkedTexturePayload(
                        transferId, i, totalChunks, chunkData,
                        payload.action(), payload.slotIndex(), payload.customId(),
                        payload.displayName(), payload.lightLevel(), payload.hardness(),
                        payload.soundType(), payload.face(), payload.animMeta());
            } else {
                // Subsequent chunks: minimal metadata
                chunk = new ChunkedTexturePayload(
                        transferId, i, totalChunks, chunkData,
                        null, 0, null, null, 0, 0f, null, null, null);
            }
            queue.enqueueChunk(chunk);
        }
    }

    private static TextureQueue getOrCreateQueue(ServerPlayerEntity player) {
        return PLAYER_QUEUES.computeIfAbsent(player.getUuid(), k -> new TextureQueue());
    }

    /** Expose chunk threshold for diagnostic logging. */
    public static int getChunkThreshold() { return CHUNK_THRESHOLD; }
}
