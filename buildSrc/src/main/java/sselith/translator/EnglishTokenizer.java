package sselith.translator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import opennlp.tools.tokenize.SimpleTokenizer;
import opennlp.tools.util.Span;

public final class EnglishTokenizer {

    /** Case to re-apply to the Sselith translation. {@code MIXED} means "leave alone" —
     *  weird casing like {@code iRoN} passes through unchanged. */
    public enum CasePattern { LOWER, TITLE, UPPER, MIXED }

    public record Token(
            String original,
            String normalized,
            boolean isPunctuation,
            boolean isPossessiveMarker,
            CasePattern casePattern,
            /** The source whitespace before this token. The output keeps it as written. */
            String gap
    ) {}

    public static final Pattern FORMAT_PATTERN = Pattern.compile(
            "%(?:\\d+\\$)?[-#+0,(]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAtT%n]");

    public List<Token> tokenize(String text) {
        List<Token> result = new ArrayList<>();
        if (text.isEmpty()) return result;
        Span[] spans = SimpleTokenizer.INSTANCE.tokenizePos(text);
        String[] raw = new String[spans.length];
        String[] gaps = new String[spans.length];
        int prevEnd = 0;
        for (int i = 0; i < spans.length; i++) {
            raw[i] = text.substring(spans[i].getStart(), spans[i].getEnd());
            gaps[i] = text.substring(prevEnd, spans[i].getStart());
            prevEnd = spans[i].getEnd();
        }
        for (int i = 0; i < raw.length; i++) {
            String t = raw[i];
            boolean nextAttached = i + 1 < raw.length && gaps[i + 1].isEmpty();
            boolean attachedToWord = gaps[i].isEmpty() && !result.isEmpty() && endsWithLetter(result.get(result.size() - 1));
            // Possessive `'s` — emit a dedicated possessive-marker token.
            if ("'".equals(t) && attachedToWord && nextAttached && "s".equalsIgnoreCase(raw[i + 1])) {
                result.add(new Token("'s", "'s", false, true, CasePattern.LOWER, ""));
                i++;
                continue;
            }
            // Other contractions — glue `'` + suffix back onto the prior letter-token.
            if ("'".equals(t) && attachedToWord && nextAttached && isContractionSuffix(raw[i + 1])) {
                Token prev = result.get(result.size() - 1);
                String merged = prev.original() + "'" + raw[i + 1];
                result.set(result.size() - 1, new Token(
                        merged, merged.toLowerCase(Locale.ROOT),
                        false, false, prev.casePattern(), prev.gap()));
                i++;
                continue;
            }
            // SimpleTokenizer splits `5,345` at each comma; the runtime tokenizer keeps it as one.
            int groupedEnd = groupedNumberEnd(raw, gaps, i);
            if (groupedEnd > i) {
                String merged = String.join("", java.util.Arrays.copyOfRange(raw, i, groupedEnd + 1));
                result.add(new Token(merged, merged, false, false, CasePattern.LOWER, gaps[i]));
                i = groupedEnd;
                continue;
            }
            // SimpleTokenizer splits `1.5` into three tokens; the runtime tokenizer keeps it as one.
            if (isDigits(t) && i + 2 < raw.length && ".".equals(raw[i + 1]) && isDigits(raw[i + 2])
                    && gaps[i + 1].isEmpty() && gaps[i + 2].isEmpty()) {
                String merged = t + "." + raw[i + 2];
                result.add(new Token(merged, merged, false, false, CasePattern.LOWER, gaps[i]));
                i += 2;
                continue;
            }
            boolean punct = isPunctuation(t);
            CasePattern p = punct ? CasePattern.LOWER : casePatternOf(t);
            result.add(new Token(t, t.toLowerCase(Locale.ROOT), punct, false, p, gaps[i]));
        }
        return result;
    }

    private static boolean endsWithLetter(Token token) {
        if (token.isPunctuation() || token.isPossessiveMarker()) return false;
        String original = token.original();
        return Character.isLetter(original.charAt(original.length() - 1));
    }

    /** Index of the last raw token of a comma-grouped number ({@code 5 , 345 [. 5]}) that starts at
     *  {@code start}, or {@code start} when there is none. "1, 2" and "1,2" are not grouped numbers. */
    private static int groupedNumberEnd(String[] raw, String[] gaps, int start) {
        if (!isDigits(raw[start]) || raw[start].length() > 3) return start;
        int end = start;
        while (end + 2 < raw.length && ",".equals(raw[end + 1]) && gaps[end + 1].isEmpty()
                && gaps[end + 2].isEmpty() && isDigits(raw[end + 2]) && raw[end + 2].length() == 3) {
            end += 2;
        }
        if (end > start && end + 2 < raw.length && ".".equals(raw[end + 1]) && gaps[end + 1].isEmpty()
                && gaps[end + 2].isEmpty() && isDigits(raw[end + 2])) {
            end += 2;
        }
        return end;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isContractionSuffix(String s) {
        if (s == null || s.isEmpty()) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "t", "d", "m", "ll", "re", "ve" -> true;
            default -> false;
        };
    }

    public static boolean isPunctuation(String token) {
        if (token == null || token.isEmpty()) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetterOrDigit(c)) return false;
        }
        return true;
    }

    /**
     * Classify the source token's case so the renderer can re-apply it to
     * the Sselith translation. Rules:
     *
     * <ul>
     *   <li>No letters → {@code LOWER}.</li>
     *   <li>First letter lowercase → {@code LOWER}.</li>
     *   <li>One letter and uppercase → {@code TITLE}.</li>
     *   <li>All letters uppercase and ≥ 2 letters → {@code UPPER}.</li>
     *   <li>First letter uppercase, rest all lowercase → {@code TITLE}.</li>
     *   <li>Anything else (e.g. {@code iRoN}, {@code McGill}) → {@code MIXED}.</li>
     * </ul>
     */
    public static CasePattern casePatternOf(String s) {
        if (s == null || s.isEmpty()) return CasePattern.LOWER;
        int firstLetterIdx = -1;
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                if (firstLetterIdx < 0) firstLetterIdx = i;
                letters++;
            }
        }
        if (firstLetterIdx < 0) return CasePattern.LOWER;
        char first = s.charAt(firstLetterIdx);
        if (!Character.isUpperCase(first)) return CasePattern.LOWER;
        if (letters == 1) return CasePattern.TITLE;
        boolean allUpper = true;
        boolean restAllLower = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetter(c)) continue;
            if (i == firstLetterIdx) continue;
            if (Character.isUpperCase(c)) restAllLower = false;
            else allUpper = false;
        }
        if (allUpper) return CasePattern.UPPER;
        if (restAllLower) return CasePattern.TITLE;
        return CasePattern.MIXED;
    }
}
