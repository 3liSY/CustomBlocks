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
    // Factory methods

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

    public static GuiState faceChangeSelect(String editingId, int returnPage) {
        return new GuiState(GuiMode.FACE_CHANGE_SELECT, editingId, returnPage, false, 0, false);
    }

    public static GuiState faceChangePicker(String editingId, int page) {
        return new GuiState(GuiMode.FACE_CHANGE_PICKER, editingId, page, false, 0, false);
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

    public static GuiState welcome() {
        return new GuiState(GuiMode.WELCOME_MENU, null, 0, false, 0, false);
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

    public static GuiState animGui(String editingId, int returnPage) {
        return new GuiState(GuiMode.ANIM_GUI, editingId, returnPage, false, 0, false);
    }

    public static GuiState animConfirmAbandon(String editingId, int returnPage) {
        return new GuiState(GuiMode.ANIM_CONFIRM_ABANDON, editingId, returnPage, false, 0, false);
    }

    public static GuiState tabIconMenu() {
        return new GuiState(GuiMode.TAB_ICON_MENU, null, 0, false, 0, false);
    }

    // FIND_PORT_GUI, ASSISTANT_CONTROL — factories removed; modes reserved but not reachable yet.

    public static GuiState resourceCenter() {
        return new GuiState(GuiMode.RESOURCE_CENTER, null, 0, false, 0, false);
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

    /** {@code page} is the config sub-tab: {@code 0} main panel, {@code 1} permission fallbacks. */
    public static GuiState configGui(int tab) {
        return new GuiState(GuiMode.CONFIG_GUI, null, tab, false, 0, false);
    }

    public static GuiState configGui() {
        return configGui(0);
    }

    public static GuiState configWarning() {
        return new GuiState(GuiMode.CONFIG_WARNING, null, 0, false, 0, false);
    }

    public static GuiState helpCategory(int category) {
        return new GuiState(GuiMode.HELP_CATEGORY, null, category, false, 0, false);
    }

    public static GuiState bgStudio() {
        return new GuiState(GuiMode.BG_STUDIO, null, 0, false, 0, false);
    }

    public static GuiState uncategorizedPicker(int page) {
        return new GuiState(GuiMode.UNCATEGORIZED_PICKER, null, page, false, 0, false);
    }

    public static GuiState assignmentDecision(String blockId, int returnPage) {
        return new GuiState(GuiMode.ASSIGNMENT_DECISION, blockId, returnPage, false, 0, false);
    }

    public static GuiState categoryPicker(String blockId, int page) {
        return new GuiState(GuiMode.CATEGORY_PICKER, blockId, page, false, 0, false);
    }

    public static GuiState categoryBrowser(int page) {
        return new GuiState(GuiMode.CATEGORY_BROWSER, null, page, false, 0, false);
    }

    public static GuiState categoryDetail(String categoryKey, int page) {
        return new GuiState(GuiMode.CATEGORY_DETAIL, categoryKey, page, false, 0, false);
    }

    public static GuiState categoryController(int page) {
        return new GuiState(GuiMode.CATEGORY_CONTROLLER, null, page, false, 0, false);
    }

    public static GuiState categoryEditor(String categoryKey, int tabIndex) {
        return new GuiState(GuiMode.CATEGORY_EDITOR, categoryKey, tabIndex, false, 0, false);
    }

    public static GuiState subcategoryController(String parentKey, int page) {
        return new GuiState(GuiMode.SUBCATEGORY_CONTROLLER, parentKey, page, false, 0, false);
    }

    public static GuiState importConflict(String blockId) {
        return new GuiState(GuiMode.IMPORT_CONFLICT, blockId, 0, false, 0, false);
    }

    public static GuiState deleteCategoryMenu(String categoryKey) {
        return new GuiState(GuiMode.DELETE_CATEGORY_MENU, categoryKey, 0, false, 0, false);
    }

    public static GuiState mergeCategoryPickerTarget(String sourceCategoryKey, int page) {
        return new GuiState(GuiMode.MERGE_CATEGORY_PICKER_TARGET, sourceCategoryKey, page, false, 0, false);
    }

    public static GuiState bulkAssignPicker(int page) {
        return new GuiState(GuiMode.BULK_ASSIGN_PICKER, null, page, false, 0, false);
    }

    public static GuiState sortBlocksMenu(String categoryKey) {
        return new GuiState(GuiMode.SORT_BLOCKS_MENU, categoryKey, 0, false, 0, false);
    }

    /** 1.27 — global picker sort menu; returnPage stored in {@link #page()}. */
    public static GuiState pickerSort(int returnPage) {
        return new GuiState(GuiMode.SORT_BLOCKS_MENU, "__picker_sort__", returnPage, false, 0, false);
    }

    public static GuiState categoryStats(String categoryKey) {
        return new GuiState(GuiMode.CATEGORY_STATS, categoryKey, 0, false, 0, false);
    }

    public static GuiState categoryBlockContext(String categoryKey, String blockId, int returnPage) {
        // We need both categoryKey and blockId. Let's put categoryKey in editingId, and blockId in a temporary lookup, or vice-versa.
        // Or we can encode it as categoryKey|blockId
        return new GuiState(GuiMode.CATEGORY_BLOCK_CONTEXT, categoryKey + "|" + blockId, returnPage, false, 0, false);
    }

    public static GuiState categoryIconPicker(String categoryKey, int page, boolean isCustomTab) {
        return new GuiState(GuiMode.CATEGORY_ICON_PICKER, categoryKey, page, isCustomTab, 0, false);
    }

    public static GuiState bulkRecolorWizard(int page) {
        return new GuiState(GuiMode.BULK_RECOLOR_WIZARD, null, page, false, 0, false);
    }

    public static GuiState bulkRecolorConfirm(int returnPage) {
        return new GuiState(GuiMode.BULK_RECOLOR_CONFIRM, null, returnPage, false, 0, false);
    }

    public static GuiState colorFillMode() {
        return new GuiState(GuiMode.COLOR_FILL_MODE, null, 0, false, 0, false);
    }

    public static GuiState recoverGui(int page) {
        return new GuiState(GuiMode.RECOVER_GUI, null, page, false, 0, false);
    }

    public static GuiState featureMenu(int tab) {
        return new GuiState(GuiMode.FEATURE_MENU, null, tab, false, 0, false);
    }

    // PERMISSIONS_SUMMARY (Phase A6) — factory removed; mode reserved but not reachable yet.

    /** K2 — usage statistics dashboard. */
    public static GuiState statsGui() {
        return new GuiState(GuiMode.STATS_GUI, null, 0, false, 0, false);
    }

    /** H4 — variant texture manager for a block. editingId = customId, page = unused. */
    public static GuiState variantGui(String editingId) {
        return new GuiState(GuiMode.VARIANT_GUI, editingId, 0, false, 0, false);
    }

    /** G1 — color studio. editingId = customId. */
    public static GuiState colorStudio(String editingId) {
        return new GuiState(GuiMode.COLOR_STUDIO, editingId, 0, false, 0, false);
    }

    /** G2 — palette generator. editingId = customId. */
    public static GuiState paletteGenerator(String editingId) {
        return new GuiState(GuiMode.PALETTE_GENERATOR, editingId, 0, false, 0, false);
    }

    /** J2 — AI smart suggest GUI. editingId = customId. */
    public static GuiState aiSuggestGui(String editingId) {
        return new GuiState(GuiMode.AI_SUGGEST_GUI, editingId, 0, false, 0, false);
    }

    /** L3 — Cloud Vault market browser. page = current page index. */
    public static GuiState marketGui(int page) {
        return new GuiState(GuiMode.MARKET_GUI, null, page, false, 0, false);
    }

    /** Phase 2 — Bulk operations hub. */
    public static GuiState bulkHub() {
        return new GuiState(GuiMode.BULK_HUB, null, 0, false, 0, false);
    }

    /** Phase 2 — Bulk op picker. editingId = operation key, page = current page. */
    public static GuiState bulkOpPicker(String opId, int page) {
        return new GuiState(GuiMode.BULK_OP_PICKER, opId, page, false, 0, false);
    }

    /**
     * Phase 3.1 — Color Library picker.
     * editingId = context string (e.g. "triangle", "square", "recolor:<blockId>").
     * page = not used (always 0).
     */
    public static GuiState colorPicker(String context) {
        return new GuiState(GuiMode.COLOR_PICKER, context, 0, false, 0, false);
    }

    /** 5.25 — Voice Mode Picker GUI. */
    public static GuiState voicePicker() {
        return new GuiState(GuiMode.VOICE_PICKER, null, 0, false, 0, false);
    }

    /** Color Tools Hub — unified hub for color tool management. */
    public static GuiState colorsHub() {
        return new GuiState(GuiMode.COLORS_HUB, null, 0, false, 0, false);
    }

    /** Rename Tool Picker — shows custom triangles/squares in inventory. */
    public static GuiState renameToolPicker() {
        return new GuiState(GuiMode.RENAME_TOOL_PICKER, null, 0, false, 0, false);
    }

    /** Feature Menu shortcut — favorited blocks list. page = current page index. */
    public static GuiState favoritesGui(int page) {
        return new GuiState(GuiMode.FAVORITES_GUI, null, page, false, 0, false);
    }

    /** Feature Menu shortcut — recently-edited blocks (no pagination). */
    public static GuiState recentGui() {
        return new GuiState(GuiMode.RECENT_GUI, null, 0, false, 0, false);
    }

    // DRESS_GUI (3.3), GRADIENT_GUI (3.4), IMPORT_WIZARD (4A.6), RETEXTURE_WIZARD (4A.7)
    // — factories removed; modes reserved but not reachable yet.

    /** Phase 9.2 — Safety Center. */
    public static GuiState safetyCenter() {
        return new GuiState(GuiMode.SAFETY_CENTER, null, 0, false, 0, false);
    }

    /** Phase 9.3 — History GUI. page = current page index. */
    public static GuiState historyGui(int page) {
        return new GuiState(GuiMode.HISTORY_GUI, null, page, false, 0, false);
    }

    /** Phase 10.1 — Script GUI. page = current page index. */
    public static GuiState scriptGui(int page) {
        return new GuiState(GuiMode.SCRIPT_GUI, null, page, false, 0, false);
    }

    /** Phase 10.1 — Script summary. editingId = script name. page = step count. */
    public static GuiState scriptSummary(String scriptName, int stepCount) {
        return new GuiState(GuiMode.SCRIPT_SUMMARY, scriptName, stepCount, false, 0, false);
    }

    /** Phase 10.3 — Cache Dashboard. page = tab index (0-4). */
    public static GuiState cacheDashboard(int tab) {
        return new GuiState(GuiMode.CACHE_DASHBOARD, null, tab, false, 0, false);
    }

    /** Phase 10.4 — Audit GUI. page = current page index. */
    public static GuiState auditGui(int page) {
        return new GuiState(GuiMode.AUDIT_GUI, null, page, false, 0, false);
    }

    /** Phase 11.1 — AI Gen prompt. editingId = description typed so far (null if fresh). */
    public static GuiState aiGen(String description) {
        return new GuiState(GuiMode.AI_GEN, description, 0, false, 0, false);
    }

    // AI_PICKER (Phase 11.1) — factory removed; GuiMode.AI_PICKER is reserved but not reachable yet.

    /** Phase 11.2 — Custom Color Studio. editingId = current hex string (without #). */
    public static GuiState customColorStudio(String hex) {
        return new GuiState(GuiMode.CUSTOM_COLOR_STUDIO, hex, 0, false, 0, false);
    }

    public static GuiState achievementsGui(int page) {
        return new GuiState(GuiMode.ACHIEVEMENTS_GUI, null, page, false, 0, false);
    }

    /** V4-43 — Snapshots GUI. page = current page index. */
    public static GuiState snapshotsGui(int page) {
        return new GuiState(GuiMode.SNAPSHOTS_GUI, null, page, false, 0, false);
    }

    /** V4-18 — Deleted Blocks GUI. page = current page index. */
    public static GuiState deletedBlocksGui(int page) {
        return new GuiState(GuiMode.DELETED_BLOCKS_GUI, null, page, false, 0, false);
    }

    /**
     * V4-13 — Box Nudge Editor.
     * editingId = block customId, page = shape editor returnPage,
     * shapeBoxPage = index of the box being nudged.
     */
    public static GuiState boxNudgeEditor(String blockId, int boxIdx, int returnPage) {
        return new GuiState(GuiMode.BOX_NUDGE_EDITOR, blockId, returnPage, false, boxIdx, false);
    }

    // DROP_CONFIG (Phase 12.2) — factory removed; mode reserved but not reachable yet.

    // Mutation (returns new instance)

    public GuiState withConfirmDelete(boolean confirm) {
        return new GuiState(mode, editingId, page, confirm, shapeBoxPage, fromCommand);
    }

    public GuiState withShapeBoxPage(int boxPage) {
        return new GuiState(mode, editingId, page, confirmDelete, boxPage, fromCommand);
    }

    public GuiState withPage(int newPage) {
        return new GuiState(mode, editingId, newPage, confirmDelete, shapeBoxPage, fromCommand);
    }

    // Back-stack helper

    /**
     * Arabic Letter Browser — color stored in editingId, page in page.
     * color must be one of: "black", "yellow", "green", "red".
     */
    public static GuiState arabicBrowser(String color, int page) {
        return new GuiState(GuiMode.ARABIC_BROWSER, color != null ? color : "black", page, false, 0, false);
    }

    /** COL9 — hex recolor confirm wizard. editingId = config key, page = packed new RGB, shapeBoxPage = UI page index. */
    public static GuiState hexRecolorConfirm(String configKey, int packedRgb, int uiPage) {
        return new GuiState(GuiMode.HEX_RECOLOR_CONFIRM, configKey, packedRgb, false, uiPage, false);
    }

    /** NF2 — deleter confirm. editingId = block customId to delete. */
    public static GuiState deleterConfirm(String blockId) {
        return new GuiState(GuiMode.DELETER_CONFIRM, blockId, 0, false, 0, false);
    }

    /**
     * Creates a new back-stack initialized with the main menu as the bottom.
     */
    public static Deque<GuiState> newBackStack() {
        return new ArrayDeque<>();
    }
}


