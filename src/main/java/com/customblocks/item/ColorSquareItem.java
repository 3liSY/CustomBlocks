package com.customblocks.item;

import com.customblocks.CustomBlocksMod;
import com.customblocks.CustomBlocksConfig;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.block.SlotBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

/**
 * Right-click any CustomBlock to swap it to the same block ID but with this
 * item's colour segment substituted in.
 *
 * HOW MATCHING WORKS — segment scan (robust, handles any naming convention):
 *
 *   The block ID is split on "_".  The scanner finds the FIRST segment that
 *   exactly equals a known colour word and replaces ONLY that segment.
 *
 *   Examples (swapping to "yellow"):
 *     black_alef          → [black, alef]             → [yellow, alef]         → yellow_alef
 *     28_lam_black        → [28, lam, black]           → [28, lam, yellow]      → 28_lam_yellow
 *     black_28_lam        → [black, 28, lam]           → [yellow, 28, lam]      → yellow_28_lam
 *     alef_black_v2       → [alef, black, v2]          → [alef, yellow, v2]     → alef_yellow_v2
 *     green_letter_black  → [green, letter, black]     first colour hit = green  → yellow_letter_black
 *
 *   Partial matches are NEVER triggered — "blackboard", "greenhouse",
 *   "yellowish" are plain name segments and are left alone.
 */
public class ColorSquareItem extends Item {

    /** Every colour word the system recognises. Keep in sync with triangles + CustomBlocksMod. */
    public static final String[] KNOWN_COLORS = {"black", "yellow", "green"};

    private final String colorWord;   // e.g. "yellow"
    private final String colorName;   // e.g. "Yellow"  (display label)

    public ColorSquareItem(String colorWord, String colorName, Settings settings) {
        super(settings);
        this.colorWord = colorWord;
        this.colorName = colorName;
    }

    @Override public Text getName()                { return Text.literal(colorName + " Square"); }
    @Override public Text getName(ItemStack stack) { return getName(); }

    @Override
    public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
        if (selected && !world.isClient && world.getTime() % 12 == 0 && world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.CHERRY_LEAVES, entity.getX(), entity.getY() + 1.2, entity.getZ(), 1, 0.15, 0.15, 0.15, 0.02);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW, entity.getX(), entity.getY() + 1.2, entity.getZ(), 1, 0.2, 0.2, 0.2, 0.01);
        }
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World        world  = ctx.getWorld();
        BlockPos     pos    = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        if (world.isClient) return ActionResult.PASS;

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) return ActionResult.PASS;

        if (player != null && !player.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin)) {
            player.sendMessage(
                Text.literal("§c[CustomBlocks] You need OP to use colour squares."), true);
            if (world instanceof ServerWorld sw) sw.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            return ActionResult.FAIL;
        }

        SlotData current = SlotManager.getBySlot(sb.getSlotKey());
        if (current == null) return ActionResult.PASS;

        // ── Segment scan ──────────────────────────────────────────────────────
        // Split the ID into underscore-separated tokens, find the first token
        // that is exactly a known colour word (case-insensitive), replace it.
        String[] segments = current.customId.split("_", -1);
        int colorIdx = -1;
        for (int i = 0; i < segments.length; i++) {
            for (String known : KNOWN_COLORS) {
                if (segments[i].equalsIgnoreCase(known)) {
                    colorIdx = i;
                    break;
                }
            }
            if (colorIdx >= 0) break;
        }

        if (colorIdx < 0) {
            if (player != null) {
                player.sendMessage(Text.literal(
                    "§c[CustomBlocks] No colour segment found in \"§f" + current.customId
                    + "§c\". The colour word (black/yellow/green) must be its own underscore-"
                    + "separated token, e.g. §fblock_black§c or §fblack_block§c."), true);
                if (world instanceof ServerWorld sw) sw.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            }
            return ActionResult.FAIL;
        }

        // Replace only the colour segment, keep everything else identical
        segments[colorIdx] = colorWord;
        String targetId = String.join("_", segments);

        // Already this colour?
        if (targetId.equals(current.customId)) {
            if (player != null) {
                player.sendMessage(
                    Text.literal("§7[CustomBlocks] Already §f" + colorName + "§7."), true);
                if (world instanceof ServerWorld sw) sw.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.2f);
            }
            return ActionResult.SUCCESS;
        }

        // Target block must already exist
        SlotData target = SlotManager.getById(targetId);
        if (target == null) {
            if (player != null)
                player.sendMessage(Text.literal(
                    "§c[CustomBlocks] §f" + targetId
                    + "§c doesn't exist yet. Create it first with §f/cb createurl§c."), true);
            return ActionResult.FAIL;
        }

        // Swap block in world — flag 3 = update neighbours + notify clients
        world.setBlockState(pos, CustomBlocksMod.SLOT_BLOCKS[target.index].getDefaultState(), 3);

        if (player != null) {
            player.sendMessage(
                Text.literal("§a[CustomBlocks] Swapped to §f" + target.displayName + "§a!"), true);
            
            if (world instanceof ServerWorld sw) {
                sw.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 10, 0.2, 0.2, 0.2, 0.05);
                sw.playSound(null, pos, net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.1f);
            }
        }

        return ActionResult.SUCCESS;
    }
}
