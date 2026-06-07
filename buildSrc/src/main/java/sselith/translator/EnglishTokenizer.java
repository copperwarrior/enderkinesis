package sselith.translator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import opennlp.tools.tokenize.SimpleTokenizer;

public final class EnglishTokenizer {

    /** Case to re-apply to the Sselith translation. {@code MIXED} means "leave alone" —
     *  weird casing like {@code iRoN} passes through unchanged. */
    public enum CasePattern { LOWER, TITLE, UPPER, MIXED }

    public record Token(
            String original,
            String normalized,
            boolean isPunctuation,
            boolean isPossessiveMarker,
            CasePattern casePattern
    ) {}

    public static final Pattern FORMAT_PATTERN = Pattern.compile(
            "%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAtT%n]");

    public List<Token> tokenize(String text) {
        List<Token> result = new ArrayList<>();
        if (text.isEmpty()) return result;
        String[] raw = SimpleTokenizer.INSTANCE.tokenize(text);
        for (int i = 0; i < raw.length; i++) {
            String t = raw[i];
            // Possessive `'s` — emit a dedicated possessive-marker token.
            if ("'".equals(t) && i + 1 < raw.length && "s".equalsIgnoreCase(raw[i + 1])) {
                result.add(new Token("'s", "'s", false, true, CasePattern.LOWER));
                i++;
                continue;
            }
            // Other contractions — glue `'` + suffix back onto the prior
            // letter-token so the apostrophe never appears as a standalone
            // token mid-word (which would otherwise be paired-quoted with
            // space around it).
            if ("'".equals(t) && i + 1 < raw.length && !result.isEmpty()
                    && isContractionSuffix(raw[i + 1])) {
                Token prev = result.get(result.size() - 1);
                if (!prev.isPunctuation() && !prev.isPossessiveMarker()
                        && Character.isLetter(prev.original().charAt(prev.original().length() - 1))) {
                    String merged = prev.original() + "'" + raw[i + 1];
                    result.set(result.size() - 1, new Token(
                            merged, merged.toLowerCase(Locale.ROOT),
                            false, false, prev.casePattern()));
                    i++;
                    continue;
                }
            }
            boolean punct = isPunctuation(t);
            CasePattern p = punct ? CasePattern.LOWER : casePatternOf(t);
            result.add(new Token(t, t.toLowerCase(Locale.ROOT), punct, false, p));
        }
        return result;
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
