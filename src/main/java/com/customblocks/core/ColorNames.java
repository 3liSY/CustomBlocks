package com.customblocks.core;

import java.util.*;

/**
 * Shared color vocabulary for the entire CustomBlocks color system.
 *
 * Provides:
 *   - 16 canonical color family names (FAMILY_NAMES)
 *   - 40+ alias → canonical family mappings (ALIAS_MAP)
 *   - resolveFamily(word) — convert any color word to a canonical family
 *
 * Used by:
 *   - ColorDetection (pixel-analysis fallback)
 *   - ColorSquareItem / ColorTriangleItem (resolveTargetId)
 *   - Command parsing (/cb recolor <color> <id>)
 *   - Search filters (color:red)
 *
 * Items 1.1 + 1.3 of the V3 master plan.
 */
public final class ColorNames {

    /** The 16 canonical color family names. Lower-case, singular. */
    public static final List<String> FAMILY_NAMES = List.of(
        "black", "white", "red", "orange", "yellow",
        "green", "lime", "blue", "cyan", "purple",
        "magenta", "pink", "brown", "gray", "dark", "light"
    );

    /**
     * Alias → canonical family.
     * Keys are lower-case; values are entries in FAMILY_NAMES.
     *
     * The alias list covers all 40+ entries from the plan plus common natural-language
     * variants a player might type.
     */
    public static final Map<String, String> ALIAS_MAP;

    static {
        Map<String, String> m = new LinkedHashMap<>();

        // ── black ────────────────────────────────────────────────────────────
        for (String a : new String[]{"charcoal","coal","ebony","onyx","obsidian","jet","noir","pitch"})
            m.put(a, "black");

        // ── white ────────────────────────────────────────────────────────────
        for (String a : new String[]{"snow","ivory","pearl","cream","chalk","marble_white","alabaster"})
            m.put(a, "white");

        // ── red ──────────────────────────────────────────────────────────────
        for (String a : new String[]{"crimson","scarlet","ruby","blood","cherry","carmine","garnet","vermilion"})
            m.put(a, "red");

        // ── orange ───────────────────────────────────────────────────────────
        // "gold" maps to orange (warm), not yellow — per plan specification
        for (String a : new String[]{"amber","gold","honey","saffron","tangerine","apricot","copper","rust"})
            m.put(a, "orange");

        // ── yellow ───────────────────────────────────────────────────────────
        for (String a : new String[]{"lemon","butter","canary","sunflower","mustard","golden_yellow"})
            m.put(a, "yellow");

        // ── green ────────────────────────────────────────────────────────────
        for (String a : new String[]{"forest","emerald","jade","olive","moss","fern","hunter","sage","jungle"})
            m.put(a, "green");

        // ── lime ─────────────────────────────────────────────────────────────
        for (String a : new String[]{"chartreuse","neon","bright_green","spring"})
            m.put(a, "lime");

        // ── blue ─────────────────────────────────────────────────────────────
        for (String a : new String[]{"navy","sapphire","azure","sky","cobalt","cerulean","royal_blue","midnight"})
            m.put(a, "blue");

        // ── cyan ─────────────────────────────────────────────────────────────
        for (String a : new String[]{"teal","aqua","turquoise","aquamarine","seafoam"})
            m.put(a, "cyan");

        // ── purple ───────────────────────────────────────────────────────────
        for (String a : new String[]{"violet","indigo","plum","grape","lavender_dark","amethyst","eggplant"})
            m.put(a, "purple");

        // ── magenta ──────────────────────────────────────────────────────────
        for (String a : new String[]{"fuchsia","hot_pink","neon_pink","fuchsia_pink"})
            m.put(a, "magenta");

        // ── pink ─────────────────────────────────────────────────────────────
        for (String a : new String[]{"rose","salmon","blush","coral","lavender","peach","baby_pink"})
            m.put(a, "pink");

        // ── brown ────────────────────────────────────────────────────────────
        for (String a : new String[]{"beige","tan","chocolate","coffee","mocha","sienna","umber","mahogany","caramel"})
            m.put(a, "brown");

        // ── gray ─────────────────────────────────────────────────────────────
        for (String a : new String[]{"grey","silver","ash","slate","stone","concrete","pewter"})
            m.put(a, "gray");

        // ── dark (meta-family — block IDs like "dark_stone") ─────────────────
        for (String a : new String[]{"dark_grey","dark_gray","dark_blue","dark_green","shadow","dim"})
            m.put(a, "dark");

        // ── light (meta-family) ───────────────────────────────────────────────
        for (String a : new String[]{"light_grey","light_gray","light_blue","light_green","pastel","pale"})
            m.put(a, "light");

        ALIAS_MAP = Collections.unmodifiableMap(m);
    }

    /**
     * Resolve any color word (user input, block ID segment, pixel analysis result)
     * to a canonical family name.
     *
     * Resolution order:
     *   1. Exact match in FAMILY_NAMES (e.g. "red" → "red")
     *   2. Alias match in ALIAS_MAP    (e.g. "crimson" → "red")
     *   3. null — let the caller decide what to do (e.g. try pixel analysis)
     *
     * @param word raw color word — case-insensitive, may be null
     * @return canonical family name, or {@code null} if no match found
     */
    public static String resolveFamily(String word) {
        if (word == null || word.isBlank()) return null;
        String lower = word.toLowerCase(Locale.ROOT).trim();
        if (FAMILY_NAMES.contains(lower)) return lower;
        return ALIAS_MAP.get(lower);
    }

    private ColorNames() {}
}
