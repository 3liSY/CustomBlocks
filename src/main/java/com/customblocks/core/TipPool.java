package com.customblocks.core;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Phase 5.8 — Contextual, rotating tips shown in GUI footers.
 * <p>
 * Maintains a pool of 20 tips and ensures each player sees a different tip
 * on each GUI open (never the same one twice in a row).
 */
public final class TipPool {

    private TipPool() {}

    private static final List<String> TIPS = List.of(
        "§8Tip: §7Shift-click a category block in the detail view to give yourself one instantly",
        "§8Tip: §7Right-click a sound option to preview it before applying",
        "§8Tip: §7Use §f/cb bulkgui §7for batch operations on many blocks at once",
        "§8Tip: §7Type color names like §fred§7, §fcoral§7, or §fnavy §7instead of hex codes",
        "§8Tip: §7§f/cb recent §7shows your last 10 edited blocks",
        "§8Tip: §7§f/cb find <id> §7highlights placed blocks in the world with particles",
        "§8Tip: §7Press §fF §7in a block list to quickly toggle favorite status",
        "§8Tip: §7Use §f/cb template save §7to save a block's properties as a reusable preset",
        "§8Tip: §7§f/cb search animated:true §7finds all animated blocks",
        "§8Tip: §7§f/cb help scopes §7explains the category:key and name:* filter syntax",
        "§8Tip: §7Dress overlays (cracked, mossy, weathered) are in the Block Editor",
        "§8Tip: §7The Gradient Generator creates smooth color transitions between two blocks",
        "§8Tip: §7§f/cb macro record §7lets you record and replay a sequence of block commands",
        "§8Tip: §7§f/cb undo §7can restore the last deleted or modified block",
        "§8Tip: §7Right-click a block in any picker to open the category assignment menu",
        "§8Tip: §7§f/cb note <id> <text> §7attaches an admin note to any block",
        "§8Tip: §7The Shape Editor supports presets like slab, stairs, and wall",
        "§8Tip: §7§fglow:>5 §7in the search bar finds blocks that emit strong light",
        "§8Tip: §7GIF textures are auto-detected and animated on first upload",
        "§8Tip: §7§f/cb exportblock <id> §7generates a share code anyone can import"
    );

    /** Per-player index of the last tip shown. */
    private static final ConcurrentHashMap<UUID, Integer> LAST = new ConcurrentHashMap<>();

    /**
     * Returns the next tip for this player — guaranteed to differ from the
     * last one they saw.
     */
    public static String next(UUID playerUuid) {
        int size = TIPS.size();
        int last  = LAST.getOrDefault(playerUuid, -1);
        int idx;
        if (size <= 1) {
            idx = 0;
        } else {
            do {
                idx = ThreadLocalRandom.current().nextInt(size);
            } while (idx == last);
        }
        LAST.put(playerUuid, idx);
        return TIPS.get(idx);
    }
}
