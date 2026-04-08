package com.customblocks.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Custom screen handler for the CB GUI.
 * Uses GENERIC_9X6 (large chest) so no client-side registration is needed.
 * Intercepts all slot clicks and forwards to GuiManager — items cannot be moved.
 */
public class CbScreenHandler extends GenericContainerScreenHandler {

    public CbScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, 6);
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        // Forward GUI-area clicks to GuiManager; block EVERYTHING else (no item movement ever)
        if (slotIndex >= 0 && slotIndex < 54 && player instanceof ServerPlayerEntity sp) {
            GuiManager.handleClick(sp, slotIndex, button);
        }
        // Sync state to undo any client-side prediction of item movement
        this.syncState();
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY; // Disable shift-click entirely
    }

    @Override
    public boolean canUse(PlayerEntity player) { return true; }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        // Only clear GUI state if there's no pending chat input waiting
        if (player instanceof ServerPlayerEntity sp && !GuiManager.hasPending(sp)) {
            GuiManager.clearState(sp);
        }
    }
}
