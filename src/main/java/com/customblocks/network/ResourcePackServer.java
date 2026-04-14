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

    public static void start() {
        if (server != null) {
            server.stop(0);
        }
        
        int port = CustomBlocksConfig.resourcePackPort;
        if (port <= 0) {
            CustomBlocksMod.LOGGER.info("[CustomBlocks] Internal HTTP server is disabled (port <= 0).");
            return;
        }

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/pack.zip", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    if (currentPackZip == null) {
                        String response = "Pack not ready yet.";
                        exchange.sendResponseHeaders(404, response.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(response.getBytes());
                        os.close();
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, currentPackZip.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(currentPackZip);
                    os.close();
                }
            });
            server.setExecutor(null); // creates a default executor
            server.start();
            CustomBlocksMod.LOGGER.info("[CustomBlocks] Resource pack server listening on port {}", port);
            
            // Build the initial pack
            updatePack();
        } catch (IOException e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to start HTTP server on port " + port, e);
        }
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Rebuilds the in-memory ZIP silently without prompting users. */
    public static void updatePack() {
        new Thread(() -> {
            try {
                byte[] zip = ServerPackGenerator.generateZipInMemory();
                if (zip != null) {
                    currentPackZip = zip;
                    MessageDigest digest = MessageDigest.getInstance("SHA-1");
                    byte[] hashBytes = digest.digest(zip);
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hashBytes) {
                        sb.append(String.format("%02x", b));
                    }
                    currentHash = sb.toString();
                    CustomBlocksMod.LOGGER.info("[CustomBlocks] Cached internal resource pack ZIP.");
                }
            } catch (Exception e) {
                CustomBlocksMod.LOGGER.error("[CustomBlocks] Error updating internal pack.", e);
            }
        }, "CustomBlocks-PackBuilder").start();
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
}
