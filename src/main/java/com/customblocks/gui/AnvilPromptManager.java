package com.customblocks.gui;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reusable short-form anvil prompt controller.
 * Feeds submitted text back through GuiManager's existing validated input flow.
 */
public final class AnvilPromptManager {

    private static final int MAX_NAME_LENGTH = 50;
    private static final Map<UUID, PromptSession> SESSIONS = new ConcurrentHashMap<>();

    private AnvilPromptManager() {}

    public static void open(ServerPlayerEntity player, Text title, ItemStack promptItem, String initialText) {
        PromptSession session = new PromptSession();
        SESSIONS.put(player.getUuid(), session);

        ItemStack seedItem = promptItem == null || promptItem.isEmpty()
            ? new ItemStack(Items.NAME_TAG)
            : promptItem.copyWithCount(1);
        String seedText = initialText == null ? "" : initialText;

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInventory, ignored) -> new PromptAnvilScreenHandler(syncId, playerInventory, seedItem, seedText, session),
            title));
    }

    private static void submit(ServerPlayerEntity player, String value, PromptSession session) {
        if (session.closed) {
            return;
        }
        session.closed = true;
        SESSIONS.remove(player.getUuid(), session);
        GuiManager.handleChatInput(player, value);
    }

    private static void cancel(ServerPlayerEntity player, PromptSession session) {
        if (session.closed) {
            return;
        }
        session.closed = true;
        SESSIONS.remove(player.getUuid(), session);
        if (!player.isDisconnected()) {
            GuiManager.handleChatInput(player, "cancel");
        }
    }

    private static final class PromptSession {
        private volatile boolean closed;
    }

    private static final class PromptAnvilScreenHandler extends AnvilScreenHandler {
        private final ItemStack promptItem;
        private final PromptSession session;
        private String currentText;

        private PromptAnvilScreenHandler(int syncId, PlayerInventory playerInventory, ItemStack promptItem, String initialText, PromptSession session) {
            super(syncId, playerInventory, ScreenHandlerContext.EMPTY);
            this.promptItem = promptItem.copyWithCount(1);
            this.session = session;
            this.currentText = clamp(initialText);
            this.input.setStack(0, createPromptStack(this.promptItem, this.currentText));
            this.input.setStack(1, ItemStack.EMPTY);
            updateResult();
        }

        @Override
        public boolean setNewItemName(String newItemName) {
            this.currentText = clamp(newItemName);
            updateResult();
            return true;
        }

        @Override
        public void updateResult() {
            if (currentText.isBlank()) {
                this.output.setStack(0, ItemStack.EMPTY);
                sendContentUpdates();
                return;
            }

            ItemStack result = this.promptItem.copyWithCount(1);
            result.set(DataComponentTypes.CUSTOM_NAME, Text.literal(currentText).styled(style -> style.withItalic(false)));
            this.output.setStack(0, result);
            sendContentUpdates();
        }

        @Override
        protected boolean canTakeOutput(PlayerEntity player, boolean present) {
            return present && !currentText.isBlank();
        }

        @Override
        protected void onTakeOutput(PlayerEntity player, ItemStack stack) {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return;
            }
            String submitted = currentText.trim();
            if (submitted.isEmpty()) {
                return;
            }

            clearPromptInventory();
            setCursorStack(ItemStack.EMPTY);
            submit(serverPlayer, submitted, session);
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (slotIndex == 0 || slotIndex == 1) {
                return;
            }
            super.onSlotClick(slotIndex, button, actionType, player);
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void onClosed(PlayerEntity player) {
            clearPromptInventory();
            super.onClosed(player);
            if (player instanceof ServerPlayerEntity serverPlayer) {
                cancel(serverPlayer, session);
            }
        }

        private void clearPromptInventory() {
            this.input.setStack(0, ItemStack.EMPTY);
            this.input.setStack(1, ItemStack.EMPTY);
            this.output.setStack(0, ItemStack.EMPTY);
        }

        private static ItemStack createPromptStack(ItemStack base, String text) {
            ItemStack stack = base.copyWithCount(1);
            String label = text == null || text.isBlank() ? "Type Here" : text;
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(label).styled(style -> style.withItalic(false)));
            return stack;
        }

        private static String clamp(String value) {
            if (value == null) {
                return "";
            }
            String trimmed = value.length() > MAX_NAME_LENGTH ? value.substring(0, MAX_NAME_LENGTH) : value;
            return trimmed;
        }
    }
}
