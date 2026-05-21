package com.customblocks.gui;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.customblocks.CustomBlocksMod;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Custom screen handler for all CB GUI screens.
 *
 * KEY BEHAVIOURS:
 * 1. onClosed() distinguishes "player pressed ESC" from "server reopened a new screen".
 * 2. refreshWith() allows updating slot contents IN PLACE without reopening the screen,
 *    which prevents the cursor from snapping to the centre of the screen on every click.
 * 3. We NEVER call clearState() from onClosed().
 */
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class CbScreenHandler extends GenericContainerScreenHandler {

    private final Inventory inventory;
    private boolean disposed = false;

    public CbScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory) {
        // Standardize on 6 rows (54 slots) for all CustomBlocks manageable menus.
        // If the inventory is small (like a chest 9x3), we use 3 rows, but for anything
        // larger (like our editors/pickers), we use 6 rows to allow for the 'Premium Depth' feel.
        super(inventory.size() <= 27 ? ScreenHandlerType.GENERIC_9X3 : ScreenHandlerType.GENERIC_9X6,
              syncId, playerInventory, inventory, inventory.size() <= 27 ? 3 : 6);
        this.inventory = inventory;
    }

    /**
     * Update the visible inventory contents IN PLACE and sync to the client.
     * This avoids reopening the screen (which resets the mouse cursor position).
     */
    public void refreshWith(SimpleInventory newInv) {
        int count = Math.min(54, newInv.size());
        for (int i = 0; i < count; i++) {
            ItemStack newStack = newInv.getStack(i);
            if (!ItemStack.areEqual(inventory.getStack(i), newStack)) {
                inventory.setStack(i, newStack);
            }
        }
        this.syncState();
    }

    public boolean isDisposed() { return disposed; }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex >= 0 && slotIndex < inventory.size() && player instanceof ServerPlayerEntity sp) {
            try {
                GuiManager.handleClick(sp, slotIndex, button, actionType);
            } catch (Exception e) {
                CustomBlocksMod.LOGGER.error("[CustomBlocks] GUI click error in slot {}", slotIndex, e);
                sp.sendMessage(Text.literal("§c[CB] Something went wrong. The action was not applied."), true);
                // Force-close so client and server agree the screen is closed
                sp.closeHandledScreen();
                return;
            }
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
        disposed = true;
        if (!(player instanceof ServerPlayerEntity sp)) return;

        if (GuiManager.isReopeningScreen(sp.getUuid())) return;
        if (GuiManager.hasPending(sp)) return;

        net.minecraft.server.MinecraftServer cbSrv = sp.getServer();
        if (cbSrv == null) return;
        cbSrv.execute(() -> GuiManager.handleEscBack(sp));
    }
}
