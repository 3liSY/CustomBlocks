package com.customblocks.arabic;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent registry mapping (letterName, color) -> custom-block ID.
 * Backed by config/customblocks/arabic-registry.json.
 *
 * Call {@link #load()} once at server start. Every {@link #register} call
 * saves immediately.
 */
public final class ArabicBlockRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/Arabic");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Path REGISTRY_FILE = Path.of("config", "customblocks", "arabic-registry.json");

    /** Key: "letterName:color"  Value: customId */
    private static final Map<String, String> BY_KEY = new ConcurrentHashMap<>();

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public static void load() {
        if (!Files.exists(REGISTRY_FILE)) return;
        try {
            String json = Files.readString(REGISTRY_FILE);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            obj.entrySet().forEach(e -> BY_KEY.put(e.getKey(), e.getValue().getAsString()));
            LOGGER.info("[CB/Arabic] Loaded {} letter-block mappings", BY_KEY.size());
        } catch (Exception e) {
            LOGGER.error("[CB/Arabic] Failed to load arabic-registry.json: {}", e.getMessage());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(REGISTRY_FILE.getParent());
            JsonObject obj = new JsonObject();
            new TreeMap<>(BY_KEY).forEach(obj::addProperty);
            Files.writeString(REGISTRY_FILE, GSON.toJson(obj));
        } catch (Exception e) {
            LOGGER.error("[CB/Arabic] Failed to save arabic-registry.json: {}", e.getMessage());
        }
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    /** Returns the custom-block ID for this letter+color, or null if not imported. */
    public static String lookup(String letterName, String color) {
        return BY_KEY.get(key(letterName, color));
    }

    /** Looks up a block ID by Arabic Unicode character and color. Returns null if not mapped. */
    public static String lookupByChar(char ch, String color) {
        String name = ArabicLetterMap.letterNameFor(ch);
        if (name == null) return null;
        return lookup(name, color);
    }

    /** Registers a mapping and saves immediately. */
    public static void register(String letterName, String color, String customId) {
        BY_KEY.put(key(letterName, color), customId);
        save();
    }

    /** All registered entries (letter:color -> blockId), sorted alphabetically. */
    public static Map<String, String> all() {
        return Collections.unmodifiableMap(new TreeMap<>(BY_KEY));
    }

    /**
     * Returns all custom-block IDs registered for a given color,
     * in the same order as {@link ArabicLetterMap#LETTER_TO_CHAR}.
     */
    public static List<String> getAllForColor(String color) {
        String col = color.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        // Iterate in canonical letter order
        for (String letterName : ArabicLetterMap.LETTER_TO_CHAR.keySet()) {
            String id = lookup(letterName, col);
            if (id != null) result.add(id);
        }
        // Any extras not in the canonical map (a0-a9 style)
        String suffix = ":" + col;
        Set<String> canonical = new HashSet<>();
        ArabicLetterMap.LETTER_TO_CHAR.keySet().forEach(n -> canonical.add(key(n, col)));
        for (Map.Entry<String, String> e : new TreeMap<>(BY_KEY).entrySet()) {
            if (e.getKey().endsWith(suffix) && !canonical.contains(e.getKey())) {
                result.add(e.getValue());
            }
        }
        return result;
    }

    public static boolean hasAny()  { return !BY_KEY.isEmpty(); }
    public static int size()        { return BY_KEY.size(); }
    public static boolean isRegistered(String letterName, String color) {
        return BY_KEY.containsKey(key(letterName, color));
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static String key(String letter, String color) {
        return letter + ":" + color.toLowerCase(Locale.ROOT);
    }

    private ArabicBlockRegistry() {}
}
