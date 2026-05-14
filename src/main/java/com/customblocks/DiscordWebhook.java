package com.customblocks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M1 — Fire-and-forget Discord webhook poster.
 * <p>
 * Posts a plain JSON {@code {"content":"..."}} body to the configured endpoint.
 * Runs on a daemon thread; never blocks the server tick.
 */
public final class DiscordWebhook {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");

    private static final java.util.concurrent.ExecutorService POOL =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CustomBlocks-Webhook");
            t.setDaemon(true);
            return t;
        });

    private DiscordWebhook() {}

    /**
     * Post {@code message} to the Discord webhook configured in
     * {@link CustomBlocksConfig#discordWebhookUrl}.  No-op if the URL is blank.
     */
    public static void post(String message) {
        String url = CustomBlocksConfig.discordWebhookUrl;
        if (url == null || url.isBlank()) return;
        final String finalUrl = url.trim();
        final String body = "{\"content\":" + escapeJson(message) + "}";
        POOL.submit(() -> {
            try {
                java.net.HttpURLConnection con =
                    (java.net.HttpURLConnection) java.net.URI.create(finalUrl).toURL().openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.setRequestProperty("Content-Type", "application/json");
                con.setRequestProperty("User-Agent", "CustomBlocks/2.0.0");
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                con.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (java.io.OutputStream os = con.getOutputStream()) {
                    os.write(bytes);
                }
                int code = con.getResponseCode();
                if (code >= 400) {
                    LOGGER.warn("[CustomBlocks] Discord webhook returned HTTP {}", code);
                }
            } catch (Exception ex) {
                LOGGER.debug("[CustomBlocks] Discord webhook failed: {}", ex.getMessage());
            }
        });
    }

    /** Minimal JSON string escaper — handles the characters Discord messages can contain. */
    private static String escapeJson(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
