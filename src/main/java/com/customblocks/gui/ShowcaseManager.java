package com.customblocks.gui;

import com.customblocks.CustomBlocksMod;
import com.customblocks.core.CategoryManager;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 4C — Server-side manager for Showcase Display Blocks.
 * <p>
 * Each placed ShowcaseBlock spawns an invisible ArmorStand one block above it.
 * A server-tick hook cycles through the configured block pool on a timer,
 * updating the ArmorStand's head item and custom name hologram each cycle.
 */
public final class ShowcaseManager {

    private ShowcaseManager() {}

    // ── Data ─────────────────────────────────────────────────────────────────

    /**
     * Runtime config for one showcase placement.
     * Uses a position-tag approach (same as HologramManager) so the ArmorStand
     * survives server restarts — we locate it by tag rather than caching a UUID.
     */
    private record ShowcaseData(
        String worldId,
        BlockPos pos,
        String source,       // "all" | "category:<key>"
        int intervalTicks,   // ticks between cycles (default 100 = 5 s)
        long nextCycleTick,  // server tick when we next advance
        int currentIndex     // index into current pool
    ) {}

    private static final Map<String, ShowcaseData> SHOWCASES = new ConcurrentHashMap<>();

    private static final int DEFAULT_INTERVAL = 100; // 5 seconds

    /** Tag applied to every showcase ArmorStand so we can find it on break. */
    private static final String TAG_ALL = "cb_showcase";

    private static String posTag(BlockPos pos) {
        return "cb_showcase_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
    }

    private static String key(World world, BlockPos pos) {
        return world.getRegistryKey().getValue().toString()
            + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }

