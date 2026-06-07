package sselith.translator;

import java.util.Set;

public final class MorphologyEngine {

    private MorphologyEngine() {}

    public static String attachSuffix(String stem, String suffix, Set<Character> vowels) {
        String s = stripLeadingHyphen(suffix);
        if (stem == null || stem.isEmpty() || s.isEmpty()) {
            return (stem == null ? "" : stem) + s;
        }
        char stemEnd = Character.toLowerCase(stem.charAt(stem.length() - 1));
        char sufStart = Character.toLowerCase(s.charAt(0));
        if (stemEnd == sufStart) {
            return stem + s.substring(1);
        }
        return stem + s;
    }

    public static String attachPrefix(String stem, String prefix, Set<Character> vowels) {
        String p = stripTrailingHyphen(prefix);
        if (stem == null || stem.isEmpty() || p.isEmpty()) {
            return p + (stem == null ? "" : stem);
        }
        char prefEnd = Character.toLowerCase(p.charAt(p.length() - 1));
        char stemStart = Character.toLowerCase(stem.charAt(0));
        if (prefEnd == stemStart) {
            return p + stem.substring(1);
        }
        return p + stem;
    }

    private static String stripLeadingHyphen(String s) {
        if (s == null) return "";
        return s.startsWith("-") ? s.substring(1) : s;
    }

    private static String stripTrailingHyphen(String s) {
        if (s == null) return "";
        return s.endsWith("-") ? s.substring(0, s.length() - 1) : s;
    }
}
