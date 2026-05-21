package com.customblocks.gui;

/**
 * 1.27 — Per-player block-list sort order.
 * <p>
 * The default ({@link #NAME_ASC}) matches the pre-1.27 alphabetical behaviour.
 */
public enum SortMode {
    NAME_ASC("A→Z Name", "§aA→Z §7Name", "Alphabetical ascending (default)", "§aA"),
    NAME_DESC("Z→A Name", "§aZ→A §7Name", "Alphabetical descending", "§cZ"),
    INDEX_ASC("0→9 Slot Index", "§a0→9 §7Slot Index", "Creation order — oldest first", "§a0"),
    INDEX_DESC("9→0 Slot Index", "§a9→0 §7Slot Index", "Most recently added first", "§c9"),
    RECENTLY_EDITED("Recently Edited", "§6★ §7Recently Edited", "Most recently changed block at top", "§6★"),
    ANIMATED_FIRST("Animated First", "§b▶ §7Animated First", "Animated GIF blocks before static", "§b▶"),
    BROKEN_FIRST("Broken First", "§c⚠ §7Broken First", "Blocks with broken/missing textures at top", "§c⚠"),
    BY_CATEGORY("By Category", "§d📁 §7By Category", "Group blocks by category, alphabetical within", "§d📁"),
    BY_SIZE("By Size", "§e📦 §7By Size", "Largest texture (most RAM/disk) first", "§e📦"),
    LOCKED_FIRST("Locked First", "§7🔒 §7Locked First", "Locked/protected blocks at top", "§7🔒"),
    BY_GLOW("By Glow", "§e💡 §7By Glow", "Highest glow level first", "§e💡"),
    BY_SOUND("By Sound", "§f🔊 §7By Sound", "Grouped by sound type", "§f🔊");

    /** Short label for sort button / picker title indicator. */
    public final String label;
    /** Formatted label for sort menu items. */
    public final String menuLabel;
    /** Tooltip description in sort menu. */
    public final String description;
    /** 2-character indicator shown in the picker title bar. */
    public final String indicator;

    SortMode(String label, String menuLabel, String description, String indicator) {
        this.label = label;
        this.menuLabel = menuLabel;
        this.description = description;
        this.indicator = indicator;
    }
}