    private static String key(String worldId, BlockPos pos) {
        return worldId + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Called when a player right-clicks a showcase block — prints current config
     * and a usage hint to that player's chat.
     */
    public static void openConfig(ServerPlayerEntity player, BlockPos pos) {
        String k = key(player.getWorld(), pos);
        ShowcaseData d = SHOWCASES.get(k);
        String source   = d != null ? d.source() : "all";
        int    intervalS = d != null ? d.intervalTicks() / 20 : DEFAULT_INTERVAL / 20;

        player.sendMessage(Text.literal(
            "§b[Showcase] §7Source: §f" + source
            + " §7| Interval: §f" + intervalS + "s"), false);
        player.sendMessage(Text.literal(
            "§7Use §b/cb showcase config <x> <y> <z> source <value> §7to change."), false);
    }

    /**
     * Called when a showcase block is placed — spawns the ArmorStand and
     * registers this position in the active-showcases map.
     */
    public static void onPlace(ServerWorld world, BlockPos pos, String source) {
        String worldId = world.getRegistryKey().getValue().toString();
        String k = key(worldId, pos);

        // Spawn the ArmorStand
        spawnArmorStand(world, pos);

        ShowcaseData data = new ShowcaseData(worldId, pos, source, DEFAULT_INTERVAL, 0L, 0);
        SHOWCASES.put(k, data);

        // Immediately show the first block so the stand isn't empty
        updateArmorStand(world, data);
    }

    /**
     * Called when a showcase block is broken — removes the ArmorStand and
     * deregisters the position.
     */
    public static void onBreak(World world, BlockPos pos) {
        String k = key(world, pos);
        SHOWCASES.remove(k);
        if (!(world instanceof ServerWorld sw)) return;
        removeArmorStand(sw, pos);
    }

    /**
     * Update config for a showcase (called from {@code /cb showcase config}).
     * If no showcase is registered at this position yet, places one.
     */
    public static void updateConfig(ServerWorld world, BlockPos pos, String source, int intervalSeconds) {
        String worldId = world.getRegistryKey().getValue().toString();
        String k = key(worldId, pos);
        ShowcaseData existing = SHOWCASES.get(k);
        if (existing == null) {
            onPlace(world, pos, source);
            return;
        }
        ShowcaseData updated = new ShowcaseData(
            worldId, pos, source,
            Math.max(1, intervalSeconds) * 20,
            existing.nextCycleTick(),
            existing.currentIndex()
        );
        SHOWCASES.put(k, updated);
    }

    /**
     * Server-tick hook — advances every active showcase whose timer has expired.
     * Call this from {@code ServerTickEvents.END_SERVER_TICK}.
     */
    public static void tick(MinecraftServer server) {
        if (SHOWCASES.isEmpty()) return;
        long currentTick = server.getTicks();

        for (Map.Entry<String, ShowcaseData> entry : SHOWCASES.entrySet()) {
            ShowcaseData d = entry.getValue();
            if (currentTick < d.nextCycleTick()) continue;

            // Find the world for this showcase
            ServerWorld world = findWorld(server, d.worldId());
            if (world == null) continue;

            List<SlotData> pool = buildPool(d.source());
            if (pool.isEmpty()) continue;

            int nextIdx = (d.currentIndex() + 1) % pool.size();
            ShowcaseData updated = new ShowcaseData(
                d.worldId(), d.pos(), d.source(), d.intervalTicks(),
                currentTick + d.intervalTicks(), nextIdx
            );
            entry.setValue(updated);
            updateArmorStand(world, updated);
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private static List<SlotData> buildPool(String source) {
        if (source == null || source.equals("all")) {
            return SlotManager.sortedSlots();
        }
        if (source.startsWith("category:")) {
            String cat = source.substring(9);
            List<SlotData> catBlocks = CategoryManager.getBlocksInCategory(cat);
            return catBlocks.isEmpty() ? SlotManager.sortedSlots() : catBlocks;
        }
        return SlotManager.sortedSlots();
    }

    private static void spawnArmorStand(ServerWorld world, BlockPos pos) {
        // Remove any stale stand at this position first
        removeArmorStand(world, pos);

        ArmorStandEntity stand = new ArmorStandEntity(world,
            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setCustomNameVisible(true);
        stand.addCommandTag(TAG_ALL);
        stand.addCommandTag(posTag(pos));
        world.spawnEntity(stand);
    }

    private static void updateArmorStand(ServerWorld world, ShowcaseData data) {
        Box box = searchBox(data.pos());
        List<ArmorStandEntity> stands = world.getEntitiesByType(
            EntityType.ARMOR_STAND, box,
            e -> e.getCommandTags().contains(posTag(data.pos())));

        if (stands.isEmpty()) return;
        ArmorStandEntity stand = stands.get(0);

        List<SlotData> pool = buildPool(data.source());
        if (pool.isEmpty()) return;

        int idx = Math.min(data.currentIndex(), pool.size() - 1);
        SlotData block = pool.get(idx);

        // Update hologram name
        stand.setCustomName(Text.literal("§e§l" + block.displayName));

        // Update head item — use the registered slot item for this block
        Item headItem = CustomBlocksMod.safeSlotItem(block.index);
        ItemStack headStack = headItem != null
            ? new ItemStack(headItem)
            : new ItemStack(Items.DIAMOND_BLOCK);
        stand.equipStack(EquipmentSlot.HEAD, headStack);
    }

    private static void removeArmorStand(ServerWorld world, BlockPos pos) {
        world.getEntitiesByType(
            EntityType.ARMOR_STAND, searchBox(pos),
            e -> e.getCommandTags().contains(posTag(pos))
        ).forEach(net.minecraft.entity.Entity::discard);
    }

    private static Box searchBox(BlockPos pos) {
        return new Box(
            pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
            pos.getX() + 2, pos.getY() + 4, pos.getZ() + 2);
    }

    private static ServerWorld findWorld(MinecraftServer server, String worldId) {
        for (ServerWorld sw : server.getWorlds()) {
            if (sw.getRegistryKey().getValue().toString().equals(worldId)) return sw;
        }
        return null;
    }
}
