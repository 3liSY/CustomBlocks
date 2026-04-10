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
 * Custom screen handler for all CB GUI screens.
 *
 * KEY FIXES:
 * 1. onClosed() now distinguishes "player pressed ESC" from "server reopened a new screen".
 *    When the server calls openHandledScreen(), it sets REOPENING_SCREENS before the call
 *    so onClosed() (which fires on the OLD handler) knows not to trigger ESC-back navigation.
 *
 * 2. We NEVER call clearState() from onClosed(). Every openXxx() writes the new state
 *    BEFORE openHandledScreen() is called, so clearing here would wipe the fresh state
 *    and make every second click a no-op (state == null → handleClick returns immediately).
 */
public class CbScreenHandler extends GenericContainerScreenHandler {

    public CbScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, 6);
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex >= 0 && slotIndex < 54 && player instanceof ServerPlayerEntity sp) {
            GuiManager.handleClick(sp, slotIndex, button);
        }
        this.syncState();
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) { return true; }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        if (!(player instanceof ServerPlayerEntity sp)) return;

        // If the server itself is opening a new screen (REOPENING_SCREENS is set),
        // this onClosed() is firing because the OLD screen is being replaced.
        // Do nothing — the new state is already written by the openXxx() call.
        if (GuiManager.isReopeningScreen(sp.getUuid())) return;

        // If there is a pending chat-input action, the player closed the GUI to type
        // in chat. Don't navigate back — let them type their answer.
        if (GuiManager.hasPending(sp)) return;

        // Otherwise the player pressed ESC (or /close). Navigate one level back.
        // handleEscBack() will re-open the parent screen, or do nothing if at root.
        sp.getServer().execute(() -> GuiManager.handleEscBack(sp));
    }
}
