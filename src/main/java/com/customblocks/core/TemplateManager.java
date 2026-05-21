package com.customblocks.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 5.15 — Block templates / presets.
 * <p>
 * A template captures a block's properties (sound, glow, hardness, shape,
 * collision, import settings) and lets you apply them to any other block in
 * one command.  Stored in {@code config/customblocks/templates.json}.
 */
public final class TemplateManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/Templates");
    private static final String DATA_DIR  = "config/customblocks";
    private static final String DATA_FILE = "templates.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Template record ──────────────────────────────────────────────────────

    @SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public record Template(
        String name,
        String soundType,
        int    lightLevel,
        float  hardness,
        boolean noCollision,
        List<SlotData.ShapeBox> shapeBoxes,   // null = full cube
        String importBgMode,
        String importFringe,
        int    importSize,
        String createdBy,
        long   createdAt
    ) {}

    private static final ConcurrentHashMap<String, Template> TEMPLATES = new ConcurrentHashMap<>();

    private TemplateManager() {}

    // ── Query ────────────────────────────────────────────────────────────────

    public static Template get(String name) {
        return name == null ? null : TEMPLATES.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public static List<Template> getAll() {
        List<Template> list = new ArrayList<>(TEMPLATES.values());
        list.sort(Comparator.comparing(t -> t.name().toLowerCase(java.util.Locale.ROOT)));
        return list;
    }

    public static boolean exists(String name) {
        return name != null && TEMPLATES.containsKey(name.toLowerCase(java.util.Locale.ROOT));
    }

    // ── Mutation ─────────────────────────────────────────────────────────────

    /**
     * Save the properties of {@code source} as a new template called {@code name}.
     * @return true if saved, false if the template name is blank.
     */
    public static boolean save(String name, SlotData source, String createdBy) {
        if (name == null || name.isBlank()) return false;
        String key = name.toLowerCase(java.util.Locale.ROOT);
        Template t = new Template(
            name.trim(),
            source.soundType,
            source.lightLevel,
            source.hardness,
            source.noCollision,
            source.shapeBoxes == null ? null : new ArrayList<>(source.shapeBoxes),
            source.importBgMode,
            source.importFringe,
            source.importSize,
            createdBy,
            System.currentTimeMillis()
        );
        TEMPLATES.put(key, t);
        saveAsync();
        return true;
    }

    /**
     * Apply template {@code name} to the block with {@code targetId}.
     * @return true if applied, false if template or block not found.
     */
    public static boolean apply(String name, String targetId) {
        Template t = get(name);
        if (t == null) return false;
        if (SlotManager.getById(targetId) == null) return false;

        SlotManager.update(targetId, d -> d
            .withSoundType(t.soundType())
            .withLightLevel(t.lightLevel())
            .withHardness(t.hardness())
            .withNoCollision(t.noCollision())
            .withShapeBoxes(t.shapeBoxes())
            .withImportBgMode(t.importBgMode())
            .withImportFringe(t.importFringe())
            .withImportSize(t.importSize()));
        return true;
    }

    /**
     * Delete a template.
     * @return true if it existed and was removed.
     */
    public static boolean delete(String name) {
        if (name == null) return false;
        String key = name.toLowerCase(java.util.Locale.ROOT);
        boolean removed = TEMPLATES.remove(key) != null;
        if (removed) saveAsync();
        return removed;
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public static void load() {
        Path file = Path.of(DATA_DIR, DATA_FILE);
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            TEMPLATES.clear();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    Template t = fromJson(entry.getValue().getAsJsonObject());
                    if (t != null) TEMPLATES.put(t.name().toLowerCase(java.util.Locale.ROOT), t);
                } catch (Exception ex) {
                    LOGGER.warn("[CustomBlocks] Skipping malformed template entry '{}': {}", entry.getKey(), ex.getMessage());
                }
            }
            LOGGER.info("[CustomBlocks] Loaded {} block template(s).", TEMPLATES.size());
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load templates.json: {}", e.getMessage());
        }
    }

    private static void saveAsync() {
        Thread t = new Thread(TemplateManager::save, "CustomBlocks-TemplatesSave");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void save() {
        try {
            Path dir  = Path.of(DATA_DIR);
            Path tmp  = dir.resolve(DATA_FILE + ".tmp");
            Path dest = dir.resolve(DATA_FILE);
            Files.createDirectories(dir);
            JsonObject root = new JsonObject();
            for (Template t : TEMPLATES.values()) {
                root.add(t.name().toLowerCase(java.util.Locale.ROOT), toJson(t));
            }
            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8))) {
                GSON.toJson(root, w);
            }
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to save templates.json: {}", e.getMessage());
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private static JsonObject toJson(Template t) {
        JsonObject o = new JsonObject();
        o.addProperty("name",         t.name());
        o.addProperty("soundType",    t.soundType() != null    ? t.soundType()    : "stone");
        o.addProperty("lightLevel",   t.lightLevel());
        o.addProperty("hardness",     t.hardness());
        o.addProperty("noCollision",  t.noCollision());
        o.addProperty("importBgMode", t.importBgMode() != null ? t.importBgMode() : "keep_transparent");
        o.addProperty("importFringe", t.importFringe() != null ? t.importFringe() : "off");
        o.addProperty("importSize",   t.importSize());
        o.addProperty("createdBy",    t.createdBy() != null    ? t.createdBy()    : "");
        o.addProperty("createdAt",    t.createdAt());
        if (t.shapeBoxes() != null && !t.shapeBoxes().isEmpty()) {
            JsonArray arr = new JsonArray();
            for (SlotData.ShapeBox box : t.shapeBoxes()) {
                arr.add(box.x1() + "," + box.y1() + "," + box.z1() + ","
                      + box.x2() + "," + box.y2() + "," + box.z2());
            }
            o.add("shapeBoxes", arr);
        }
        return o;
    }

    private static Template fromJson(JsonObject o) {
        String name       = o.has("name")        ? o.get("name").getAsString()        : null;
        if (name == null || name.isBlank()) return null;
        String soundType  = o.has("soundType")   ? o.get("soundType").getAsString()   : "stone";
        int    lightLevel = o.has("lightLevel")   ? o.get("lightLevel").getAsInt()     : 0;
        float  hardness   = o.has("hardness")     ? o.get("hardness").getAsFloat()     : 2.0f;
        boolean noCol     = o.has("noCollision")  && o.get("noCollision").getAsBoolean();
        String bgMode     = o.has("importBgMode") ? o.get("importBgMode").getAsString(): "keep_transparent";
        String fringe     = o.has("importFringe") ? o.get("importFringe").getAsString(): "off";
        int    size       = o.has("importSize")   ? o.get("importSize").getAsInt()     : 0;
        String by         = o.has("createdBy")    ? o.get("createdBy").getAsString()   : "";
        long   at         = o.has("createdAt")    ? o.get("createdAt").getAsLong()     : 0L;

        List<SlotData.ShapeBox> boxes = null;
        if (o.has("shapeBoxes") && o.get("shapeBoxes").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("shapeBoxes");
            boxes = new ArrayList<>();
            for (JsonElement el : arr) {
                try { boxes.add(SlotData.ShapeBox.parse(el.getAsString())); }
                catch (IllegalArgumentException ignored) {}
            }
            if (boxes.isEmpty()) boxes = null;
        }

        return new Template(name, soundType, lightLevel, hardness, noCol, boxes, bgMode, fringe, size, by, at);
    }
}
