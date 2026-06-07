package sselith.translator;

import java.util.HashSet;
import java.util.Set;

public final class PosTagMapper {

    private static final Set<String> UD_TAGS = Set.of(
            "ADJ", "ADP", "ADV", "AUX", "CCONJ", "DET", "INTJ", "NOUN", "NUM",
            "PART", "PRON", "PROPN", "PUNCT", "SCONJ", "SYM", "VERB", "X");

    private PosTagMapper() {}

    public static String udTag(String pennTag) {
        if (pennTag == null) return "X";
        // Already a UD tag? Return as-is.
        if (UD_TAGS.contains(pennTag)) return pennTag;
        return switch (pennTag) {
            case "NN", "NNS" -> "NOUN";
            case "NNP", "NNPS" -> "PROPN";
            case "VB", "VBD", "VBG", "VBN", "VBP", "VBZ" -> "VERB";
            case "JJ", "JJR", "JJS" -> "ADJ";
            case "RB", "RBR", "RBS", "WRB" -> "ADV";
            case "PRP", "PRP$", "WP", "WP$" -> "PRON";
            case "DT", "PDT", "WDT" -> "DET";
            case "IN", "TO" -> "ADP";
            case "CC" -> "CCONJ";
            case "MD" -> "AUX";
            case "UH" -> "INTJ";
            case "RP" -> "PART";
            case "CD" -> "NUM";
            default -> "X";
        };
    }

    /** Convert a UD tag (from the modern OpenNLP en-pos model) to Penn Treebank using
     *  surface-form heuristics — en-lemmatizer.dict is keyed on Penn. Already-Penn inputs
     *  pass through. */
    public static String toPenn(String surface, String tag) {
        if (tag == null || tag.isEmpty()) return "X";
        if (!UD_TAGS.contains(tag)) return tag;  // already Penn

        String s = surface == null ? "" : surface.toLowerCase(java.util.Locale.ROOT);
        boolean cap = surface != null && !surface.isEmpty()
                && Character.isUpperCase(surface.charAt(0));

        return switch (tag) {
            case "NOUN" -> (s.endsWith("s") && s.length() > 2 && !s.endsWith("ss")) ? "NNS" : "NN";
            case "PROPN" -> (s.endsWith("s") && s.length() > 3) ? "NNPS" : "NNP";
            case "VERB" -> {
                if (s.endsWith("ing")) yield "VBG";
                if (s.endsWith("ed")) yield "VBD";
                if (s.endsWith("en") && s.length() > 3) yield "VBN";
                if (s.endsWith("s") && !s.endsWith("ss")) yield "VBZ";
                // VBP (base form, non-3rd-person present): no morphology feature applied.
                // Defaulting to VB would over-apply the INF suffix to imperatives and bare
                // forms that aren't actually infinitives.
                yield "VBP";
            }
            case "ADJ" -> {
                if (s.endsWith("est")) yield "JJS";
                if (s.endsWith("er") && s.length() > 3) yield "JJR";
                yield "JJ";
            }
            case "ADV" -> {
                if (s.endsWith("est")) yield "RBS";
                if (s.endsWith("er") && s.length() > 3) yield "RBR";
                yield "RB";
            }
            case "PRON" -> s.endsWith("'s") ? "PRP$" : "PRP";
            case "DET" -> "DT";
            case "ADP" -> "IN";
            case "CCONJ" -> "CC";
            case "SCONJ" -> "IN";
            case "AUX" -> "MD";
            case "PART" -> "RP";
            case "INTJ" -> "UH";
            case "NUM" -> "CD";
            case "PUNCT", "SYM" -> ".";
            default -> "X";
        };
    }

    public static Set<String> extractFeatures(String pennTag) {
        Set<String> features = new HashSet<>();
        if (pennTag == null) return features;
        switch (pennTag) {
            case "NNS", "NNPS" -> features.add("PLURAL");
            case "VBD" -> features.add("PAST");
            case "VBG" -> features.add("PROGRESSIVE");
            case "VBN" -> features.add("PERFECT");
            case "VB" -> features.add("INFINITIVE");
            case "JJR", "RBR" -> features.add("COMPARATIVE");
            case "JJS", "RBS" -> features.add("SUPERLATIVE");
            case "PRP$", "WP$" -> features.add("POSSESSIVE");
            default -> { /* no features */ }
        }
        return features;
    }
}
