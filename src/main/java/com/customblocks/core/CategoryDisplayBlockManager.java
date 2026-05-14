package com.customblocks.core;

import com.google.gson.*;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent registry that maps placed Category Display Block positions
 * (per dimension) to category keys. Right-clicking a registered position
 * opens the category browser; sneak + right-click picks the block back up.
 *
 * <p>Storage: {@code config/customblocks/display_blocks.json}
 * Atomic write pattern (.tmp → ATOMIC_MOVE) for crash-safety.
 */
public final class CategoryDisplayBlockManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DATA_DIR = "config/customblocks";
    private static final String DATA_FILE = "display_blocks.json";

    /** dimensionId -> ("x,y,z" -> categoryKey). Concurrent for safe access. */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> REGISTRY =
            new ConcurrentHashMap<>();

    /** NBT/data component key embedded in the item to identify its category. */
    public static final String NBT_CATEGORY_KEY = "cb_display_category";

    private CategoryDisplayBlockManager() {}

    // ── Persistence ──────────────────────────────────────────────────────────

    public static synchronized void loadAll() {
        Path dir = Path.of(DATA_DIR);
        Path file = dir.resolve(DATA_FILE);
        Path bak1 = dir.resolve("display_blocks.bak1.json");
        try {
            Files.createDirectories(dir);
            if (!Files.exists(file)) {
                if (Files.exists(bak1)) {
                    LOGGER.warn("[CustomBlocks] display_blocks.json missing! Auto-restoring from backup.");
                    Files.copy(bak1, file, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    REGISTRY.clear();
                    return;
                }
            }
            try {
                parseFile(file);
            } catch (Exception e) {
                LOGGER.error("[CustomBlocks] display_blocks.json corrupted! Restoring from .bak1...", e);
                if (Files.exists(bak1)) {
                    Files.copy(bak1, file, StandardCopyOption.REPLACE_EXISTING);
                    parseFile(file);
                }
            }
            SlotManager.rotateBackups(file);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to load display blocks", e);
        }
    }

    private static void parseFile(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            REGISTRY.clear();
            return;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        REGISTRY.clear();
        for (Map.Entry<String, JsonElement> dim : root.entrySet()) {
            ConcurrentHashMap<String, String> dimMap = new ConcurrentHashMap<>();
            JsonObject dimObj = dim.getValue().getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : dimObj.entrySet()) {
                dimMap.put(e.getKey(), e.getValue().getAsString());
            }
            if (!dimMap.isEmpty()) REGISTRY.put(dim.getKey(), dimMap);
        }
    }

    public static synchronized void saveAll() {
        Path dir = Path.of(DATA_DIR);
        Path file = dir.resolve(DATA_FILE);
        Path tmp = dir.resolve("display_blocks.json.tmp");
        try {
            Files.createDirectories(dir);
            JsonObject root = new JsonObject();
            for (Map.Entry<String, ConcurrentHashMap<String, String>> dim : REGISTRY.entrySet()) {
                JsonObject dimObj = new JsonObject();
                for (Map.Entry<String, String> e : dim.getValue().entrySet()) {
                    dimObj.addProperty(e.getKey(), e.getValue());
                }
                root.add(dim.getKey(), dimObj);
            }
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to save display blocks", e);
        }
    }

    // ── Registry Ops ─────────────────────────────────────────────────────────

    private static String posKey(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static void register(String dimensionId, BlockPos pos, String categoryKey) {
        REGISTRY.computeIfAbsent(dimensionId, k -> new ConcurrentHashMap<>())
                .put(posKey(pos), categoryKey.toLowerCase());
        saveAll();
    }

    public static String unregister(String dimensionId, BlockPos pos) {
        ConcurrentHashMap<String, String> dimMap = REGISTRY.get(dimensionId);
        if (dimMap == null) return null;
        String removed = dimMap.remove(posKey(pos));
        if (removed != null) {
            if (dimMap.isEmpty()) REGISTRY.remove(dimensionId);
            saveAll();
        }
        return removed;
    }

    public static String getCategoryAt(String dimensionId, BlockPos pos) {
        ConcurrentHashMap<String, String> dimMap = REGISTRY.get(dimensionId);
        return dimMap == null ? null : dimMap.get(posKey(pos));
    }

    // ── Item Stack Construction ──────────────────────────────────────────────

    /**
     * Builds the display-block ItemStack the player gets from the controller.
     * Uses {@link net.minecraft.component.DataComponentTypes#CUSTOM_DATA} to embed the
     * category key for placement-time detection.
     */
    public static ItemStack createDisplayBlockStack(Category category) {
        net.minecraft.item.Item base = resolveDisplayItem(category);
        ItemStack stack = new ItemStack(base);

        NbtCompound tag = new NbtCompound();
        tag.putString(NBT_CATEGORY_KEY, category.key());
        stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_DATA,
                net.minecraft.component.type.NbtComponent.of(tag));

        stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                Text.literal(VoiceCatalog.format("cmd.display_block_item_name", category.displayName())));

        net.minecraft.component.type.LoreComponent lore = new net.minecraft.component.type.LoreComponent(java.util.List.of(
                Text.literal(VoiceCatalog.format("cmd.display_block_lore_place")),
                Text.literal(VoiceCatalog.format("cmd.display_block_lore_right_click")),
                Text.literal(VoiceCatalog.format("cmd.display_block_lore_sneak_pickup")),
                Text.literal(""),
                Text.literal(VoiceCatalog.format("cmd.display_block_lore_category_id", category.key()))
        ));
        stack.set(net.minecraft.component.DataComponentTypes.LORE, lore);

        return stack;
    }

    /** Pulls the configured display item, falling back to BARREL. */
    public static net.minecraft.item.Item resolveDisplayItem(Category category) {
        String type = category.displayBlockType();
        if (type == null || type.isBlank() || "vanilla".equalsIgnoreCase(type)) {
            return Items.BARREL;
        }
        try {
            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(type);
            if (id != null) {
                net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
                if (item != null && item != Items.AIR) return item;
            }
        } catch (Exception ignored) {}
        return Items.BARREL;
    }

    /** Reads the embedded category key from an ItemStack, or null if not a display block. */
    public static String readCategoryFromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        net.minecraft.component.type.NbtComponent comp = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return null;
        NbtCompound nbt = comp.copyNbt();
        if (nbt == null || !nbt.contains(NBT_CATEGORY_KEY)) return null;
        return nbt.getString(NBT_CATEGORY_KEY);
    }
}
