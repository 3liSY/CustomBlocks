package com.customblocks.core;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.CustomBlocksMod;
import com.customblocks.TextSanitizer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase B2 — localized strings per {@link CustomBlocksConfig#voiceMode}, backed by
 * {@code assets/customblocks/lang/voice_*.json}.
 */
public final class VoiceCatalog {

    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private VoiceCatalog() {}

    public static void invalidate() {
        CACHE.clear();
    }

    /** Resource filename suffix (professional → {@code pro}). */
    static String resourceSuffix(String normalizedMode) {
        return switch (normalizedMode) {
            case "professional" -> "pro";
            case "friendly", "royal", "minimal", "arabic", "silly" -> normalizedMode;
            default -> "royal";
        };
    }

    /**
     * Format a catalog line for the current voice mode. Placeholders: {@code {0}}, {@code {1}}, …
     */
    public static String format(String key, Object... args) {
        String mode = CustomBlocksConfig.normalizeVoiceMode(CustomBlocksConfig.voiceMode);
        return formatForMode(mode, key, args);
    }

    /** Format a catalog line for a specific voice mode (used by previews/pickers). */
    public static String formatForMode(String modeRaw, String key, Object... args) {
        String mode = CustomBlocksConfig.normalizeVoiceMode(modeRaw);
        Map<String, String> table = CACHE.computeIfAbsent(mode, VoiceCatalog::loadTableForMode);
        String template = table.get(key);
        if (template == null) {
            CustomBlocksMod.LOGGER.warn("[VoiceCatalog] Missing key '{}' for mode '{}'", key, mode);
            template = key;
        }
        return TextSanitizer.fix(substitute(template, args));
    }

    private static Map<String, String> loadTableForMode(String normalizedMode) {
        String suffix = resourceSuffix(normalizedMode);
        String path = "assets/customblocks/lang/voice_" + suffix + ".json";
        ClassLoader cl = VoiceCatalog.class.getClassLoader();
        InputStream in = cl != null ? cl.getResourceAsStream(path) : null;
        if (in == null) {
            CustomBlocksMod.LOGGER.error("[VoiceCatalog] Missing classpath resource: {}", path);
            return Collections.emptyMap();
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(br).getAsJsonObject();
            Map<String, String> m = new HashMap<>(Math.max(16, root.size()));
            for (var e : root.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    m.put(e.getKey(), TextSanitizer.fix(e.getValue().getAsString()));
                }
            }
            return Collections.unmodifiableMap(m);
        } catch (Exception ex) {
            CustomBlocksMod.LOGGER.error("[VoiceCatalog] Failed to parse {}", path, ex);
            return Collections.emptyMap();
        }
    }

    private static String substitute(String template, Object... args) {
        if (args == null || args.length == 0) return template;
        String out = template;
        for (int i = 0; i < args.length; i++) {
            String rep = args[i] == null ? "" : String.valueOf(args[i]);
            out = out.replace("{" + i + "}", rep);
        }
        return out;
    }
}
