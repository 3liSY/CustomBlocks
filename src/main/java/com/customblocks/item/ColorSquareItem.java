package com.customblocks.item;

import com.customblocks.CustomBlocksMod;
import com.customblocks.SlotManager;
import com.customblocks.block.SlotBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Right-click on any CustomBlock to swap it to the same block ID but with
 * this item's color prefix.
 *
 * Example: yellow square + black_alef  →  world block swaps to yellow_alef.
 * Strips any known color prefix from the target block's ID first, then
 * prepends this item's own prefix.
 */
public class ColorSquareItem extends Item {

    /** All color prefixes the system recognises — must stay in sync with what admins use. */
    public static final String[] COLOR_PREFIXES = {"black_", "yellow_", "green_"};

    private final String colorPrefix; // e.g. "yellow_"
    private final String colorName;   // e.g. "Yellow"

    public ColorSquareItem(String colorPrefix, String colorName, Settings settings) {
        super(settings);
        this.colorPrefix = colorPrefix;
        this.colorName   = colorName;
    }

    @Override
    public Text getName() { return Text.literal(colorName + " Square"); }

    @Override
    public Text getName(ItemStack stack) { return getName(); }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World world   = ctx.getWorld();
        BlockPos pos  = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        // Logic only runs on server — PASS on client so server result is authoritative
        if (world.isClient) return ActionResult.PASS;

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) {
            // PASS lets normal right-click behaviour through (doors, chests, etc.)
            return ActionResult.PASS;
        }

        // Require OP level 2 — same permission as all other CustomBlocks commands
        if (player != null && !player.hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("§c[CustomBlocks] You need OP (level 2) to use color squares."), true);
            return ActionResult.FAIL;
        }

        SlotManager.SlotData current = SlotManager.getBySlot(sb.getSlotKey());
        if (current == null) {
            // Slot block exists in world but has no data — PASS so other interactions still work
            return ActionResult.PASS;
        }

        // Strip any existing color prefix → get base name (e.g. "black_alef" → "alef")
        String baseName = current.customId;
        for (String prefix : COLOR_PREFIXES) {
            if (current.customId.startsWith(prefix)) {
                baseName = current.customId.substring(prefix.length());
                break;
            }
        }

        String targetId = colorPrefix + baseName;

        // Already this color?
        if (targetId.equals(current.customId)) {
            if (player != null)
                player.sendMessage(
                    Text.literal("§7[CustomBlocks] Already " + colorName + "."), true);
            return ActionResult.SUCCESS;
        }

        // Target must exist
        SlotManager.SlotData target = SlotManager.getById(targetId);
        if (target == null) {
            if (player != null)
                player.sendMessage(
                    Text.literal("§c[CustomBlocks] '" + targetId + "' doesn't exist yet. Create it first."), true);
            return ActionResult.FAIL;
        }

        // Swap block in world — flag 3 = update neighbours + send to client
        world.setBlockState(pos, CustomBlocksMod.SLOT_BLOCKS[target.index].getDefaultState(), 3);

        if (player != null)
            player.sendMessage(
                Text.literal("§a[CustomBlocks] Swapped to §f" + target.displayName + "§a!"), true);

        return ActionResult.SUCCESS;
    }
}
