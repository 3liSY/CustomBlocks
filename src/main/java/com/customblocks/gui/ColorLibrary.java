package com.customblocks.gui;

import java.util.*;

/**
 * Phase 3.1 — Named color library with 29 preset colors organized in sections.
 * <p>
 * Provides name→hex resolution for commands and GUI color pickers.
 * Resolution order: exact name (case-insensitive) → alias → hex parse (#RRGGBB) → null.
 */
public final class ColorLibrary {

    private ColorLibrary() {}

    /** A single named color entry. */
    public record LibColor(String name, String hex) {
        /** The RGB integer (0xRRGGBB) derived from {@link #hex}. */
        public int rgb() {
            String s = hex.startsWith("#") ? hex.substring(1) : hex;
            return Integer.parseInt(s, 16);
        }
        /** Short MC-formatted display name for GUI lore. */
        public String rgbLabel() {
            int v = rgb();
            return "§8RGB: §7" + ((v >> 16) & 0xFF) + ", " + ((v >> 8) & 0xFF) + ", " + (v & 0xFF);
        }
    }

    // ── Color sections ───────────────────────────────────────────────────────

    /** Row 2 — basic vibrant colors (9 colors). */
    public static final List<LibColor> BASICS = List.of(
            new LibColor("Red",      "#EE3333"),
            new LibColor("Orange",   "#FF8800"),
            new LibColor("Yellow",   "#FFDD00"),
            new LibColor("Lime",     "#44DD00"),
            new LibColor("Green",    "#1A8C00"),
            new LibColor("Cyan",     "#00BBCC"),
            new LibColor("Blue",     "#1155CC"),
            new LibColor("Purple",   "#7700CC"),
            new LibColor("Magenta",  "#EE00BB")
    );

    /** Row 3 — neutrals + warm tones (9 colors). */
    public static final List<LibColor> NEUTRALS = List.of(
            new LibColor("Pink",       "#FF88BB"),
            new LibColor("White",      "#F5F5F5"),
            new LibColor("Light Gray", "#C8C8C8"),
            new LibColor("Gray",       "#808080"),
            new LibColor("Dark Gray",  "#404040"),
            new LibColor("Black",      "#111111"),
            new LibColor("Brown",      "#8B4513"),
            new LibColor("Crimson",    "#CC1133"),
            new LibColor("Maroon",     "#800000"),
            new LibColor("Gold",       "#FFD700")
    );

    /** Row 4 — rich & pastel tones (9 colors). */
    public static final List<LibColor> RICH = List.of(
            new LibColor("Forest",    "#1E6B1E"),
            new LibColor("Navy",      "#003377"),
            new LibColor("Indigo",    "#4B0082"),
            new LibColor("Coral",     "#FF5E47"),
            new LibColor("Baby Blue", "#88BBEE"),
            new LibColor("Lavender",  "#BB88DD"),
            new LibColor("Mint",      "#AAEEBB"),
            new LibColor("Peach",     "#FFCC99"),
            new LibColor("Rose",      "#FF8899")
    );

    /** Row 5 slot 0 — extra color. */
    public static final LibColor BUTTER = new LibColor("Butter", "#FFFAAA");

    /** Flat list of all library colors in display order. */
    public static final List<LibColor> ALL;

    /** Case-insensitive name → LibColor lookup map (includes aliases). */
    private static final Map<String, LibColor> BY_NAME;

    static {
        List<LibColor> all = new ArrayList<>();
        all.addAll(BASICS);
        all.addAll(NEUTRALS);
        all.addAll(RICH);
        all.add(BUTTER);
        ALL = Collections.unmodifiableList(all);

        Map<String, LibColor> map = new LinkedHashMap<>();
        for (LibColor c : ALL) {
            map.put(c.name().toLowerCase(Locale.ROOT), c);
        }
        // Aliases (common short names + variants)
        alias(map, "light gray", "light_gray", "lightgray", "silver");
        alias(map, "dark gray", "dark_gray", "darkgray", "charcoal");
        alias(map, "baby blue", "baby_blue", "sky", "skyblue", "sky blue");
        alias(map, "black",  "obsidian", "noir");
        alias(map, "white",  "snow", "ivory");
        alias(map, "red",    "scarlet", "ruby");
        alias(map, "maroon", "dark red", "darkred", "wine", "burgundy");
        alias(map, "green",  "emerald", "olive");
        alias(map, "blue",   "sapphire", "cobalt");
        alias(map, "purple", "violet", "amethyst");
        alias(map, "gold",   "yellow-gold", "auric");
        alias(map, "brown",  "wood", "dirt", "earth");
        alias(map, "coral",  "salmon", "terracotta");
        alias(map, "rose",   "blush");
        alias(map, "mint",   "seafoam");
        alias(map, "forest", "dark green", "darkgreen", "jungle");
        alias(map, "navy",   "midnight", "darkblue", "dark blue");
        alias(map, "indigo", "deep purple", "deeppurple");
        alias(map, "peach",  "apricot");
        BY_NAME = Collections.unmodifiableMap(map);
    }

    private static void alias(Map<String, LibColor> map, String canonical, String... aliases) {
        LibColor target = map.get(canonical);
        if (target == null) return;
        for (String alias : aliases) map.putIfAbsent(alias.toLowerCase(Locale.ROOT), target);
    }

    // ── Resolution ──────────────────────────────────────────────────────────

    /**
     * Resolve a color input to a hex string.
     * Order: exact name → alias → hex parse.
     *
     * @param input color name (e.g. "red", "baby blue") or hex (#RRGGBB)
     * @return "#RRGGBB" on success, null on failure
     */
    public static String resolve(String input) {
        if (input == null || input.isBlank()) return null;
        String low = input.trim().toLowerCase(Locale.ROOT);
        LibColor found = BY_NAME.get(low);
        if (found != null) return found.hex();
        // Try hex parse
        String s = low.startsWith("#") ? low.substring(1) : low;
        if (s.length() == 6) {
            try { Integer.parseInt(s, 16); return "#" + s.toUpperCase(Locale.ROOT); }
            catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * Suggest similar color names when resolution fails (for error messages).
     * Returns up to 3 closest names.
     */
    public static List<String> suggest(String input) {
        if (input == null) return List.of();
        String low = input.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (LibColor c : ALL) {
            String name = c.name().toLowerCase(Locale.ROOT);
            if (name.contains(low) || low.contains(name.substring(0, Math.min(3, name.length())))) {
                out.add(c.name());
                if (out.size() >= 3) break;
            }
        }
        return out;
    }
}
