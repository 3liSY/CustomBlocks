package com.customblocks.item;

import com.customblocks.command.PermissionHelper;
import com.customblocks.core.LockManager;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.core.UndoManager;
import com.customblocks.block.SlotBlock;
import com.customblocks.gui.FeedbackHelper;
import com.customblocks.gui.GuiManager;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/**
 * Deleter Tool — right-click a custom block to delete it.
 * Without shift: opens a confirmation GUI first.
 * With shift: deletes immediately, no GUI.
 */
public class DeleterItem extends Item {

    public DeleterItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World world = ctx.getWorld();
        if (world.isClient) return ActionResult.SUCCESS;

        PlayerEntity player = ctx.getPlayer();
        if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
        if (!PermissionHelper.canUseTool(sp)) {
            FeedbackHelper.actionBar(sp, "§b§l[CB]§r §fYou don't have permission to delete blocks.");
            return ActionResult.PASS;
        }

        BlockPos pos = ctx.getBlockPos();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) return ActionResult.PASS;

        SlotData d = SlotManager.getBySlot(sb.getSlotKey());
        if (d == null) return ActionResult.PASS;

        if (LockManager.isLocked(d.customId)) {
            FeedbackHelper.actionBar(sp, "§c§l🔒 §r§cBlock is locked — /cb unlock " + d.customId);
            return ActionResult.PASS;
        }

        if (sp.isSneaking()) {
            // Shift: instant delete
            GuiManager.executeDeleterDelete(sp, d.customId + "|" + pos.asLong());
        } else {
            // No shift: open confirm GUI
            GuiManager.openDeleterConfirmGui(sp, d.customId + "|" + pos.asLong());
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.literal("§7Right-click: §econfirm screen"));
        tooltip.add(Text.literal("§7Shift + right-click: §cinstant delete"));
    }
}
