package com.customblocks.item;

import com.customblocks.CustomBlocksMod;
import com.customblocks.SlotManager;
import com.customblocks.block.SlotBlock;
import com.customblocks.block.UndoHistory;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class ColorTriangleItem extends Item {
    private static final String[] COLORS = new String[]{"black", "yellow", "green", "white", "red", "blue", "purple", "orange", "pink", "gray"};
    private final String colorToken;
    private final String colorName;

    public ColorTriangleItem(String colorToken, String colorName, Item.Settings settings) {
        super(settings);
        this.colorToken = colorToken;
        this.colorName = colorName;
    }

    public Text getName() {
        return Text.literal(this.colorName + " Triangle");
    }

    public Text getName(ItemStack stack) {
        return this.getName();
    }

    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();
        if (world.isClient) {
            return ActionResult.PASS;
        }
        BlockState state = world.getBlockState(pos);
        Block var7 = state.getBlock();
        if (var7 instanceof SlotBlock) {
            SlotBlock sb = (SlotBlock)var7;
            if (player != null && !player.hasPermissionLevel(2)) {
                player.sendMessage(Text.literal("§c[CustomBlocks] You need OP (level 2) to use color triangles."), true);
                return ActionResult.FAIL;
            }
            SlotManager.SlotData current = SlotManager.getBySlot(sb.getSlotKey());
            if (current == null) {
                return ActionResult.PASS;
            }
            String currentId = current.customId;
            
            // Get the base ID without any color
            String baseId = getBaseId(currentId);
            
            // Try to find the colored variant first
            String targetColorId = this.colorToken + "_" + baseId;
            SlotManager.SlotData target = SlotManager.getById(targetColorId);
            
            // If not found, try other patterns
            if (target == null && !baseId.isEmpty()) {
                target = findColorVariant(baseId, this.colorToken);
            }
            
            // If still not found, try baseId with color suffix
            if (target == null && !baseId.isEmpty()) {
                targetColorId = baseId + "_" + this.colorToken;
                target = SlotManager.getById(targetColorId);
            }
            
            // Last resort: try just the color name as ID
            if (target == null) {
                target = SlotManager.getById(this.colorToken);
            }
            
            if (target == null) {
                if (player != null) {
                    player.sendMessage(Text.literal("§c[CustomBlocks] No " + this.colorName + " variant found for '" + currentId + "'. Create '" + targetColorId + "' first."), true);
                }
                return ActionResult.FAIL;
            }
            
            // Check if already this color
            String targetId = target.customId;
            if (targetId.equals(currentId)) {
                if (player != null) {
                    player.sendMessage(Text.literal("§7[CustomBlocks] Already " + this.colorName + "."), true);
                }
                return ActionResult.SUCCESS;
            }
            
            if (player instanceof ServerPlayerEntity sp) {
                UndoHistory.push(sp, pos, sb.getSlotKey(), CustomBlocksMod.SLOT_BLOCKS[target.index].getSlotKey());
            }

            world.setBlockState(pos, CustomBlocksMod.SLOT_BLOCKS[target.index].getDefaultState(), 3);
            if (player != null) {
                player.sendMessage(Text.literal("§a[CustomBlocks] Changed background to §f" + this.colorName + "§a!"), true);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    // Get the base ID by stripping all known colors
    private String getBaseId(String id) {
        String result = id;
        for (String color : COLORS) {
            result = stripColor(result, color);
        }
        // Clean up any double underscores or leading/trailing underscores
        result = result.replaceAll("_+", "_").replaceAll("^_", "").replaceAll("_$", "");
        return result;
    }

    private static SlotManager.SlotData findColorVariant(String baseId, String newColor) {
        if (baseId == null || baseId.isEmpty()) {
            return null;
        }
        // Search for any block that contains the baseId and the new color
        for (SlotManager.SlotData d : SlotManager.allSlots()) {
            String id = d.customId;
            // Check if this ID contains the baseId and the color
            if (id.contains(baseId) && id.contains(newColor)) {
                return d;
            }
        }
        // Try prefix match: color_baseId
        SlotManager.SlotData exact = SlotManager.getById(newColor + "_" + baseId);
        if (exact != null) return exact;
        // Try suffix match: baseId_color
        exact = SlotManager.getById(baseId + "_" + newColor);
        if (exact != null) return exact;
        return null;
    }

    private static String stripColor(String id, String color) {
        if (id.equals(color)) {
            return "";
        }
        if (id.startsWith(color + "_")) {
            return id.substring(color.length() + 1);
        }
        if (id.endsWith("_" + color)) {
            return id.substring(0, id.length() - color.length() - 1);
        }
        String mid = "_" + color + "_";
        int idx = id.indexOf(mid);
        if (idx >= 0) {
            return id.substring(0, idx) + id.substring(idx + color.length() + 1);
        }
        return id;
    }
}
