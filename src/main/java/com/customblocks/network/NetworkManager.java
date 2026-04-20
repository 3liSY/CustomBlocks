package com.customblocks.network;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
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
 * Drip-feed system has been completely removed to prevent massive lag
 * spikes during server operation.
 */
public final class NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");

    // ── Packet size limits ───────────────────────────────────────────────────
    /** Warning threshold - textures larger than this will log a warning. */
    private static final int PACKET_WARN_SIZE = 500 * 1024; // 500KB
    /** Hard limit - textures larger than this are rejected to prevent client crashes. */
    private static final int PACKET_MAX_SIZE = 8 * 1024 * 1024; // 8MB (below 10MB codec limit)

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
     */
    public static void broadcastUpdate(MinecraftServer server, SlotUpdatePayload payload) {
        if (!validatePayloadSize(payload)) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * Send a slot update to a SINGLE player.
     */
    public static void sendToPlayer(ServerPlayerEntity player, SlotUpdatePayload payload) {
        if (!validatePayloadSize(payload)) return;
        ServerPlayNetworking.send(player, payload);
    }

    /**
     * Send a full metadata sync to a player (on join).
     * Only sends metadata — textures are loaded natively via HTTP resource packs.
     * <p>
     * A {@code sync_done} sentinel is sent immediately so the client knows exactly
     * when all join data has been received, preventing it from freezing or waiting
     * for a drip-feed that no longer exists.
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
                    null,  // no texture in full sync -> sent via HTTP resource pack instead!
                    data.lightLevel, data.hardness, data.soundType, data.animMeta));
        }
        byte[] tabIcon = SlotManager.getTabIconTexture();
        FullSyncPayload syncPayload = new FullSyncPayload(entries, tabIcon);
        ServerPlayNetworking.send(player, syncPayload);

        // Sentinel: tells the client that the sync is done.
        // Triggers the client's localized resource pack regeneration and native reloading cleanly.
        ServerPlayNetworking.send(player, new SlotUpdatePayload("sync_done", -1, "", null,
                new byte[0], 0, 0f, "stone"));
        
        LOGGER.info("[CustomBlocks] Full sync complete for {}", player.getName().getString());
    }

    /**
     * Broadcast full sync to ALL online players (used for /cb reload).
     */
    public static void broadcastFullSync(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            sendFullSync(player);
        }
    }

    /**
     * Drip-feed system completely removed.
     * We retain this empty method hook in case other systems expected it.
     */
    public static void onServerTick(MinecraftServer server) {
        // No-op
    }

    // ── Player lifecycle ─────────────────────────────────────────────────────

    /** Called when a player disconnects — cleans up their state. */
    public static void onPlayerDisconnect(ServerPlayerEntity player) {
        LAST_FULL_SYNC.remove(player.getUuid());
    }

    /** Called when a player joins — sends full sync + mandatory resource pack via Local HTTP Server. */
    public static void onPlayerJoin(ServerPlayerEntity player) {
        sendFullSync(player);

        // ── Mandatory Resource Pack Enforcement ──────────────────────────────
        // Delayed by 40 ticks (2 seconds) to ensure the HTTP server has the pack
        // ZIP ready. Uses Minecraft's native sendResourcePackUrl API safely.
        if (com.customblocks.CustomBlocksConfig.rpEnforceOnJoin
                && ResourcePackServer.isRunning()
                && ResourcePackServer.activePort() > 0) {
            player.getServer().execute(() -> {
                final java.util.UUID playerId = player.getUuid();
                new Thread(() -> {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    player.getServer().execute(() -> {
                        // Secure validation against YoCube1 Netty crash BUG-02
                        ServerPlayerEntity p = player.getServer().getPlayerManager().getPlayer(playerId);
                        if (p == null || p.isDisconnected() || p.networkHandler == null) return;
                        try {
                             if (!p.networkHandler.isConnectionOpen()) return;
                        } catch (Exception e) {} // Fallback for various mappings

                        String url = ResourcePackServer.getPackUrl(p.getServer());
                        String hash = ResourcePackServer.getHash();
                        if (hash == null) hash = "";

                        // Stable UUID from hash — Minecraft caches accepted packs by UUID,
                        // so using a hash-derived ID skips the prompt if the pack hasn't changed.
                        java.util.UUID packUuid = hash != null && !hash.isEmpty()
                                ? java.util.UUID.nameUUIDFromBytes(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                : java.util.UUID.randomUUID();
                                
                        try {
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
                        } catch (Exception e) {
                            LOGGER.warn("[CustomBlocks] Failed to send resource pack to {}: {}", p.getName().getString(), e.getMessage());
                        }
                    });
                }, "CustomBlocks-RPSend").start();
            });
        }
    }
}
