package com.customblocks.core;

import com.customblocks.CustomBlocksConfig;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * I1/I2 — Manages floating hologram labels above placed custom blocks.
 * <p>
 * Holograms are invisible, marker ArmorStands tagged with {@value #TAG_ALL}
 * and a position-specific tag so they can be found and removed when the block
 * is broken, even across server restarts (the ArmorStands persist in the world).
 */
public final class HologramManager {

    private HologramManager() {}

    /** Command-tag added to every CB hologram ArmorStand. */
    private static final String TAG_ALL = "cb_hologram";

    /** Position-specific tag used to find the hologram for a given block. */
    private static String posTag(BlockPos pos) {
        return "cb_holo_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
    }

    /**
     * Spawn a hologram above the given block position.
     * Called from {@link com.customblocks.block.SlotBlock.SlotItem#postPlacement}.
     * No-op if {@link CustomBlocksConfig#hologramEnabled} is false.
     */
    public static void onBlockPlaced(ServerWorld world, BlockPos pos, String customId) {
        if (!CustomBlocksConfig.hologramEnabled) return;
        SlotData d = SlotManager.getById(customId);
        if (d == null) return;

        String label = d.hasHologramText() ? d.hologramText : d.displayName;
        float height  = CustomBlocksConfig.hologramHeight;
        String color  = CustomBlocksConfig.hologramColor;

        ArmorStandEntity stand = new ArmorStandEntity(world,
                pos.getX() + 0.5, pos.getY() + height, pos.getZ() + 0.5);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setCustomName(Text.literal(color + label));
        stand.setCustomNameVisible(true);
        stand.addCommandTag(TAG_ALL);
        stand.addCommandTag(posTag(pos));

        world.spawnEntity(stand);
    }

    /**
     * Remove the hologram at the given block position (called on block break).
     * Searches a small AABB near the position for tagged ArmorStands and discards them.
     */
    public static void onBlockRemoved(ServerWorld world, BlockPos pos) {
        String tag = posTag(pos);
        Box box = new Box(
                pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                pos.getX() + 2, pos.getY() + 4, pos.getZ() + 2);
        world.getEntitiesByType(EntityType.ARMOR_STAND, box,
                e -> e.getCommandTags().contains(tag))
            .forEach(net.minecraft.entity.Entity::discard);
    }

    /**
     * Update the hologram text for a block that has already been placed.
     * Finds the existing ArmorStand by position tag and renames it in-place.
     * If no hologram exists at this position yet, spawns one (respects config toggle).
     */
    public static void refreshHologram(ServerWorld world, BlockPos pos, String customId) {
        if (!CustomBlocksConfig.hologramEnabled) {
            // If disabled globally, just remove any stale hologram
            onBlockRemoved(world, pos);
            return;
        }
        SlotData d = SlotManager.getById(customId);
        if (d == null) { onBlockRemoved(world, pos); return; }

        String tag   = posTag(pos);
        String label = d.hasHologramText() ? d.hologramText : d.displayName;
        String color = CustomBlocksConfig.hologramColor;
        Box box = new Box(
                pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                pos.getX() + 2, pos.getY() + 4, pos.getZ() + 2);
        var existing = world.getEntitiesByType(EntityType.ARMOR_STAND, box,
                e -> e.getCommandTags().contains(tag));
        if (!existing.isEmpty()) {
            existing.get(0).setCustomName(Text.literal(color + label));
        } else {
            onBlockPlaced(world, pos, customId);
        }
    }
}
