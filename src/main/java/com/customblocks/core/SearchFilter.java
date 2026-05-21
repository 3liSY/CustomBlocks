package com.customblocks.core;
// Phase 4.1
import java.util.*;
import java.util.function.Predicate;

/**
 * Phase 4.1 — Parses a search query string into composable SlotData filters.
 *
 * Supported tokens:
 *   category:<key>        block is in that category
 *   animated:yes/no       has animMeta
 *   glow:>0 / glow:5      light level comparison (>, <, = operators)
 *   hardness:>1.5         hardness comparison
 *   sound:<type>          soundType contains value
 *   locked:yes/no         is locked (via LockManager)
 *   favorite:yes/no       is favorited by searching player
 *   created:today         lastEditedAt within 24h (or "week", "month")
 *   has:variants          variantTextures non-empty
 *   has:shape             shapeBoxes non-empty
 *   has:faces             faceTextures non-empty
 *   has:glow              lightLevel > 0
 *   has:texture           texture bytes present
 *   color:<name>          dominant color detected (basic heuristic via name/id match)
 *   (bare text)           name or ID contains text (case-insensitive)
 */
public class SearchFilter {

    private final List<Predicate<SlotData>> predicates = new ArrayList<>();
    private final String rawQuery;
    private UUID playerUuid; // for favorite: filter

    private SearchFilter(String rawQuery) {
        this.rawQuery = rawQuery;
    }

    /** Parse a query string into a SearchFilter. playerUuid is needed for favorite: filter. */
    public static SearchFilter parse(String query, UUID playerUuid) {
        SearchFilter sf = new SearchFilter(query);
        sf.playerUuid = playerUuid;
        if (query == null || query.isBlank()) return sf; // no predicates = match all

        String[] tokens = query.trim().split("\\s+");
        for (String token : tokens) {
            int colon = token.indexOf(':');
            if (colon > 0 && colon < token.length() - 1) {
                String key = token.substring(0, colon).toLowerCase();
                String val = token.substring(colon + 1).toLowerCase();
                sf.addFilterToken(key, val);
            } else {
                // bare text — match name or ID
                String lower = token.toLowerCase();
                sf.predicates.add(d ->
                    d.displayName != null && d.displayName.toLowerCase().contains(lower) ||
                    d.customId.toLowerCase().contains(lower));
            }
        }
        return sf;
    }

    private void addFilterToken(String key, String val) {
        switch (key) {
            case "category" -> {
                String catLower = val;
                predicates.add(d ->
                    CategoryManager.getCategoriesForBlock(d.customId).stream()
                        .anyMatch(c -> c.equalsIgnoreCase(catLower)));
            }
            case "animated" -> {
                boolean want = toBool(val);
                predicates.add(d -> (d.animMeta != null && !d.animMeta.isBlank()) == want);
            }
            case "glow", "light" -> {
                Predicate<Integer> cmp = parseNumericInt(val);
                if (cmp != null) predicates.add(d -> cmp.test(d.lightLevel));
            }
            case "hardness" -> {
                Predicate<Float> cmp = parseNumericFloat(val);
                if (cmp != null) predicates.add(d -> cmp.test(d.hardness));
            }
            case "sound" -> {
                String sv = val;
                predicates.add(d -> d.soundType != null && d.soundType.toLowerCase().contains(sv));
            }
            case "locked" -> {
                boolean want = toBool(val);
                predicates.add(d -> LockManager.isLocked(d.customId) == want);
            }
            case "favorite" -> {
                boolean want = toBool(val);
                UUID uuid = playerUuid;
                predicates.add(d -> {
                    if (uuid == null) return false;
                    Set<String> favs = FavoritesManager.validatedSet(uuid);
                    return favs.contains(d.customId) == want;
                });
            }
            case "created" -> {
                long cutoff = parseDateCutoff(val);
                if (cutoff > 0) predicates.add(d -> d.lastEditedAt >= cutoff);
            }
            case "has" -> {
                switch (val) {
                    case "variants" -> predicates.add(d -> d.variantTextures != null && !d.variantTextures.isEmpty());
                    case "shape"    -> predicates.add(d -> d.shapeBoxes != null && !d.shapeBoxes.isEmpty());
                    case "faces"    -> predicates.add(d -> d.faceTextures != null && !d.faceTextures.isEmpty());
                    case "glow"     -> predicates.add(d -> d.lightLevel > 0);
                    case "texture"  -> predicates.add(d -> d.texture != null && d.texture.length > 0);
                    default -> {}
                }
            }
            case "color" -> {
                // Basic color heuristic: match against displayName or customId containing the color name
                // (full pixel-level color detection is a future enhancement)
                String cv = val;
                predicates.add(d ->
                    (d.displayName != null && d.displayName.toLowerCase().contains(cv)) ||
                    (d.customId != null && d.customId.toLowerCase().contains(cv)));
            }
            default -> {}
        }
    }

    /** Returns true if this filter matches the given SlotData. */
    public boolean matches(SlotData d) {
        for (Predicate<SlotData> p : predicates) {
            if (!p.test(d)) return false;
        }
        return true;
    }

    /** Filter a list, returning only matching entries. */
    public List<SlotData> apply(List<SlotData> blocks) {
        if (predicates.isEmpty()) return blocks;
        List<SlotData> out = new ArrayList<>();
        for (SlotData d : blocks) {
            if (matches(d)) out.add(d);
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean toBool(String val) {
        return val.equals("yes") || val.equals("true") || val.equals("1");
    }

    private static long parseDateCutoff(String val) {
        long now = System.currentTimeMillis();
        return switch (val) {
            case "today"  -> now - 86_400_000L;
            case "week"   -> now - 7 * 86_400_000L;
            case "month"  -> now - 30 * 86_400_000L;
            default       -> -1L;
        };
    }

    private static Predicate<Integer> parseNumericInt(String val) {
        try {
            if (val.startsWith(">")) return n -> n > Integer.parseInt(val.substring(1));
            if (val.startsWith("<")) return n -> n < Integer.parseInt(val.substring(1));
            int target = Integer.parseInt(val.startsWith("=") ? val.substring(1) : val);
            return n -> n == target;
        } catch (NumberFormatException e) { return null; }
    }

    private static Predicate<Float> parseNumericFloat(String val) {
        try {
            if (val.startsWith(">")) return n -> n > Float.parseFloat(val.substring(1));
            if (val.startsWith("<")) return n -> n < Float.parseFloat(val.substring(1));
            float target = Float.parseFloat(val.startsWith("=") ? val.substring(1) : val);
            return n -> n == target;
        } catch (NumberFormatException e) { return null; }
    }
}
