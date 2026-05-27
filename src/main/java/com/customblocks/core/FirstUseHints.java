package com.customblocks.core;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V4-31 — First-use hints.
 * Fires a one-time chat tip the first time a player encounters a feature.
 * Never repeats. Stored in memory only (resets on server restart — intentional).
 */
public final class FirstUseHints {

    private static final Map<UUID, Set<String>> SEEN = new ConcurrentHashMap<>();

    /**
     * Returns a hint string if this is the first time the player has triggered
     * the given key; returns null on subsequent calls. Thread-safe.
     */
    public static String hint(UUID player, String key) {
        Set<String> seen = SEEN.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet());
        if (!seen.add(key)) return null;
        return HINTS.get(key);
    }

    /** Returns true if the player has already seen this hint. */
    public static boolean hasSeen(UUID player, String key) {
        Set<String> seen = SEEN.get(player);
        return seen != null && seen.contains(key);
    }

    private static final Map<String, String> HINTS = Map.ofEntries(
        Map.entry("open_block_list",
            "§8Tip: §7Right-click a block in the list to pre-check it for §fBulk Delete§7."),
        Map.entry("hold_square",
            "§8Tip: §7Hold a Color Square and run §f/cb square§7 to browse all available colors."),
        Map.entry("hold_triangle",
            "§8Tip: §7Switch Triangle fill mode with §f/cb trianglemode edge §7or §f/cb trianglemode full§7."),
        Map.entry("open_editor",
            "§8Tip: §7Run §f/cb help§7 for the full interactive command guide."),
        Map.entry("first_delete",
            "§8Tip: §7Deleted blocks go to the Trash bin — restore them with §f/cb deletedblocks§7."),
        Map.entry("first_undo",
            "§8Tip: §7You can undo multiple steps. Run §f/cb undogui§7 to pick exactly which step to restore."),
        Map.entry("first_retexture",
            "§8Tip: §7After uploading a texture, use §f/cb setface <id> <url>§7 to set a different image per face."),
        Map.entry("open_properties",
            "§8Tip: §7The Lumina Brush (right-click a block) is faster for adjusting light, hardness, and sound."),
        Map.entry("open_shape_editor",
            "§8Tip: §7The Amethyst Chisel (right-click a block) opens the Shape Editor instantly."),
        Map.entry("first_snapshot",
            "§8Tip: §7Snapshots are auto-saved before dangerous operations. Browse them with §f/cb snapshots§7."),
        Map.entry("color_triangle",
            "§e§lColor Triangle: §rRight-click a custom block to create a recolored background copy."),
        Map.entry("color_square",
            "§e§lColor Square: §rRight-click a custom block to instantly swap to an existing color variant.")
    );

    private FirstUseHints() {}
}
