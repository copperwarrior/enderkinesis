package sselith.translator;

import java.util.ArrayList;
import java.util.List;

/** Finds `[sketch...]` tags and `[page]` directives. Keep these rules equal to SketchTag.kt and TomeLore.kt in common. */
final class SketchTagSpans {

    private static final String OPEN = "[sketch";
    private static final String PAGE_BREAK = "[page]";

    private SketchTagSpans() {}

    /** Each tag or directive as {start, end}, end exclusive. */
    static List<int[]> find(String text) {
        List<int[]> found = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int start = text.indexOf(OPEN, from);
            if (start < 0) break;
            int end = scan(text, start);
            if (end < 0) {
                from = start + OPEN.length();
            } else {
                found.add(new int[] {start, end});
                from = end;
            }
        }
        for (int at = text.indexOf(PAGE_BREAK); at >= 0; at = text.indexOf(PAGE_BREAK, at + PAGE_BREAK.length())) {
            found.add(new int[] {at, at + PAGE_BREAK.length()});
        }
        return found;
    }

    private static int scan(String text, int start) {
        int i = start + OPEN.length();
        if (text.startsWith("=", i)) {
            i++;
        } else if (text.startsWith(":", i)) {
            int eq = text.indexOf('=', i);
            if (eq < 0) return -1;
            i = eq + 1;
        } else {
            return -1;
        }
        i = valueEnd(text, i);
        if (i < 0) return -1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == ']') return i + 1;
            int tokenEnd = i;
            while (tokenEnd < text.length() && !Character.isWhitespace(text.charAt(tokenEnd))
                    && text.charAt(tokenEnd) != ']') {
                tokenEnd++;
            }
            if (text.substring(i, tokenEnd).indexOf('=') <= 0) return -1;
            i = tokenEnd;
        }
        return -1;
    }

    private static int valueEnd(String text, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                if (depth == 0) return c == ']' ? i : -1;
                depth--;
            } else if (depth == 0 && Character.isWhitespace(c)) {
                return i;
            }
        }
        return -1;
    }
}
