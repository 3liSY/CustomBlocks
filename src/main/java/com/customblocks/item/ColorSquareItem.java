package com.customblocks.item;

import com.customblocks.CustomBlocksMod;
import com.customblocks.SlotManager;
import com.customblocks.block.SlotBlock;
import com.customblocks.block.UndoHistory;
import java.util.Iterator;
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

public class ColorSquareItem extends Item {
    private static final String[] COLORS = new String[]{"black", "yellow", "green", "white", "red", "blue", "purple", "orange", "pink", "gray"};
    private final String colorToken;
    private final String colorName;

    public ColorSquareItem(String colorToken, String colorName, Item.Settings settings) {
        super(settings);
        this.colorToken = colorToken;
        this.colorName = colorName;
    }

    public Text getName() {
        return Text.literal(this.colorName + " Square");
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
        } else {
            BlockState state = world.getBlockState(pos);
            Block var7 = state.getBlock();
            if (var7 instanceof SlotBlock) {
                SlotBlock sb = (SlotBlock)var7;
                if (player != null && !player.hasPermissionLevel(2)) {
                    player.sendMessage(Text.literal("§c[CustomBlocks] You need OP (level 2) to use color squares."), true);
                    return ActionResult.FAIL;
                } else {
                    SlotManager.SlotData current = SlotManager.getBySlot(sb.getSlotKey());
                    if (current == null) {
                        return ActionResult.PASS;
                    } else {
                        String currentId = current.customId;
                        String currentColor = colorOf(currentId);
                        if (this.colorToken.equals(currentColor)) {
                            if (player != null) {
                                player.sendMessage(Text.literal("§7[CustomBlocks] Already " + this.colorName + "."), true);
                            }
                            return ActionResult.SUCCESS;
                        } else {
                            SlotManager.SlotData target = findColorVariant(currentId, currentColor, this.colorToken);
                            if (target == null) {
                                if (player != null) {
                                    player.sendMessage(Text.literal("§c[CustomBlocks] No " + this.colorName + " variant found for '" + currentId + "'."), true);
                                }
                                return ActionResult.FAIL;
                            } else {
                                if (player instanceof ServerPlayerEntity) {
                                    ServerPlayerEntity sp = (ServerPlayerEntity)player;
                                    UndoHistory.push(sp, pos, sb.getSlotKey(), CustomBlocksMod.SLOT_BLOCKS[target.index].getSlotKey());
                                }
                                world.setBlockState(pos, CustomBlocksMod.SLOT_BLOCKS[target.index].getDefaultState(), 3);
                                if (player != null) {
                                    player.sendMessage(Text.literal("§a[CustomBlocks] Swapped to §f" + target.displayName + "§a!"), true);
                                }
                                return ActionResult.SUCCESS;
                            }
                        }
                    }
                }
            } else {
                return ActionResult.PASS;
            }
        }
    }

    private static SlotManager.SlotData findColorVariant(String currentId, String currentColor, String newColor) {
        String currentBase;
        if (currentColor != null) {
            currentBase = replaceColor(currentId, currentColor, newColor);
            SlotManager.SlotData d = SlotManager.getById(currentBase);
            if (d != null) {
                return d;
            }
        }

        currentBase = currentColor != null ? stripColor(currentId, currentColor) : currentId;
        Iterator var7 = SlotManager.allSlots().iterator();

        SlotManager.SlotData d;
        do {
            if (!var7.hasNext()) {
                return null;
            }
            d = (SlotManager.SlotData)var7.next();
        } while(!d.customId.startsWith(currentBase));

        return d;
    }

    private static String colorOf(String id) {
        for (String color : COLORS) {
            if (id.contains(color)) {
                return color;
            }
        }
        return null;
    }

    private static String replaceColor(String id, String oldColor, String newColor) {
        return id.replace(oldColor, newColor);
    }

    private static String stripColor(String id, String color) {
        return id.replace("_" + color, "").replace(color + "_", "");
    }
}