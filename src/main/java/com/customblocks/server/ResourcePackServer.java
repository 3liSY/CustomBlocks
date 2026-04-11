package com.customblocks.server;

import com.customblocks.CustomBlocksMod;
import com.customblocks.client.ResourcePackGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Properties;

/**
 * §2: Internal embedded HTTP Server to host the resource pack.
 * Built into the mod so server owners don't need external web hosts.
 */
public class ResourcePackServer {

    private static HttpServer server;
    private static int port = 8080;
    private static final File CONFIG_FILE = new File("config/customblocks/rp_server.properties");
    private static final File ZIP_FILE = new File("config/customblocks/customblocks_pack.zip");
    
    private static byte[] currentHash = new byte[0];

    public static void loadConfig() {
        CONFIG_FILE.getParentFile().mkdirs();
        if (CONFIG_FILE.exists()) {
            try (FileInputStream in = new FileInputStream(CONFIG_FILE)) {
                Properties props = new Properties();
                props.load(in);
                port = Integer.parseInt(props.getProperty("port", "8080"));
            } catch (Exception e) {
                CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to load RP server config", e);
            }
        } else {
            saveConfig(port);
        }
    }

    public static void saveConfig(int newPort) {
        port = newPort;
        try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
            Properties props = new Properties();
            props.setProperty("port", String.valueOf(port));
            props.store(out, "CustomBlocks Resource Pack Server Config");
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to save RP server config", e);
        }
    }

    public static int getPort() {
        return port;
    }

    public static void start() {
        if (server != null) return;
        loadConfig();
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/customblocks.zip", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    if (!ZIP_FILE.exists()) {
                        String response = "Not Found";
                        exchange.sendResponseHeaders(404, response.length());
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response.getBytes());
                        }
                        return;
                    }
                    
                    exchange.getResponseHeaders().set("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, ZIP_FILE.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        Files.copy(ZIP_FILE.toPath(), os);
                    }
                }
            });
            server.setExecutor(null);
            server.start();
            CustomBlocksMod.LOGGER.info("[CustomBlocks] Started Embedded Resource Pack Server on port {}", port);
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to start RP server on port {}", port, e);
        }
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Re-generates the ZIP from SlotManager data and updates the hash payload. */
    public static void regenerateZip() {
        try {
            File runDir = FabricLoader.getInstance().getGameDir().toFile();
            ZIP_FILE.getParentFile().mkdirs();
            ResourcePackGenerator.generateAndZip(runDir, ZIP_FILE);
            
            // Calculate SHA-1 hash for the packet
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream is = new FileInputStream(ZIP_FILE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
                currentHash = digest.digest();
            }
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to regenerate server resource pack zip", e);
        }
    }

    public static byte[] getCurrentHash() {
        return currentHash;
    }
}
