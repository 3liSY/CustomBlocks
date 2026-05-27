package com.customblocks.gui;

/**
 * All GUI modes / screens.
 */
public enum GuiMode {
    MAIN,
    PICKER,
    PICKER_BROKEN,
    EDITOR,
    FACE_EDITOR,
    FACE_CHANGE_SELECT,
    FACE_CHANGE_PICKER,
    SHAPE_EDITOR,
    MAINTENANCE_MENU,
    HELP_MENU,
    WELCOME_MENU,
    TOOLS_GUI,
    PROPERTIES_MENU,
    SOUND_MENU,
    ANIM_GUI,
    TAB_ICON_MENU,
    RESOURCE_CENTER,
    BULK_DELETE,
    SEARCH_PICKER,
    MAGIC_ITEMS,
    UNDO_PICKER,
    CONFIG_WARNING,
    CONFIG_GUI,
    HELP_CATEGORY,
    ANIM_CONFIRM_ABANDON,
    BG_STUDIO,
    UNCATEGORIZED_PICKER,
    ASSIGNMENT_DECISION,
    CATEGORY_PICKER,
    CATEGORY_BROWSER,
    CATEGORY_DETAIL,
    CATEGORY_CONTROLLER,
    CATEGORY_EDITOR,
    SUBCATEGORY_CONTROLLER,
    IMPORT_CONFLICT,
    DELETE_CATEGORY_MENU,
    MERGE_CATEGORY_PICKER_TARGET,
    BULK_ASSIGN_PICKER,
    SORT_BLOCKS_MENU,
    CATEGORY_STATS,
    CATEGORY_BLOCK_CONTEXT,
    CATEGORY_ICON_PICKER,
    BULK_RECOLOR_WIZARD,
    BULK_RECOLOR_CONFIRM,
    COLOR_FILL_MODE,
    RECOVER_GUI,
    FEATURE_MENU,
    COLOR_PICKER,
    /** K2 — usage dashboard (top placements, activity). */
    STATS_GUI,
    /** H4 — variant texture manager for a single block. */
    VARIANT_GUI,
    /** G1 — color studio: tint/brightness/grayscale/invert/mirror/rotate on main texture. */
    COLOR_STUDIO,
    /** G2 — color palette generator: 16-color tint set from base texture. */
    PALETTE_GENERATOR,
    /** J2 — AI smart suggest: 18 curated one-click texture presets. */
    AI_SUGGEST_GUI,
    /** L3 — Cloud Vault market browser: browse + import shared blocks. */
    MARKET_GUI,
    /** Phase 2 — Bulk operations hub: 12 operation tiles, shared selection display. */
    BULK_HUB,
    /** Phase 2 — Shared block picker for any bulk operation. editingId = operation key. */
    BULK_OP_PICKER,
    /** 5.25 — Voice mode picker: one legendary item per voice style, active mode glows. */
    VOICE_PICKER,
    /** Feature Menu shortcut — paginated list of a player's favorited blocks. */
    FAVORITES_GUI,
    /** Feature Menu shortcut — up to 3 recently-edited blocks, no pagination. */
    RECENT_GUI,
    /** Phase 9.2 — Safety Center: snapshot, panic, undo, backup summary. */
    SAFETY_CENTER,
    /** Phase 9.3 — History GUI: paginated mutation log with filter. */
    HISTORY_GUI,
    /** Phase 10.1 — Script GUI: manage saved scripts (formerly macros). */
    SCRIPT_GUI,
    /** Phase 10.1 — Script summary shown after a script completes (5-second countdown). */
    SCRIPT_SUMMARY,
    /** Phase 10.3 — Cache & Server Health Dashboard (5 tabs). */
    CACHE_DASHBOARD,
    /** Phase 10.4 — Audit GUI: Royal Directive compliance scanner results. */
    AUDIT_GUI,
    /** Phase 11.1 — AI description prompt: player enters block description, then picks variation. */
    AI_GEN,
    /** Phase 11.2 — Custom Color Studio: hex picker + HSB sliders for /cb customcolor GUI. */
    CUSTOM_COLOR_STUDIO,
    /** Phase 12.1 — Achievements GUI: shows all achievements, locked/unlocked with progress. */
    ACHIEVEMENTS_GUI,
    /** V4-43 — Snapshots GUI: paginated list of server snapshots with restore support. */
    SNAPSHOTS_GUI,
    /** V4-18 — Deleted Blocks GUI: browse and restore the trash bin. */
    DELETED_BLOCKS_GUI,
    /** V4-13 — Box Nudge Editor: fine-tune a single shape box with + / − buttons. */
    BOX_NUDGE_EDITOR,
    /** Phase 3.5 — Color Triangle confirm dialog: preview color + block before applying. */
    RECOLOR_CONFIRM,
    /** Color Tools Hub — unified hub for color tool management, rename, fill mode. */
    COLORS_HUB,
    /** Rename Tool Picker — shows custom triangles/squares in inventory for renaming. */
    RENAME_TOOL_PICKER
}


