package com.customblocks.gui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Immutable GUI state record with back-stack support.
 * <p>
 * Each player has a {@code Deque<GuiState>} back-stack. Navigation pushes
 * the current state before transitioning; ESC pops and restores.
 */
public record GuiState(
        GuiMode mode,
        String  editingId,
        int     page,
        boolean confirmDelete,
        int     shapeBoxPage,
        boolean fromCommand
) {
    // ── Factory methods ──────────────────────────────────────────────────────

    public static GuiState main(int page) {
        return new GuiState(GuiMode.MAIN, null, page, false, 0, false);
    }

    public static GuiState picker(int page) {
        return new GuiState(GuiMode.PICKER, null, page, false, 0, false);
    }

    public static GuiState pickerBroken(int page) {
        return new GuiState(GuiMode.PICKER_BROKEN, null, page, false, 0, false);
    }

    public static GuiState editor(String editingId, int returnPage) {
        return new GuiState(GuiMode.EDITOR, editingId, returnPage, false, 0, false);
    }

    public static GuiState editorFromCommand(String editingId) {
        return new GuiState(GuiMode.EDITOR, editingId, 0, true, 0, true);
    }

    public static GuiState faceEditor(String editingId, int returnPage) {
        return new GuiState(GuiMode.FACE_EDITOR, editingId, returnPage, false, 0, false);
    }

    public static GuiState shapeEditor(String editingId, int returnPage) {
        return new GuiState(GuiMode.SHAPE_EDITOR, editingId, returnPage, false, 0, false);
    }

    public static GuiState maintenance() {
        return new GuiState(GuiMode.MAINTENANCE_MENU, null, 0, false, 0, false);
    }

    public static GuiState help() {
        return new GuiState(GuiMode.HELP_MENU, null, 0, false, 0, false);
    }

    public static GuiState tools() {
        return new GuiState(GuiMode.TOOLS_GUI, null, 0, false, 0, false);
    }

    public static GuiState properties(String editingId, int returnPage) {
        return new GuiState(GuiMode.PROPERTIES_MENU, editingId, returnPage, false, 0, false);
    }

    public static GuiState sound(String editingId, int returnPage) {
        return new GuiState(GuiMode.SOUND_MENU, editingId, returnPage, false, 0, false);
    }

    public static GuiState animGui(String editingId) {
        return new GuiState(GuiMode.ANIM_GUI, editingId, 0, false, 0, false);
    }

    public static GuiState tabIconMenu() {
        return new GuiState(GuiMode.TAB_ICON_MENU, null, 0, false, 0, false);
    }

    public static GuiState findPortGui() {
        return new GuiState(GuiMode.FIND_PORT_GUI, null, 0, false, 0, false);
    }

    public static GuiState resourceCenter() {
        return new GuiState(GuiMode.RESOURCE_CENTER, null, 0, false, 0, false);
    }

    public static GuiState assistantControl() {
        return new GuiState(GuiMode.ASSISTANT_CONTROL, null, 0, false, 0, false);
    }

    public static GuiState bulkDelete(int page) {
        return new GuiState(GuiMode.BULK_DELETE, null, page, false, 0, false);
    }

    public static GuiState searchPicker(int page) {
        return new GuiState(GuiMode.SEARCH_PICKER, null, page, false, 0, false);
    }

    public static GuiState magicItems() {
        return new GuiState(GuiMode.MAGIC_ITEMS, null, 0, false, 0, false);
    }

    public static GuiState undoPicker(int page) {
        return new GuiState(GuiMode.UNDO_PICKER, null, page, false, 0, false);
    }

    public static GuiState configGui() {
        return new GuiState(GuiMode.CONFIG_GUI, null, 0, false, 0, false);
    }

    public static GuiState configWarning() {
        return new GuiState(GuiMode.CONFIG_WARNING, null, 0, false, 0, false);
    }

    public static GuiState helpCategory(int category) {
        return new GuiState(GuiMode.HELP_CATEGORY, null, category, false, 0, false);
    }

    // ── Mutation (returns new instance) ──────────────────────────────────────

    public GuiState withConfirmDelete(boolean confirm) {
        return new GuiState(mode, editingId, page, confirm, shapeBoxPage, fromCommand);
    }

    public GuiState withShapeBoxPage(int boxPage) {
        return new GuiState(mode, editingId, page, confirmDelete, boxPage, fromCommand);
    }

    public GuiState withPage(int newPage) {
        return new GuiState(mode, editingId, newPage, confirmDelete, shapeBoxPage, fromCommand);
    }

    // ── Back-stack helper ────────────────────────────────────────────────────

    /**
     * Creates a new back-stack initialized with the main menu as the bottom.
     */
    public static Deque<GuiState> newBackStack() {
        return new ArrayDeque<>();
    }
}
