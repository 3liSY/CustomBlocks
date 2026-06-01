package com.customblocks.arabic;

import java.util.*;

/**
 * Static mappings between Arabic letter names (matching the PNG file-name prefixes)
 * and their Unicode code points.
 *
 * Names match the letter folders exactly, e.g. "ba" matches ba_black.png.
 */
public final class ArabicLetterMap {

    /** letter-name -> primary Unicode character for that letter */
    public static final Map<String, Character> LETTER_TO_CHAR;
    /** Unicode character -> letter-name (reverse of above) */
    public static final Map<Character, String> CHAR_TO_LETTER;

    /**
     * Letters that do NOT connect to the left (west) neighbour.
     * These are the Arabic non-joining letters: alef variants, dal, thal, ra, zay,
     * waw variants, and standalone hamza.
     */
    public static final Set<String> NON_JOINING;

    static {
        Map<String, Character> l2c = new LinkedHashMap<>();

        l2c.put("hamza",            'ء'); // ء
        l2c.put("alef_madda",       'آ'); // آ
        l2c.put("alef_hamza_above", 'أ'); // أ
        l2c.put("waw_hamza",        'ؤ'); // ؤ
        l2c.put("alef_hamza_below", 'إ'); // إ
        l2c.put("ya_hamza",         'ئ'); // ئ
        l2c.put("alef",             'ا'); // ا
        l2c.put("ba",               'ب'); // ب
        l2c.put("ta_marbuta",       'ة'); // ة
        l2c.put("ta",               'ت'); // ت
        l2c.put("tha",              'ث'); // ث
        l2c.put("jeem",             'ج'); // ج
        l2c.put("ha",               'ح'); // ح
        l2c.put("kha",              'خ'); // خ
        l2c.put("dal",              'د'); // د
        l2c.put("thal",             'ذ'); // ذ
        l2c.put("ra",               'ر'); // ر
        l2c.put("zay",              'ز'); // ز
        l2c.put("seen",             'س'); // س
        l2c.put("sheen",            'ش'); // ش
        l2c.put("sad",              'ص'); // ص
        l2c.put("dad",              'ض'); // ض
        l2c.put("ta2",              'ط'); // ط
        l2c.put("tha2",             'ظ'); // ظ
        l2c.put("ain",              'ع'); // ع
        l2c.put("ghain",            'غ'); // غ
        l2c.put("fa",               'ف'); // ف
        l2c.put("qaf",              'ق'); // ق
        l2c.put("kaf",              'ك'); // ك
        l2c.put("lam",              'ل'); // ل
        l2c.put("meem",             'م'); // م
        l2c.put("noon",             'ن'); // ن
        l2c.put("ha2",              'ه'); // ه
        l2c.put("waw",              'و'); // و
        l2c.put("alef_maqsura",     'ى'); // ى
        l2c.put("ya",               'ي'); // ي
        // Arabic-Indic numerals  ٠١٢٣٤٥٦٧٨٩
        l2c.put("num_0",            '٠');
        l2c.put("num_1",            '١');
        l2c.put("num_2",            '٢');
        l2c.put("num_3",            '٣');
        l2c.put("num_4",            '٤');
        l2c.put("num_5",            '٥');
        l2c.put("num_6",            '٦');
        l2c.put("num_7",            '٧');
        l2c.put("num_8",            '٨');
        l2c.put("num_9",            '٩');

        Map<Character, String> c2l = new HashMap<>();
        l2c.forEach((name, ch) -> c2l.put(ch, name));

        LETTER_TO_CHAR = Collections.unmodifiableMap(l2c);
        CHAR_TO_LETTER = Collections.unmodifiableMap(c2l);

        NON_JOINING = Set.of(
            "hamza", "alef", "alef_madda", "alef_hamza_above", "alef_hamza_below",
            "alef_maqsura", "dal", "thal", "ra", "zay", "waw", "waw_hamza"
        );
    }

    /** Returns the letter-name for an Arabic Unicode character, or null if not mapped. */
    public static String letterNameFor(char ch) {
        return CHAR_TO_LETTER.get(ch);
    }

    /** Returns the Unicode character for a letter-name, or null if not found. */
    public static Character charFor(String letterName) {
        return LETTER_TO_CHAR.get(letterName);
    }

    public static boolean isNonJoining(String letterName) {
        return NON_JOINING.contains(letterName);
    }

    /**
     * Returns a human-readable title-case display name from a letter-name.
     * "alef_hamza_above" -> "Alef Hamza Above"
     */
    public static String displayName(String letterName) {
        if (letterName == null) return "Unknown";
        StringBuilder sb = new StringBuilder();
        for (String word : letterName.split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0)));
            sb.append(word.substring(1));
        }
        return sb.toString();
    }

    private ArabicLetterMap() {}
}
