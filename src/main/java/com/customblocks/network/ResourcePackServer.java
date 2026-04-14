package com.customblocks.network;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.CustomBlocksMod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;

public class ResourcePackServer {

    private static HttpServer server;
    private static byte[] currentPackZip;
    private static String currentHash;
    private static int activePort = -1;
    private static String lastError = null;

    public static int activePort() { return activePort; }

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
                lastError = e.getMessage();
            }
        }

        if (!started) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] CRITICAL: Could not find any open Communication Door. Texture pipeline is OFFLINE.");
            return;
        }

        try {
            server.createContext("/pack.zip", exchange -> {
                if (currentPackZip == null) {
                    byte[] msg = "Pipeline warming up...".getBytes();
                    exchange.sendResponseHeaders(404, msg.length);
                    exchange.getResponseBody().write(msg);
                    exchange.getResponseBody().close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, currentPackZip.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(currentPackZip);
                }
            });
            server.setExecutor(null);
            server.start();
            lastError = null;
            CustomBlocksMod.LOGGER.info("[CustomBlocks] Texture Sanctuary is LIVE on port {}", activePort);
            updatePack();
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Unexpected error starting pipeline", e);
            lastError = e.getMessage();
        }
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Rebuilds the in-memory ZIP using a consistent state snapshot. */
    public static void updatePackWithSnapshot(com.customblocks.core.SlotManager.Snapshot snapshot) {
        new Thread(() -> {
            try {
                byte[] zip = ServerPackGenerator.generateZipWithSnapshot(snapshot);
                if (zip != null) {
                    currentPackZip = zip;
                    MessageDigest digest = MessageDigest.getInstance("SHA-1");
                    byte[] hashBytes = digest.digest(zip);
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hashBytes) {
                        sb.append(String.format("%02x", b));
                    }
                    currentHash = sb.toString();
                    CustomBlocksMod.LOGGER.info("[CustomBlocks] Cached internal resource pack ZIP (Atomic Update).");
                }
            } catch (Exception e) {
                CustomBlocksMod.LOGGER.error("[CustomBlocks] Error updating internal pack.", e);
            }
        }, "CustomBlocks-PackBuilder").start();
    }

    /** Rebuilds the in-memory ZIP silently without prompting users. */
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
     */
    public static String getExternalIp() {
        try {
            java.net.URL url = new java.net.URL("https://checkip.amazonaws.com");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()))) {
                String ip = reader.readLine();
                return (ip != null && !ip.isBlank()) ? ip.trim() : "127.0.0.1";
            }
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public static String getPackUrl(net.minecraft.server.MinecraftServer server) {
        String ip = getExternalIp();
        int port = activePort() > 0 ? activePort() : getPort();
        return "http://" + ip + ":" + port + "/pack.zip";
    }
}
