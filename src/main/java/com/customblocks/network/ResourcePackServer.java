package com.customblocks.network;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.customblocks.CustomBlocksConfig;
import com.customblocks.CustomBlocksMod;
import com.customblocks.gui.ChatHelper;
import com.customblocks.gui.FeedbackHelper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;

public class ResourcePackServer {

    private static HttpServer server;
    // 7.29 — volatile ensures HTTP handler thread sees latest values written by PackBuilder thread
    private static volatile java.io.File currentPackFile;
    private static volatile String currentHash;
    private static volatile int activePort = -1;
    // Single-thread executor: only ONE ZIP build runs at a time.
    // AtomicInteger tracks pending builds — if > 1, we skip because
    // the already-queued build will pick up the latest snapshot.
    private static final ExecutorService PACK_BUILDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CustomBlocks-PackBuilder");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger pendingBuilds = new AtomicInteger(0);

    // 1.13+1.14 — Cloud Vault pack upload
    /** Hardcoded Cloud Vault URL — cannot be changed by config (prevents OP redirecting uploads). */
    static final String CLOUD_VAULT_URL = "https://cb-cloud-vault.cbbblocksvault.workers.dev";
    /** Set after a successful upload; used as the primary pack URL for all players. */
    private static volatile String cloudPackUrl = null;
    /** Single-thread executor for background pack uploads (never blocks pack rebuild). */
    private static final ExecutorService PACK_UPLOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CustomBlocks-PackUploader");
        t.setDaemon(true);
        return t;
    });
    private static final java.net.http.HttpClient HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build();

    public static int activePort() { return activePort; }

    /**
     * 1.32 — Players who need a pack update but had a GUI open when the pack built.
     * The update is sent to them the moment they close all CB GUI screens.
     */
    private static final java.util.Set<java.util.UUID> PENDING_PACK_PUSH =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 1.32 — Send the pack update packet to one player immediately.
     * Returns false if the pack hash isn't ready yet.
     */
    private static boolean sendPackToPlayer(net.minecraft.server.network.ServerPlayerEntity player) {
        String hash = currentHash;
        if (hash == null || hash.isEmpty()) return false;
        String url = getPackUrl(serverInstance);
        java.util.UUID packUuid = java.util.UUID.nameUUIDFromBytes(
            hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket packet =
                new net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket(
                    packUuid, url, hash, false,
                    java.util.Optional.of(net.minecraft.text.Text.literal(
                        ChatHelper.formattedKey("cmd.rp_notify_accept_prompt"))));
            player.networkHandler.sendPacket(packet);
            return true;
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] 1.32 Failed to send deferred RP push to {}", player.getName().getString());
            return false;
        }
    }

    /**
     * 1.32 — Called by GuiManager when a player fully closes all GUI screens.
     * If a pack update was deferred for them, send it now.
     */
    public static void flushPendingPackPush(net.minecraft.server.network.ServerPlayerEntity player) {
        if (!PENDING_PACK_PUSH.remove(player.getUuid())) return;
        if (serverInstance == null) return;
        serverInstance.execute(() -> sendPackToPlayer(player));
    }

    /**
     * 1.32 — Called on the server tick to flush deferred pack pushes for players
     * who closed their GUI via disconnect or other non-ESC paths.
     */
    public static void tickPendingPackPushes(MinecraftServer server) {
        if (PENDING_PACK_PUSH.isEmpty()) return;
        for (java.util.UUID uuid : java.util.List.copyOf(PENDING_PACK_PUSH)) {
            net.minecraft.server.network.ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) {
                // Player left — drop the pending push
                PENDING_PACK_PUSH.remove(uuid);
            } else if (!com.customblocks.gui.GuiManager.hasOpenGui(uuid)) {
                PENDING_PACK_PUSH.remove(uuid);
                sendPackToPlayer(player);
            }
        }
    }

    @SuppressFBWarnings("LI_LAZY_INIT_STATIC")
    public static void start() {
        if (server != null) {
            server.stop(0);
        }
        
        int basePort = CustomBlocksConfig.resourcePackPort;
        if (basePort <= 0) {
            CustomBlocksMod.LOGGER.info("[CustomBlocks] Internal HTTP server is disabled (port <= 0).");
            return;
        }

        // Try base port, then fallbacks
        int[] portsToTry = {basePort, 8081, 24454, 8082, 3000};
        boolean started = false;
        
        for (int p : portsToTry) {
            try {
                server = HttpServer.create(new InetSocketAddress(p), 0);
                activePort = p;
                started = true;
                break;
            } catch (IOException e) {
                CustomBlocksMod.LOGGER.warn("[CustomBlocks] Port {} is blocked, trying next...", p);
            }
        }

        if (!started) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] CRITICAL: Could not find any open Communication Door. Texture pipeline is OFFLINE.");
            return;
        }

        try {
            server.createContext("/pack.zip", exchange -> {
                // 7.29 — capture once atomically; avoids TOCTOU where PackBuilder swaps the
                // reference between the exists() check and the length()/copy() calls
                java.io.File file = currentPackFile;
                if (file == null || !file.exists()) {
                    byte[] msg = "Pipeline warming up...".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, msg.length);
                    exchange.getResponseBody().write(msg);
                    exchange.getResponseBody().close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, file.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    java.nio.file.Files.copy(file.toPath(), os);
                }
            });
            // Phase 10.5 — serve exported PNG screenshots from config/customblocks/exports/
            server.createContext("/exports/", exchange -> {
                String reqPath = exchange.getRequestURI().getPath(); // e.g. /exports/stone_tile_20260519_143022.png
                String fileName = reqPath.substring("/exports/".length());
                // Security: only serve .png files, no directory traversal
                if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..") || !fileName.endsWith(".png")) {
                    byte[] msg = "Forbidden".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(403, msg.length);
                    exchange.getResponseBody().write(msg);
                    exchange.getResponseBody().close();
                    return;
                }
                java.nio.file.Path file = java.nio.file.Path.of("config/customblocks/exports", fileName);
                if (!java.nio.file.Files.exists(file)) {
                    byte[] msg = "Not found".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, msg.length);
                    exchange.getResponseBody().write(msg);
                    exchange.getResponseBody().close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, java.nio.file.Files.size(file));
                try (OutputStream os = exchange.getResponseBody()) {
                    java.nio.file.Files.copy(file, os);
                }
            });
            server.setExecutor(null);
            server.start();
            CustomBlocksMod.LOGGER.info("[CustomBlocks] Texture Sanctuary is LIVE on port {}", activePort);
            updatePack();
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Unexpected error starting pipeline", e);
        }
    }

    @SuppressFBWarnings("LI_LAZY_INIT_STATIC")
    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static MinecraftServer serverInstance;

    @SuppressFBWarnings("EI_EXPOSE_STATIC_REP2")
    public static void setServer(MinecraftServer server) {
        serverInstance = server;
    }

    /** Returns the tracked server instance (may be null before server start). */
    @SuppressFBWarnings("MS_EXPOSE_REP")
    public static MinecraftServer getServer() { return serverInstance; }

    /** Rebuilds the ZIP to disk using a consistent state snapshot. */
    public static void updatePackWithSnapshot(com.customblocks.core.SlotManager.Snapshot snapshot) {
        // If there's already a build queued, skip — the queued build will use the latest data.
        if (pendingBuilds.incrementAndGet() > 1) {
            pendingBuilds.decrementAndGet();
            CustomBlocksMod.LOGGER.info("[CustomBlocks] Pack build already queued — skipping redundant request.");
            return;
        }
        PACK_BUILDER.submit(() -> {
            try {
                java.io.File packFile = new java.io.File("customblocks_data", "customblocks_pack.zip");
                long zipStart = System.currentTimeMillis();
                ServerPackGenerator.generateZipWithSnapshot(snapshot, packFile);
                long zipElapsed = System.currentTimeMillis() - zipStart;
                if (zipElapsed > 5000) {
                    CustomBlocksMod.LOGGER.warn("[CustomBlocks] 1.11 ZIP generation took {}ms — consider reducing texture count or sizes.", zipElapsed);
                }
                if (packFile.exists()) {
                    currentPackFile = packFile;
                    
                    MessageDigest digest = MessageDigest.getInstance("SHA-1");
                    try (java.io.InputStream is = new java.io.FileInputStream(packFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = is.read(buffer)) > 0) {
                            digest.update(buffer, 0, read);
                        }
                    }
                    byte[] hashBytes = digest.digest();
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hashBytes) {
                        sb.append(String.format("%02x", b));
                    }
                    currentHash = sb.toString();
                    CustomBlocksMod.LOGGER.info("[CustomBlocks] Cached internal resource pack ZIP (Atomic Update).");
                    FeedbackHelper.broadcastPackRegeneratedIfDue(serverInstance, 4000L);
                    // 1.13+1.14 — upload to Cloud Vault so external players can download via HTTPS
                    if (CustomBlocksConfig.cloudShareEnabled) {
                        String secret = CustomBlocksConfig.cloudPackSecret;
                        if (secret != null && !secret.isBlank()) {
                            final java.io.File uploadTarget = packFile;
                            final String uploadHash = currentHash;
                            PACK_UPLOADER.submit(() -> uploadPackToCloudVault(uploadTarget, uploadHash, secret));
                        }
                    }
                }
            } catch (Exception e) {
                CustomBlocksMod.LOGGER.error("[CustomBlocks] Error updating internal pack.", e);
            } finally {
                pendingBuilds.decrementAndGet();
            }
        });
    }

    /** Rebuilds the ZIP silently without prompting users. */
    public static void updatePack() {
        updatePackWithSnapshot(com.customblocks.core.SlotManager.getSnapshot());
    }

    public static String getHash() {
        return currentHash;
    }

    public static boolean isRunning() {
        return server != null;
    }

    public static int getPort() {
        return CustomBlocksConfig.resourcePackPort;
    }

    /**
     * Fetches the server's external IP address from a public service.
     * Returns "127.0.0.1" if offline or if the lookup fails.
     * 7.30 — result is cached for 5 minutes; fetch is done on background thread so the
     * caller never blocks the server tick loop.
     */
    private static volatile String cachedExternalIp = null;
    private static volatile long   ipCacheTime      = 0L;
    private static final long      IP_CACHE_TTL_MS  = 300_000L; // 5 minutes

    public static String getExternalIp() {
        long now = System.currentTimeMillis();
        if (cachedExternalIp != null && (now - ipCacheTime) < IP_CACHE_TTL_MS) {
            return cachedExternalIp; // instant — no network call
        }
        // Refresh on a background thread; return last known value immediately
        Thread bg = new Thread(() -> {
            try {
                java.net.URL url = java.net.URI.create("https://checkip.amazonaws.com").toURL();
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String ip = reader.readLine();
                    if (ip != null && !ip.isBlank()) {
                        cachedExternalIp = ip.trim();
                        ipCacheTime = System.currentTimeMillis();
                    }
                }
            } catch (IOException | RuntimeException ignored) {}
        }, "CustomBlocks-IpLookup");
        bg.setDaemon(true);
        bg.start();
        // Return last known value while the background fetch is in progress
        return cachedExternalIp != null ? cachedExternalIp : "127.0.0.1";
    }

    /** 1.13+1.14 — Upload the pack ZIP to Cloud Vault over HTTPS. Sets cloudPackUrl on success. */
    private static void uploadPackToCloudVault(java.io.File packFile, String hash, String secret) {
        try {
            byte[] packBytes = java.nio.file.Files.readAllBytes(packFile.toPath());
            String uploadUrl = CLOUD_VAULT_URL + "/pack";
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(uploadUrl))
                .header("Content-Type", "application/zip")
                .header("x-pack-secret", secret)
                .header("x-pack-hash", hash)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(packBytes))
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
            var response = HTTP_CLIENT.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 201) {
                cloudPackUrl = CLOUD_VAULT_URL + "/pack.zip";
                CustomBlocksMod.LOGGER.info("[CustomBlocks] 1.13 Resource pack uploaded to Cloud Vault ({} KB)", packBytes.length / 1024);
            } else {
                CustomBlocksMod.LOGGER.warn("[CustomBlocks] 1.13 Pack upload failed: HTTP {}", response.statusCode());
            }
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] 1.13 Pack upload failed: {}", e.getMessage());
        }
    }

    public static String getPackUrl(net.minecraft.server.MinecraftServer server) {
        // Priority 1: Cloud Vault — works on all hosting including MCServerHost (no port blocks)
        // Capture both volatiles to locals before use — prevents null race between the check and the read.
        String url = cloudPackUrl;
        String hash = currentHash;
        if (url != null && CustomBlocksConfig.cloudShareEnabled) {
            String bust = (hash != null && hash.length() >= 8) ? "?v=" + hash.substring(0, 8) : "";
            return url + bust;
        }
        // Priority 2: Local HTTP server — LAN/home servers with open ports
        String ip = getExternalIp();
        int port = activePort() > 0 ? activePort() : getPort();
        return "http://" + ip + ":" + port + "/pack.zip";
    }

    /**
     * Safely prompts ALL online players to re-download the resource pack.
     * Uses required=false so players get a prompt instead of being force-kicked.
     * Delayed slightly to let the pack ZIP finish building.
     *
     * 1.32 — Players who currently have a CB GUI open receive a deferred push:
     * the update packet is held in {@link #PENDING_PACK_PUSH} and sent the instant
     * they close all CB screens, preventing the red reload overlay from interrupting
     * active editing sessions.
     */
    public static void sendUpdateToAllPlayers() {
        if (serverInstance == null || !isRunning() || activePort() <= 0) return;

        new Thread(() -> {
            try { Thread.sleep(1000); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            serverInstance.execute(() -> {
                for (net.minecraft.server.network.ServerPlayerEntity player : serverInstance.getPlayerManager().getPlayerList()) {
                    java.util.UUID uuid = player.getUuid();
                    if (com.customblocks.gui.GuiManager.hasOpenGui(uuid)) {
                        // 1.32 — player is editing; defer the push until they close the GUI
                        PENDING_PACK_PUSH.add(uuid);
                    } else {
                        sendPackToPlayer(player);
                    }
                }
            });
        }, "CustomBlocks-RPNotify").start();
    }
}
