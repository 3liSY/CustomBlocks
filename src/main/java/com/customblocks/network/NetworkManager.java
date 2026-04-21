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
    /** Hard limit — matches Minecraft's real 1 MB packet ceiling (with margin for overhead). */
    private static final int PACKET_MAX_SIZE = 900 * 1024; // 900KB

    // ── Packet chunking (Phase 10) ──────────────────────────────────────────
    /** Payloads with texture bytes above this threshold are auto-chunked. */
    private static final int CHUNK_THRESHOLD = 500 * 1024; // 500KB
    /** Max raw bytes per chunk — fits comfortably within the tick budget. */
    private static final int CHUNK_SIZE = 200 * 1024; // 200KB

    // ── Per-player pending texture queues ────────────────────────────────────
    private static final ConcurrentHashMap<UUID, TextureQueue> PLAYER_QUEUES = new ConcurrentHashMap<>();
    /** Per-player pending chunk queues (Phase 10). */
    private static final ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentLinkedDeque<ChunkedTexturePayload>> CHUNK_QUEUES = new ConcurrentHashMap<>();

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
            java.util.concurrent.ConcurrentLinkedDeque<ChunkedTexturePayload> chunkQueue = CHUNK_QUEUES.get(uid);
            boolean hasRegular = queue != null && !queue.isEmpty();
            boolean hasChunks  = chunkQueue != null && !chunkQueue.isEmpty();
            if (!hasRegular && !hasChunks) continue;

            int bytesSent = 0;

            // ── Regular payloads ────────────────────────────────────────────
            SlotUpdatePayload[] batch = hasRegular ? queue.drain(perTick) : new SlotUpdatePayload[0];
            int i;
            for (i = 0; i < batch.length; i++) {
                SlotUpdatePayload payload = batch[i];
                try {
                    int payloadSize = payload.texture() != null ? payload.texture().length : 0;
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

            // ── Chunked payloads (Phase 10) ─────────────────────────────────
            if (hasChunks) {
                while (!chunkQueue.isEmpty()) {
                    ChunkedTexturePayload chunk = chunkQueue.peekFirst();
                    if (chunk == null) break;
                    int chunkSize = chunk.chunkData() != null ? chunk.chunkData().length : 0;
                    if (bytesSent + chunkSize > BYTES_PER_TICK_BUDGET && bytesSent > 0) break;
                    chunkQueue.pollFirst();
                    try {
                        ServerPlayNetworking.send(player, chunk);
                        bytesSent += chunkSize;
                    } catch (Exception e) {
                        LOGGER.warn("[CustomBlocks] Failed to send chunk to {}: {}",
                                player.getName().getString(), e.getMessage());
                    }
                }
            }

            // Log when drip-feed completes for a player
            boolean regularDone = queue == null || queue.isEmpty();
            boolean chunksDone  = chunkQueue == null || chunkQueue.isEmpty();
            if (regularDone && chunksDone) {
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
        CHUNK_QUEUES.remove(player.getUuid());
        LAST_FULL_SYNC.remove(player.getUuid());
        COOLDOWN_TICKS.remove(player.getUuid());
    }

    /**
     * Called when the client sends SyncRequestPayload (Fix 7: client-initiated sync).
     * At this point the Netty pipeline is guaranteed ready for S2C packets.
     */
    public static void onSyncRequest(ServerPlayerEntity player) {
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
            sendChunked(player, payload);
            return;
        }
        if (!validatePayloadSize(payload)) return;
        getOrCreateQueue(player).enqueue(payload);
    }

    /**
     * Split a large texture payload into multiple ChunkedTexturePayload packets
     * (each under CHUNK_SIZE bytes) and enqueue them for drip-feed delivery.
     * Chunk 0 carries the original payload metadata; subsequent chunks carry only data.
     */
    private static void sendChunked(ServerPlayerEntity player, SlotUpdatePayload payload) {
        byte[] tex = payload.texture();
        String transferId = UUID.randomUUID().toString();
        int totalChunks = (tex.length + CHUNK_SIZE - 1) / CHUNK_SIZE;

        java.util.concurrent.ConcurrentLinkedDeque<ChunkedTexturePayload> chunkQueue =
                CHUNK_QUEUES.computeIfAbsent(player.getUuid(), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());

        for (int i = 0; i < totalChunks; i++) {
            int offset = i * CHUNK_SIZE;
            int length = Math.min(CHUNK_SIZE, tex.length - offset);
            byte[] chunkData = new byte[length];
            System.arraycopy(tex, offset, chunkData, 0, length);

            ChunkedTexturePayload chunk;
            if (i == 0) {
                chunk = new ChunkedTexturePayload(
                        transferId, i, totalChunks, chunkData,
                        payload.action(), payload.slotIndex(), payload.customId(),
                        payload.displayName(), payload.lightLevel(), payload.hardness(),
                        payload.soundType(), payload.face(), payload.animMeta());
            } else {
                chunk = new ChunkedTexturePayload(
                        transferId, i, totalChunks, chunkData,
                        null, 0, null, null, 0, 0f, null, null, null);
            }
            chunkQueue.addLast(chunk);
        }
        LOGGER.info("[CustomBlocks] Chunked texture for {} ({} bytes → {} chunks of ~{} KB, transferId={})",
                player.getName().getString(), tex.length, totalChunks, CHUNK_SIZE / 1024,
                transferId.substring(0, Math.min(8, transferId.length())));
    }

    private static TextureQueue getOrCreateQueue(ServerPlayerEntity player) {
        return PLAYER_QUEUES.computeIfAbsent(player.getUuid(), k -> new TextureQueue());
    }
}
