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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe Category Manager using TRD Layered Defense.
 */
public final class CategoryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DATA_DIR = "config/customblocks";
    private static final String DATA_FILE = "categories.json";

    // ── In-Memory State ──────────────────────────────────────────────────────
    private static final ConcurrentHashMap<String, Category> categories = new ConcurrentHashMap<>();
    // blockId -> set of category keys
    private static final ConcurrentHashMap<String, Set<String>> assignments = new ConcurrentHashMap<>();

    // ── Load & Save ──────────────────────────────────────────────────────────

    public static synchronized void loadAll() {
        Path dir = Path.of(DATA_DIR);
        Path file = dir.resolve(DATA_FILE);
        Path bak1 = dir.resolve("categories.bak1.json");

        try {
            Files.createDirectories(dir);
            if (!Files.exists(file)) {
                if (Files.exists(bak1)) {
                    LOGGER.warn("[CustomBlocks] categories.json missing! Auto-restoring from backup.");
                    Files.copy(bak1, file, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    categories.clear();
                    assignments.clear();
                    return;
                }
            }

            try {
                parseFile(file);
            } catch (Exception parseEx) {
                LOGGER.error("[CustomBlocks] categories.json corrupted! Restoring from .bak1...", parseEx);
                if (Files.exists(bak1)) {
                    Files.copy(bak1, file, StandardCopyOption.REPLACE_EXISTING);
                    parseFile(file);
                    LOGGER.info("[CustomBlocks] Successfully restored categories from .bak1!");
                } else {
                    throw new RuntimeException("Corrupted categories.json and no backup available!");
                }
            }

            SlotManager.rotateBackups(file);

        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load categories", e);
        }
    }

    private static void parseFile(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        
        categories.clear();
        assignments.clear();

        if (root.has("categories")) {
            for (JsonElement el : root.getAsJsonArray("categories")) {
                Category cat = GSON.fromJson(el, Category.class);
                categories.put(cat.key(), cat);
            }
        }

        if (root.has("assignments")) {
            JsonObject assignsObj = root.getAsJsonObject("assignments");
            for (Map.Entry<String, JsonElement> entry : assignsObj.entrySet()) {
                String blockId = entry.getKey();
                Set<String> cats = new HashSet<>();
                for (JsonElement catEl : entry.getValue().getAsJsonArray()) {
                    cats.add(catEl.getAsString().toLowerCase());
                }
                if (!cats.isEmpty()) assignments.put(blockId, cats);
            }
        }
    }

    public static synchronized void saveAll() {
        Path dir = Path.of(DATA_DIR);
        Path file = dir.resolve(DATA_FILE);
        Path tmpFile = dir.resolve("categories.json.tmp");

        try {
            Files.createDirectories(dir);
            
            JsonObject root = new JsonObject();
            JsonArray catsArr = new JsonArray();
            for (Category cat : categories.values()) {
                catsArr.add(GSON.toJsonTree(cat));
            }
            root.add("categories", catsArr);

            JsonObject assignsObj = new JsonObject();
            for (Map.Entry<String, Set<String>> entry : assignments.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                JsonArray arr = new JsonArray();
                for (String cat : entry.getValue()) arr.add(cat);
                assignsObj.add(entry.getKey(), arr);
            }
            root.add("assignments", assignsObj);

            String json = GSON.toJson(root);
            Files.writeString(tmpFile, json, StandardCharsets.UTF_8);
            Files.move(tmpFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to save categories", e);
        }
    }

    // ── Category CRUD ────────────────────────────────────────────────────────

    public static synchronized boolean addCategory(Category category) {
        if (categories.containsKey(category.key())) return false;
        categories.put(category.key(), category);
        saveAll();
        return true;
    }

    public static synchronized void updateCategory(String key, java.util.function.UnaryOperator<Category> mutator) {
        Category old = categories.get(key.toLowerCase());
        if (old == null) return;
        Category updated = mutator.apply(old);
        // TRD validation: prevent circular chains
        if (updated.parentKey() != null && createsCircularChain(updated.key(), updated.parentKey())) {
            LOGGER.warn("[CustomBlocks] Circular subcategory chain detected. Ignoring parent update.");
            updated = updated.withParentKey(old.parentKey());
        }
        categories.put(updated.key(), updated);
        saveAll();
    }

    private static boolean createsCircularChain(String categoryKey, String newParentKey) {
        String current = newParentKey;
        while (current != null) {
            if (current.equals(categoryKey)) return true;
            Category parentCat = categories.get(current);
            if (parentCat == null) break;
            current = parentCat.parentKey();
        }
        return false;
    }

    public static synchronized void removeCategory(String key, boolean unassignBlocks) {
        key = key.toLowerCase();
        categories.remove(key);
        if (unassignBlocks) {
            for (Set<String> cats : assignments.values()) {
                cats.remove(key);
            }
            assignments.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        // Reparent children to root
        for (Category cat : categories.values()) {
            if (key.equals(cat.parentKey())) {
                categories.put(cat.key(), cat.withParentKey(null));
            }
        }
        saveAll();
    }

    public static synchronized void removeAllBlocksFromCategory(String key) {
        key = key.toLowerCase();
        for (Set<String> cats : assignments.values()) {
            cats.remove(key);
        }
        assignments.entrySet().removeIf(e -> e.getValue().isEmpty());
        saveAll();
    }

    public static synchronized void moveAllBlocksToCategory(String sourceKey, String targetKey) {
        sourceKey = sourceKey.toLowerCase();
        targetKey = targetKey.toLowerCase();
        for (Set<String> cats : assignments.values()) {
            if (cats.contains(sourceKey)) {
                cats.remove(sourceKey);
                cats.add(targetKey);
            }
        }
        assignments.entrySet().removeIf(e -> e.getValue().isEmpty());
        saveAll();
    }

    public static Category getCategory(String key) {
        return key == null ? null : categories.get(key.toLowerCase());
    }

    public static Collection<Category> getAllCategories() {
        return Collections.unmodifiableCollection(categories.values());
    }

    // ── Assignments ──────────────────────────────────────────────────────────

    public static synchronized void assignBlock(String blockId, String categoryKey) {
        categoryKey = categoryKey.toLowerCase();
        if (!categories.containsKey(categoryKey)) return;
        assignments.computeIfAbsent(blockId, k -> new HashSet<>()).add(categoryKey);
        saveAll();
    }

    public static synchronized void assignBlocksBulk(Collection<String> blockIds, String categoryKey) {
        categoryKey = categoryKey.toLowerCase();
        if (!categories.containsKey(categoryKey)) return;
        for (String blockId : blockIds) {
            assignments.computeIfAbsent(blockId, k -> new HashSet<>()).add(categoryKey);
        }
        saveAll();
    }

    public static synchronized void unassignBlock(String blockId, String categoryKey) {
        categoryKey = categoryKey.toLowerCase();
        Set<String> cats = assignments.get(blockId);
        if (cats != null) {
            cats.remove(categoryKey);
            if (cats.isEmpty()) assignments.remove(blockId);
            saveAll();
        }
    }

    public static synchronized void clearAssignments(String blockId) {
        if (assignments.remove(blockId) != null) {
            saveAll();
        }
    }

    public static Set<String> getCategoriesForBlock(String blockId) {
        Set<String> cats = assignments.get(blockId);
        return cats != null ? Collections.unmodifiableSet(cats) : Collections.emptySet();
    }

    public static List<SlotData> getBlocksInCategory(String categoryKey) {
        String key = categoryKey.toLowerCase();
        return SlotManager.sortedSlots().stream()
                .filter(d -> {
                    Set<String> cats = assignments.get(d.customId);
                    return cats != null && cats.contains(key);
                })
                .collect(Collectors.toList());
    }

}
