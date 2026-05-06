package com.customblocks.core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Auto-categorization rules engine.
 * <p>Each rule has a "Block ID contains" text match, a target category key,
 * a priority (higher wins on conflict), and an enabled flag.
 * Rules fire on block creation and import via {@link #applyRulesTo(String)}.
 *
 * <p>Storage: {@code config/customblocks/auto_rules.json}, atomic write pattern.
 */
public final class AutoCategorizeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DATA_DIR = "config/customblocks";
    private static final String DATA_FILE = "auto_rules.json";

    public record AutoRule(
            String id,
            String contains,
            String targetCategoryKey,
            int priority,
            boolean enabled
    ) {
        public AutoRule withEnabled(boolean v) { return new AutoRule(id, contains, targetCategoryKey, priority, v); }
        public AutoRule withPriority(int v) { return new AutoRule(id, contains, targetCategoryKey, priority, enabled); }
    }

    private static final CopyOnWriteArrayList<AutoRule> RULES = new CopyOnWriteArrayList<>();

    private AutoCategorizeManager() {}

    // ── Persistence ──────────────────────────────────────────────────────────

    public static synchronized void loadAll() {
        Path dir = Path.of(DATA_DIR);
        Path file = dir.resolve(DATA_FILE);
        Path bak1 = dir.resolve("auto_rules.bak1.json");
        try {
            Files.createDirectories(dir);
            if (!Files.exists(file)) {
                if (Files.exists(bak1)) {
                    LOGGER.warn("[CustomBlocks] auto_rules.json missing! Auto-restoring from backup.");
                    Files.copy(bak1, file, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    RULES.clear();
                    return;
                }
            }
            try { parseFile(file); }
            catch (Exception e) {
                LOGGER.error("[CustomBlocks] auto_rules.json corrupted! Restoring from .bak1...", e);
                if (Files.exists(bak1)) {
                    Files.copy(bak1, file, StandardCopyOption.REPLACE_EXISTING);
                    parseFile(file);
                }
            }
            SlotManager.rotateBackups(file);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load auto rules", e);
        }
    }

    private static void parseFile(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        if (json.isBlank()) { RULES.clear(); return; }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        RULES.clear();
        if (root.has("rules")) {
            for (JsonElement el : root.getAsJsonArray("rules")) {
                JsonObject o = el.getAsJsonObject();
                RULES.add(new AutoRule(
                        o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString().substring(0, 8),
                        o.has("contains") ? o.get("contains").getAsString() : "",
                        o.has("targetCategoryKey") ? o.get("targetCategoryKey").getAsString() : "",
                        o.has("priority") ? o.get("priority").getAsInt() : 0,
                        !o.has("enabled") || o.get("enabled").getAsBoolean()
                ));
            }
        }
    }

    public static synchronized void saveAll() {
        Path dir = Path.of(DATA_DIR);
        Path file = dir.resolve(DATA_FILE);
        Path tmp = dir.resolve("auto_rules.json.tmp");
        try {
            Files.createDirectories(dir);
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (AutoRule r : RULES) {
                JsonObject o = new JsonObject();
                o.addProperty("id", r.id());
                o.addProperty("contains", r.contains());
                o.addProperty("targetCategoryKey", r.targetCategoryKey());
                o.addProperty("priority", r.priority());
                o.addProperty("enabled", r.enabled());
                arr.add(o);
            }
            root.add("rules", arr);
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to save auto rules", e);
        }
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public static List<AutoRule> getAllRules() {
        return Collections.unmodifiableList(RULES);
    }

    public static List<AutoRule> getEnabledRulesByPriority() {
        List<AutoRule> sorted = new ArrayList<>(RULES);
        sorted.removeIf(r -> !r.enabled());
        sorted.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        return sorted;
    }

    public static synchronized AutoRule addRule(String contains, String targetCategoryKey, int priority) {
        AutoRule r = new AutoRule(
                UUID.randomUUID().toString().substring(0, 8),
                contains == null ? "" : contains,
                targetCategoryKey == null ? "" : targetCategoryKey.toLowerCase(),
                priority,
                true
        );
        RULES.add(r);
        saveAll();
        return r;
    }

    public static synchronized boolean removeRule(String ruleId) {
        boolean changed = RULES.removeIf(r -> r.id().equals(ruleId));
        if (changed) saveAll();
        return changed;
    }

    public static synchronized boolean toggleRule(String ruleId) {
        for (int i = 0; i < RULES.size(); i++) {
            AutoRule r = RULES.get(i);
            if (r.id().equals(ruleId)) {
                RULES.set(i, r.withEnabled(!r.enabled()));
                saveAll();
                return true;
            }
        }
        return false;
    }

    /**
     * Run all enabled rules against the given block ID. The highest-priority
     * matching rule (with a still-existing target category) gets the assignment.
     * Returns the category key applied, or null if none matched.
     */
    public static String applyRulesTo(String blockId) {
        if (blockId == null) return null;
        String lower = blockId.toLowerCase();
        for (AutoRule r : getEnabledRulesByPriority()) {
            if (r.contains() == null || r.contains().isEmpty()) continue;
            if (!lower.contains(r.contains().toLowerCase())) continue;
            if (CategoryManager.getCategory(r.targetCategoryKey()) == null) continue;
            CategoryManager.assignBlock(blockId, r.targetCategoryKey());
            LOGGER.info("[CustomBlocks] Auto-rule '{}' assigned '{}' → '{}'", r.id(), blockId, r.targetCategoryKey());
            return r.targetCategoryKey();
        }
        return null;
    }
}
