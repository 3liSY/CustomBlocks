package com.customblocks.core;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 2 — Resolves bulk-operation scope expressions to lists of matching {@link SlotData}.
 *
 * <p>Supported expressions:
 * <ul>
 *   <li>{@code all} / blank — every block</li>
 *   <li>{@code category:<key>} — all blocks in a category</li>
 *   <li>{@code name:<text>} or {@code name:<prefix>*} — name contains / starts with text</li>
 *   <li>{@code favorite:yes|no} — favorited / unfavorited by the given player</li>
 *   <li>{@code animated:yes|no} — animated / non-animated blocks</li>
 *   <li>{@code locked:yes|no} — locked / unlocked blocks</li>
 *   <li>{@code broken:yes|no} — broken-texture / good-texture blocks</li>
 *   <li>{@code <id>} — single block by exact ID</li>
 *   <li>{@code <id1>,<id2>,...} — comma-separated list of IDs</li>
 * </ul>
 */
public final class BulkScope {

    private BulkScope() {}

    /**
     * Resolve the scope expression and return the matching {@link SlotData} list.
     * Never returns null; returns an empty list for unrecognized expressions.
     *
     * @param expr       scope string from command argument or GUI
     * @param playerUuid player whose favorites are consulted (may be null → ignore favorite filter)
     */
    public static List<SlotData> resolve(String expr, UUID playerUuid) {
        if (expr == null || expr.isBlank() || "all".equalsIgnoreCase(expr.trim())) {
            return new ArrayList<>(SlotManager.allSlots());
        }
        String low = expr.trim().toLowerCase(Locale.ROOT);

        if (low.startsWith("category:")) {
            String key = expr.trim().substring("category:".length()).trim();
            Category cat = CategoryManager.getCategory(key);
            return cat != null ? new ArrayList<>(CategoryManager.getBlocksInCategory(cat.key())) : List.of();
        }
        if (low.startsWith("name:")) {
            String pattern = expr.trim().substring("name:".length()).trim().toLowerCase(Locale.ROOT);
            boolean wildcard = pattern.endsWith("*");
            String term = wildcard ? pattern.substring(0, pattern.length() - 1) : pattern;
            List<SlotData> out = new ArrayList<>();
            for (SlotData d : SlotManager.allSlots()) {
                if (wildcard ? d.displayNameLower.startsWith(term) : d.displayNameLower.contains(term))
                    out.add(d);
            }
            return out;
        }
        if (low.startsWith("favorite:")) {
            boolean want = "yes".equalsIgnoreCase(low.substring("favorite:".length()).trim());
            if (playerUuid == null) return List.of();
            return SlotManager.allSlots().stream()
                    .filter(d -> FavoritesManager.isFavorite(playerUuid, d.customId) == want)
                    .collect(Collectors.toList());
        }
        if (low.startsWith("animated:")) {
            boolean want = "yes".equalsIgnoreCase(low.substring("animated:".length()).trim());
            return SlotManager.allSlots().stream()
                    .filter(d -> d.isAnimated() == want)
                    .collect(Collectors.toList());
        }
        if (low.startsWith("locked:")) {
            boolean want = "yes".equalsIgnoreCase(low.substring("locked:".length()).trim());
            return SlotManager.allSlots().stream()
                    .filter(d -> LockManager.isLocked(d.customId) == want)
                    .collect(Collectors.toList());
        }
        if (low.startsWith("broken:")) {
            boolean want = "yes".equalsIgnoreCase(low.substring("broken:".length()).trim());
            return SlotManager.allSlots().stream()
                    .filter(d -> d.isBroken == want)
                    .collect(Collectors.toList());
        }

        // Comma-separated IDs
        if (expr.contains(",")) {
            List<SlotData> out = new ArrayList<>();
            for (String token : expr.split(",")) {
                String id = token.trim();
                SlotData d = SlotManager.getById(id);
                if (d != null) out.add(d);
            }
            return out;
        }

        // Single exact ID
        SlotData single = SlotManager.getById(expr.trim());
        return single != null ? List.of(single) : List.of();
    }
}
