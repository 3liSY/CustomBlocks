package com.customblocks.core;

import java.util.*;

/**
 * In-memory inverted search index over all registered custom blocks.
 *
 * Rebuilt lazily on the next {@link #search} call after any mutation.
 * {@link #invalidate()} is called by SlotManager on every add/update/remove.
 */
public final class SearchIndex {

    // blockId → normalized, pre-computed searchable text
    private static volatile Map<String, String> textCache = Map.of();
    // lowercase token → set of blockIds that contain that token
    private static volatile Map<String, Set<String>> tokenMap = Map.of();

    private static volatile boolean dirty = true;

    private SearchIndex() {}

    /** Flag the index as stale. Cheap — only sets a volatile boolean. */
    public static void invalidate() {
        dirty = true;
    }

    /**
     * Rebuild the index from current SlotManager + CategoryManager state.
     * Called automatically by {@link #search} when dirty.
     */
    public static synchronized void rebuild() {
        Map<String, String> newTexts  = new LinkedHashMap<>();
        Map<String, Set<String>> newTokens = new HashMap<>();

        for (SlotData d : SlotManager.sortedSlots()) {
            StringBuilder sb = new StringBuilder();
            sb.append(d.customId.toLowerCase()).append(' ');
            if (d.displayName != null) sb.append(d.displayName.toLowerCase()).append(' ');
            if (d.soundType != null)   sb.append(d.soundType.toLowerCase()).append(' ');

            for (String catKey : CategoryManager.getCategoriesForBlock(d.customId)) {
                sb.append(catKey.toLowerCase()).append(' ');
                Category cat = CategoryManager.getCategory(catKey);
                if (cat != null) {
                    appendLower(sb, cat.displayName());
                    appendLower(sb, cat.badge());
                    appendLower(sb, cat.lorePrefix());
                    appendLower(sb, cat.description());
                }
            }

            String text = sb.toString();
            newTexts.put(d.customId, text);

            for (String token : text.split("[\\s_\\-]+")) {
                if (!token.isEmpty()) {
                    newTokens.computeIfAbsent(token, k -> new HashSet<>()).add(d.customId);
                }
            }
        }

        textCache = Collections.unmodifiableMap(newTexts);
        tokenMap  = Collections.unmodifiableMap(newTokens);
        dirty     = false;
    }

    private static void appendLower(StringBuilder sb, String s) {
        if (s != null && !s.isEmpty()) sb.append(s.toLowerCase()).append(' ');
    }

    /**
     * Search blocks matching {@code rawQuery} (case-insensitive substring).
     * Rebuilds lazily if dirty. Returns results in sorted display order.
     */
    public static List<SlotData> search(String rawQuery) {
        if (dirty) rebuild();
        String q = rawQuery == null ? "" : rawQuery.strip().toLowerCase();
        if (q.isEmpty()) return SlotManager.sortedSlots();

        Map<String, String> snap = textCache;
        return SlotManager.sortedSlots().stream()
                .filter(d -> {
                    String text = snap.get(d.customId);
                    return text != null && text.contains(q);
                })
                .toList();
    }

}
