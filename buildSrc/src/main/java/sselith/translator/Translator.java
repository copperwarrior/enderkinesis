package sselith.translator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import opennlp.tools.lemmatizer.DictionaryLemmatizer;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

public final class Translator {

    private static final Set<String> DEMONSTRATIVES = Set.of("this", "that", "these", "those");
    // Keep in step with Sselith.kt.
    private static final Set<String> RELATIVE_PRONOUNS = Set.of("that", "which", "who");
    private static final Set<String> NOUN_MODIFIERS = Set.of("ADJ", "DET", "NUM");
    private static final Set<String> GRADABLE = Set.of("ADJ", "ADV");
    // "There's": after these words an 's is the contraction of "is". Keep in step with Sselith.kt.
    private static final Set<String> IS_HOSTS = Set.of("there", "here", "it", "that", "what", "who", "where");
    private static final Pattern HEADING = Pattern.compile("^(\\s*)(#+\\s+)(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*]\\s+)(.*)$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^\\s*-{3,}\\s*$");
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(?:\\.\\d+)?$");
    private static final Pattern GROUPED_NUMERIC = Pattern.compile("^\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?$");
    private static final Pattern ROMAN = Pattern.compile("^[IVXLCDM]+$");
    // Disambiguates single-char Roman numerals from the pronoun "I" or letter labels;
    // multi-char (II, IV, ...) doesn't need context.
    private static final Set<String> ROMAN_CONTEXT_WORDS = Set.of(
            "page", "chapter", "volume", "part", "section", "book", "act", "scene",
            "verse", "canto");

    /** Marker for format placeholders stripped before tokenization. Base-26 lowercase indices
     *  (a, b, ..., z, ba, ...) — digits would let {@code SimpleTokenizer} split the marker on
     *  the letter↔digit boundary into three tokens. */
    private static final String MARKER_PREFIX = "qzfmt";
    private static final String MARKER_SUFFIX = "qz";

    private static String encodeMarkerIndex(int n) {
        if (n == 0) return "a";
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            sb.append((char) ('a' + (n % 26)));
            n /= 26;
        }
        return sb.reverse().toString();
    }

    private final SselithDictionary dict;
    private final POSTaggerME posTagger;
    private final DictionaryLemmatizer lemmatizer;
    private final EnglishTokenizer tokenizer;

    public Translator(SselithDictionary dict, Path posModelPath, Path lemmaDictPath) throws IOException {
        this.dict = dict;
        try (InputStream in = Files.newInputStream(posModelPath)) {
            this.posTagger = new POSTaggerME(new POSModel(in));
        }
        try (InputStream in = Files.newInputStream(lemmaDictPath)) {
            this.lemmatizer = new DictionaryLemmatizer(in);
        }
        this.tokenizer = new EnglishTokenizer();
    }

    public String translate(String englishText) {
        // Mask the holy-number invocation first — it must not be tokenised
        // by the prose engine (the hyphens would otherwise re-emerge as
        // " - " in the output). Whatever separator form the source uses
        // (hyphens / spaces / commas), the reinsert step normalises it
        // to the canonical space-separated written form.
        Stripped inv = stripInvocationPhrase(englishText);
        Stripped s = stripFormatTokens(inv.cleanedText);
        String[] lines = s.cleanedText.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append("\n");
            out.append(translateLine(lines[i]));
        }
        List<FormatSlot> slots = new ArrayList<>(s.slots);
        slots.addAll(inv.slots);
        return reinsertFormatTokens(out.toString(), slots);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //   holy-number invocation stripping
    //
    // Five-word phrase `vraestmorocht … schest … kelkargh … skarn … moroch`
    // in any of the natural separator forms (hyphens / spaces / commas).
    // Kept in lock-step with NumeralConverter.decimalToSselith(34.5) and
    // SselithChatTeleport's CANONICAL_WORDS — update all three together.
    // ──────────────────────────────────────────────────────────────────────────

    private static final String INVOCATION_CANONICAL =
            "vraestmorocht-schest-kelkargh-skarn-moroch";

    private static final Pattern INVOCATION_PATTERN = Pattern.compile(
            "vraestmorocht[\\s,\\-]+schest[\\s,\\-]+kelkargh[\\s,\\-]+skarn[\\s,\\-]+moroch",
            Pattern.CASE_INSENSITIVE);

    private static Stripped stripInvocationPhrase(String text) {
        Matcher m = INVOCATION_PATTERN.matcher(text);
        if (!m.find()) {
            return new Stripped(text, List.of());
        }
        List<FormatSlot> slots = new ArrayList<>();
        StringBuilder clean = new StringBuilder();
        int last = 0;
        int idx = 0;
        do {
            clean.append(text, last, m.start());
            String marker = MARKER_PREFIX + "inv" + encodeMarkerIndex(idx) + MARKER_SUFFIX;
            clean.append(marker);
            slots.add(new FormatSlot(marker, INVOCATION_CANONICAL));
            idx++;
            last = m.end();
        } while (m.find());
        clean.append(text, last, text.length());
        return new Stripped(clean.toString(), slots);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //   format-token stripping / reinsertion
    // ──────────────────────────────────────────────────────────────────────────

    /** Result of stripping format placeholders out of an input string. */
    record Stripped(String cleanedText, List<FormatSlot> slots) {}

    /** One stripped-out format placeholder and its marker substitute. */
    record FormatSlot(String marker, String original) {}

    // A torn-page gap `[...]` and the word fragment just before it stay as written. Keep in step with Sselith.kt.
    private static final Pattern TORN_GAP = Pattern.compile("\\w*\\[\\.\\.\\.]");
    // A speaker label such as `A:` at the start of a line names a speaker and stays as written.
    private static final Pattern SPEAKER = Pattern.compile("^[A-Z]:(?= )", Pattern.MULTILINE);

    // Sketch tags hold block ids and NBT that must reach the page as written. Keep in step with Sselith.kt.
    private static Stripped stripFormatTokens(String text) {
        List<int[]> ranges = new ArrayList<>(SketchTagSpans.find(text));
        int sketchCount = ranges.size();
        Matcher m = EnglishTokenizer.FORMAT_PATTERN.matcher(text);
        while (m.find()) {
            boolean inSketch = false;
            for (int k = 0; k < sketchCount; k++) {
                if (m.start() >= ranges.get(k)[0] && m.start() < ranges.get(k)[1]) inSketch = true;
            }
            if (!inSketch) ranges.add(new int[] {m.start(), m.end()});
        }
        Matcher torn = TORN_GAP.matcher(text);
        while (torn.find()) ranges.add(new int[] {torn.start(), torn.end()});
        Matcher speaker = SPEAKER.matcher(text);
        while (speaker.find()) ranges.add(new int[] {speaker.start(), speaker.end()});
        if (ranges.isEmpty()) {
            return new Stripped(text, List.of());
        }
        ranges.sort(java.util.Comparator.comparingInt(r -> r[0]));
        List<FormatSlot> slots = new ArrayList<>();
        StringBuilder clean = new StringBuilder();
        int last = 0;
        for (int idx = 0; idx < ranges.size(); idx++) {
            int[] r = ranges.get(idx);
            clean.append(text, last, r[0]);
            String marker = MARKER_PREFIX + encodeMarkerIndex(idx) + MARKER_SUFFIX;
            // A word glued to a gap needs a space to be a word; the space leaves with the marker.
            boolean glued = text.startsWith("[...]", r[1] - 5) && r[1] < text.length()
                    && Character.isLetterOrDigit(text.charAt(r[1]));
            String key = glued ? marker + " " : marker;
            clean.append(key);
            slots.add(new FormatSlot(key, text.substring(r[0], r[1])));
            last = r[1];
        }
        clean.append(text, last, text.length());
        return new Stripped(clean.toString(), slots);
    }

    private static String reinsertFormatTokens(String text, List<FormatSlot> slots) {
        if (slots.isEmpty()) return text;
        java.util.Map<String, String> originals = new java.util.HashMap<>();
        int maxMarkerLength = 0;
        for (FormatSlot slot : slots) {
            originals.put(slot.marker, slot.original);
            maxMarkerLength = Math.max(maxMarkerLength, slot.marker.length());
        }
        // One pass with a bounded look-ahead. Adjacent markers have no separator, so at each marker
        // prefix the longest known marker wins.
        int minMarkerLength = MARKER_PREFIX.length() + 1 + MARKER_SUFFIX.length();
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (!text.regionMatches(true, i, MARKER_PREFIX, 0, MARKER_PREFIX.length())) {
                sb.append(text.charAt(i));
                i++;
                continue;
            }
            String original = null;
            int end = Math.min(text.length(), i + maxMarkerLength);
            for (; end >= i + minMarkerLength; end--) {
                original = originals.get(text.substring(i, end).toLowerCase(Locale.ROOT));
                if (original != null) break;
            }
            if (original == null) {
                sb.append(text.charAt(i));
                i++;
            } else {
                sb.append(original);
                i = end;
            }
        }
        return sb.toString();
    }

    private String translateLine(String line) {
        if (line.isEmpty()) return line;
        if (HORIZONTAL_RULE.matcher(line).matches()) return line;

        Matcher h = HEADING.matcher(line);
        if (h.matches()) {
            return h.group(1) + h.group(2) + translateText(h.group(3));
        }
        Matcher li = LIST_ITEM.matcher(line);
        if (li.matches()) {
            return li.group(1) + li.group(2) + translateText(li.group(3));
        }
        // Preserve leading whitespace (indentation) but translate the rest.
        int leadEnd = 0;
        while (leadEnd < line.length() && Character.isWhitespace(line.charAt(leadEnd))) leadEnd++;
        return line.substring(0, leadEnd) + translateText(line.substring(leadEnd));
    }

    private String translateText(String text) {
        if (text.isBlank()) return text;

        List<EnglishTokenizer.Token> tokens = tokenizer.tokenize(text);

        // Build parallel arrays for POS / lemma calls.
        String[] surface = new String[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) surface[i] = tokens.get(i).original();

        String[] rawTags = posTagger.tag(surface);
        // Modern OpenNLP en-pos models emit UD tags (NOUN/VERB/...). The lemmatizer dict
        // is keyed on Penn Treebank tags (NN/NNS/VBD/...), so convert before lemmatizing.
        String[] pennTags = new String[surface.length];
        for (int i = 0; i < surface.length; i++) {
            pennTags[i] = PosTagMapper.toPenn(surface[i], rawTags[i]);
        }
        String[] lemmas = lemmatizer.lemmatize(surface, pennTags);

        List<String> outputs = new ArrayList<>(tokens.size());
        // The source whitespace before each output, parallel to `outputs`.
        List<String> gaps = new ArrayList<>(tokens.size());
        int maxMulti = dict.getMaxMultiWordTokens();
        int i = 0;
        while (i < tokens.size()) {
            // Multi-word lookup pre-pass: try longest-match-first against
            // dictionary entries containing spaces, before falling back to
            // single-token rendering. Skips punctuation/possessive/numeric
            // tokens — multi-word lexicon entries are noun phrases, never
            // mixed with punctuation.
            int matchedN = tryMultiWordLookup(tokens, lemmas, pennTags, i, maxMulti, outputs);
            if (matchedN > 0) {
                gaps.add(tokens.get(i).gap());
                i += matchedN;
                continue;
            }
            EnglishTokenizer.Token tok = tokens.get(i);
            String prevOriginal = i > 0 ? tokens.get(i - 1).original() : null;
            String nextOriginal = i + 1 < tokens.size() ? tokens.get(i + 1).original() : null;
            String nextTag = i + 1 < tokens.size() ? PosTagMapper.udTag(pennTags[i + 1]) : null;
            boolean isHost = prevOriginal != null && IS_HOSTS.contains(prevOriginal.toLowerCase(Locale.ROOT));
            if (tok.isPossessiveMarker() && (isHost || isParticiple(nextOriginal, nextTag))) {
                outputs.add(dict.lookup("is").sselith());
                gaps.add(" ");
                i++;
                continue;
            }
            String rendered = renderToken(tok, pennTags[i], lemmas[i], outputs, prevOriginal, nextOriginal, nextTag);
            if (rendered != null) {
                outputs.add(rendered);
                gaps.add(numeralGap(tok, i > 0 ? tokens.get(i - 1) : null));
            }
            i++;
        }

        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        // Each output takes the case of its source token, so deliberate lowercase line starts stay lowercase.
        return joinTokens(outputs, gaps) + text.substring(end);
    }

    /**
     * Try to consume an n-gram of consecutive word-like tokens starting at
     * {@code start} via a multi-word dictionary lookup. Tries n =
     * {@code maxMulti} down to 2. On a hit, emits the translated entry
     * (POS features taken from the LAST token, casing from the FIRST) to
     * {@code outputs} and returns the n that matched. On miss, returns 0
     * and the caller falls back to single-token rendering.
     */
    private int tryMultiWordLookup(
            List<EnglishTokenizer.Token> tokens, String[] lemmas, String[] pennTags,
            int start, int maxMulti, List<String> outputs) {
        if (maxMulti < 2) return 0;
        int available = tokens.size() - start;
        int limit = Math.min(maxMulti, available);
        if (limit < 2) return 0;
        for (int n = limit; n >= 2; n--) {
            if (!areAllWordLikeTokens(tokens, start, n)) continue;
            String joined = joinAsLemmas(tokens, lemmas, start, n);
            SselithDictionary.LexiconEntry entry = dict.lookup(joined);
            String effectivePennTag = pennTags[start + n - 1];
            // Plural fallback: the lemmatizer dictionary doesn't list
            // every English noun, so words like "Golems" pass through as
            // "golems" rather than "golem". If the direct join misses,
            // try stripping a trailing -s (or -es) on the LAST word and
            // attach an explicit plural tag so applyMorphology emits the
            // PL suffix.
            if (entry == null) {
                String stripped = stripTrailingPlural(joined);
                if (stripped != null) {
                    entry = dict.lookup(stripped);
                    if (entry != null) effectivePennTag = "NNS";
                }
            }
            if (entry == null) continue;
            // "the end" is the End dimension only as a name. Keep in step with Sselith.kt.
            if ("PROPN".equals(entry.pos())
                    && tokens.get(start + n - 1).casePattern() == EnglishTokenizer.CasePattern.LOWER) continue;
            // POS features from the LAST token (the head of an English
            // compound noun phrase is typically rightmost: "iron golems"
            // — the plural feature sits on "golems").
            String rendered = applyMorphology(entry, null, effectivePennTag);
            String cased = applyCase(tokens.get(start).casePattern(), rendered);
            outputs.add(cased);
            return n;
        }
        return 0;
    }

    /** Strip a trailing English-plural suffix off the last word of {@code
     *  joined} (space-separated lowercase). Returns null if the last word
     *  doesn't end in {@code s}/{@code es}. {@code "iron golems"} →
     *  {@code "iron golem"}, {@code "ender dragones"} →
     *  {@code "ender dragon"}, {@code "iron golem"} → null. */
    private static String stripTrailingPlural(String joined) {
        int lastSpace = joined.lastIndexOf(' ');
        if (lastSpace < 0) return null;
        String last = joined.substring(lastSpace + 1);
        if (last.endsWith("es") && last.length() > 2) {
            return joined.substring(0, lastSpace + 1) + last.substring(0, last.length() - 2);
        }
        if (last.endsWith("s") && last.length() > 1 && !last.endsWith("ss")) {
            return joined.substring(0, lastSpace + 1) + last.substring(0, last.length() - 1);
        }
        return null;
    }

    private static boolean areAllWordLikeTokens(
            List<EnglishTokenizer.Token> tokens, int start, int n) {
        for (int j = 0; j < n; j++) {
            EnglishTokenizer.Token t = tokens.get(start + j);
            if (t.isPunctuation() || t.isPossessiveMarker()) return false;
            if (NUMERIC.matcher(t.original()).matches()) return false;
            if (ROMAN.matcher(t.original()).matches()) return false;
        }
        return true;
    }

    private static String joinAsLemmas(
            List<EnglishTokenizer.Token> tokens, String[] lemmas, int start, int n) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < n; j++) {
            if (j > 0) sb.append(' ');
            String lemma = lemmas[start + j];
            if (lemma == null || lemma.isEmpty() || "O".equals(lemma)) {
                sb.append(tokens.get(start + j).normalized());
            } else {
                sb.append(lemma.toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    /**
     * Capitalize the first letter of the text and the first letter following any
     * sentence-ending punctuation (. ! ?). Proper nouns and intra-word capitalization
     * already present in the joined tokens are left alone — this only modifies positions
     * that were lowercase to uppercase, never the reverse.
     */
    /**
     * Re-apply the source token's case pattern to a translated (always
     * dictionary-lowercase) Sselith form. {@link
     * EnglishTokenizer.CasePattern#MIXED MIXED} (the source was something
     * like {@code iRoN}) returns the lowercase form unchanged — replicating
     * that weirdness would be guesswork.
     */
    private static String applyCase(EnglishTokenizer.CasePattern pattern, String s) {
        if (s == null || s.isEmpty()) return s;
        return switch (pattern) {
            case UPPER -> s.toUpperCase(Locale.ROOT);
            case TITLE -> capitalizeFirstLetter(s);
            case LOWER, MIXED -> s;
        };
    }

    /** Uppercase the first ASCII letter in {@code s}; everything else is
     *  left alone. Handles cases where the Sselith form begins with a
     *  prefix-hyphen ({@code "-ielkh"}) by skipping past non-letters. */
    private static String capitalizeFirstLetter(String s) {
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (Character.isLetter(c)) {
                if (Character.isUpperCase(c)) return s;
                sb.setCharAt(i, Character.toUpperCase(c));
                return sb.toString();
            }
        }
        return s;
    }

    /**
     * @return rendered token string, or null to emit nothing (e.g., possessive marker that
     *         mutated the previous output in place).
     */
    private String renderToken(EnglishTokenizer.Token tok, String pennTag, String lemma,
                               List<String> outputsSoFar, String prevOriginal, String nextOriginal,
                               String nextTag) {
        if (tok.isPossessiveMarker()) {
            // Attach POSS to the last emitted token.
            if (!outputsSoFar.isEmpty()) {
                int lastIdx = outputsSoFar.size() - 1;
                String last = outputsSoFar.get(lastIdx);
                String poss = dict.getSuffixes().get("POSS");
                outputsSoFar.set(lastIdx, MorphologyEngine.attachSuffix(last, poss, dict.getVowels()));
            }
            return null;
        }
        if (tok.isPunctuation()) {
            return tok.original();
        }

        // A section word before a Roman numeral ("Page I", "Chapter V") makes it a number. This runs
        // before the dictionary, which has the pronoun "i" and letter labels for the same tokens.
        if (isSectionNumeral(tok.original(), prevOriginal)) {
            return capitalizeFirstLetter(NumeralConverter.integerToCardinal(parseRoman(tok.original())));
        }

        // Surface-form lookup wins over lemma + morphology. The dictionary
        // stores many inflected forms directly ("crafting" → "kraekhn",
        // "walked" → "volkhakht", "was" → "krevirkh", "running" → "morvolkh"),
        // and these are authoritative — applying morphology on top of a
        // lemma hit would produce a derived form (e.g. "kraekhend" from
        // "craft" + PROG) that overrides the explicit dict entry. Skip
        // morphology entirely when the surface form is in the dict.
        // Multi-word entries are handled one level up in translateText.
        String wordClass = wordClassOf(tok.normalized(), PosTagMapper.udTag(pennTag), prevOriginal, nextTag);
        boolean nounSlot = isNounSlot(prevOriginal, nextOriginal);
        SselithDictionary.LexiconEntry surfaceEntry = dict.lookup(tok.normalized());
        if (surfaceEntry != null) {
            return applyCase(tok.casePattern(), sense(surfaceEntry, wordClass, nounSlot).sselith());
        }

        String effectiveLemma = (lemma == null || lemma.equals("O") || lemma.isEmpty())
                ? tok.normalized() : lemma.toLowerCase(Locale.ROOT);

        // The dictionary is the source of truth for English number words ("one",
        // "ten", "hundred", "monday", "january", ...) — they carry short loan-root
        // entries that win over algorithmic base-6 conversion. Routing "hundred"
        // through NumeralConverter would regenerate the long base-6 phrase
        // (tesk-schaer-kelkargh-schest-kelkargh) and defeat the loan-root's
        // purpose. Only pure digit strings ("10", "100", "1.5") and
        // context-resolved Roman numerals fall through to the converter, AFTER a
        // dictionary miss. The holy number (decimalToSselith(34.5)) is still
        // assembled algorithmically and must not be shortcut by these loan-roots.
        SselithDictionary.LexiconEntry entry = dict.lookup(effectiveLemma);
        String engPrefix = null;
        // The lemmatizer dictionary does not list every inflected form ("voices", "listening"). The
        // runtime translator has no lemmatizer and uses these stems, so the two agree on such words.
        String stemTag = null;
        if (entry == null) {
            for (Stem candidate : candidateStems(tok.normalized())) {
                entry = dict.lookup(candidate.stem());
                if (entry != null) {
                    stemTag = candidate.pennTag();
                    break;
                }
            }
        }

        if (entry == null) {
            // Pure digit string → algorithmic base-6 (dict has no digit-string keys).
            String digits = GROUPED_NUMERIC.matcher(tok.original()).matches()
                    ? tok.original().replace(",", "") : tok.original();
            if (NUMERIC.matcher(digits).matches()) {
                try {
                    double v = Double.parseDouble(digits);
                    if (NumeralConverter.isConvertible(v)) {
                        return NumeralConverter.decimalToSselith(v);
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to normal handling
                }
            }
            if (isRomanNumeral(tok.original(), prevOriginal)) {
                int value = parseRoman(tok.original());
                return NumeralConverter.integerToCardinal(value);
            }
            // Try stripping English derivational prefixes.
            String stripped;
            if (effectiveLemma.startsWith("un") && effectiveLemma.length() > 2) {
                stripped = effectiveLemma.substring(2);
                entry = dict.lookup(stripped);
                if (entry != null) engPrefix = "NEG";
            }
            if (entry == null && effectiveLemma.startsWith("non") && effectiveLemma.length() > 3) {
                stripped = effectiveLemma.substring(3);
                entry = dict.lookup(stripped);
                if (entry != null) engPrefix = "NEG";
            }
            if (entry == null && effectiveLemma.startsWith("in") && effectiveLemma.length() > 2) {
                stripped = effectiveLemma.substring(2);
                entry = dict.lookup(stripped);
                if (entry != null) engPrefix = "NEG";
            }
            if (entry == null && effectiveLemma.startsWith("re") && effectiveLemma.length() > 2) {
                stripped = effectiveLemma.substring(2);
                entry = dict.lookup(stripped);
                if (entry != null) engPrefix = "RE";
            }
        }

        if (entry == null) {
            String abstractNoun = nessAbstract(effectiveLemma);
            if (abstractNoun != null) return applyCase(tok.casePattern(), abstractNoun);
            // Universal pronoun fallback. The tagger can call a lone letter ("(s)") a pronoun.
            String pronoun = tok.original().length() > 1 ? pronounFor(tok.original(), pennTag, effectiveLemma) : null;
            if (pronoun != null) return applyCase(tok.casePattern(), pronoun);
            // Passthrough: keep original case so proper-noun-shaped misclassifications survive.
            return tok.original();
        }

        return applyCase(tok.casePattern(),
                applyMorphology(sense(entry, wordClass, nounSlot), engPrefix, stemTag != null ? stemTag : pennTag));
    }

    /** The tagger calls "rings" in "the one that rings" a NOUN and clause-initial "Yet" an ADV. Only entries
     *  with a sense for the new class see the change. Keep in step with Sselith.kt. */
    private static String wordClassOf(String word, String taggedClass, String prevOriginal, String nextTag) {
        // "too far": an ADV before an ADJ/ADV takes its DEGREE sense, if it has one.
        if ("ADV".equals(taggedClass) && nextTag != null && GRADABLE.contains(nextTag)) return "DEGREE";
        boolean afterRelative = prevOriginal != null
                && RELATIVE_PRONOUNS.contains(prevOriginal.toLowerCase(Locale.ROOT));
        if ("NOUN".equals(taggedClass) && afterRelative && word.endsWith("s")) return "VERB";
        boolean clauseStart = prevOriginal == null || EnglishTokenizer.isPunctuation(prevOriginal);
        if ("ADV".equals(taggedClass) && clauseStart) return "CCONJ";
        return taggedClass;
    }

    /** "link's forged": an 's before a past participle is "is", not the possessive. Keep in step with Sselith.kt. */
    private static boolean isParticiple(String word, String udTag) {
        if (word == null || !"VERB".equals(udTag)) return false;
        String w = word.toLowerCase(Locale.ROOT);
        return w.endsWith("ed") || w.endsWith("en");
    }

    /** A phrase-final word after an ADJ/DET/NUM word ("the seventh bound."). */
    private boolean isNounSlot(String prevOriginal, String nextOriginal) {
        boolean phraseEnd = nextOriginal == null || EnglishTokenizer.isPunctuation(nextOriginal);
        SselithDictionary.LexiconEntry prevEntry = prevOriginal == null ? null : dict.lookup(prevOriginal);
        return phraseEnd && prevEntry != null && NOUN_MODIFIERS.contains(prevEntry.pos());
    }

    /** The tagger calls "the seventh bound." an ADJ, so an ADJ entry in a noun slot takes its NOUN sense.
     *  Keep in step with Sselith.kt. */
    private static SselithDictionary.LexiconEntry sense(
            SselithDictionary.LexiconEntry entry, String wordClass, boolean nounSlot) {
        return entry.forWordClass(nounSlot && "ADJ".equals(entry.pos()) ? "NOUN" : wordClass);
    }

    /** English -ness gives the base root + ABSTRACT, as in weak → weakness. Keep in step with Sselith.kt. */
    private String nessAbstract(String word) {
        if (word.length() < 7 || !word.endsWith("ness")) return null;
        String base = word.substring(0, word.length() - 4);
        SselithDictionary.LexiconEntry baseEntry = dict.lookup(base);
        if (baseEntry == null && base.endsWith("i")) baseEntry = dict.lookup(base.substring(0, base.length() - 1) + "y");
        if (baseEntry == null) return null;
        String root = baseEntry.sselith();
        if (root.endsWith("egh") || root.endsWith("orl")) root = root.substring(0, root.length() - 3);
        return MorphologyEngine.attachSuffix(root, dict.getSuffixes().get("ABSTRACT"), dict.getVowels());
    }

    private record Stem(String stem, String pennTag) {}

    /** Candidate stems of a surface form, most specific first, with the Penn tag that the removed
     *  ending implies. Kept in lock-step with {@code candidateStems} in the runtime {@code Sselith.kt}. */
    private static List<Stem> candidateStems(String word) {
        List<Stem> out = new ArrayList<>();
        int n = word.length();
        if (n > 3 && word.endsWith("ies")) out.add(new Stem(word.substring(0, n - 3) + "y", "NNS"));
        if (n > 3 && word.endsWith("ied")) out.add(new Stem(word.substring(0, n - 3) + "y", "VBD"));
        if (n > 4 && word.endsWith("ing")) {
            out.add(new Stem(word.substring(0, n - 3), "VBG"));
            out.add(new Stem(word.substring(0, n - 3) + "e", "VBG"));
            if (n > 5 && word.charAt(n - 4) == word.charAt(n - 5)) out.add(new Stem(word.substring(0, n - 4), "VBG"));
        }
        if (n > 3 && word.endsWith("ed")) {
            out.add(new Stem(word.substring(0, n - 2), "VBD"));
            out.add(new Stem(word.substring(0, n - 1), "VBD"));
            if (n > 4 && word.charAt(n - 3) == word.charAt(n - 4)) out.add(new Stem(word.substring(0, n - 3), "VBD"));
        }
        if (n > 3 && word.endsWith("es")) {
            out.add(new Stem(word.substring(0, n - 2), "NNS"));
            out.add(new Stem(word.substring(0, n - 1), "NNS"));
        }
        if (n > 2 && word.endsWith("s") && !word.endsWith("ss")) out.add(new Stem(word.substring(0, n - 1), "NNS"));
        if (n > 4 && word.endsWith("est")) out.add(new Stem(word.substring(0, n - 3), "NNS"));
        if (n > 3 && word.endsWith("er")) out.add(new Stem(word.substring(0, n - 2), "NNS"));
        return out;
    }

    private static boolean isRomanNumeral(String original, String prevOriginal) {
        if (original == null || !ROMAN.matcher(original).matches()) return false;
        return original.length() > 1 || isSectionNumeral(original, prevOriginal);
    }

    // The section word must have a capital: "Page I" is a number, "the book I read" is the pronoun.
    private static boolean isSectionNumeral(String original, String prevOriginal) {
        if (original == null || prevOriginal == null || prevOriginal.isEmpty()) return false;
        if (!ROMAN.matcher(original).matches()) return false;
        return Character.isUpperCase(prevOriginal.charAt(0))
                && ROMAN_CONTEXT_WORDS.contains(prevOriginal.toLowerCase(Locale.ROOT));
    }

    private static int parseRoman(String s) {
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> 0;
            };
            if (v < prev) total -= v;
            else total += v;
            prev = v;
        }
        return total;
    }

    private String pronounFor(String original, String pennTag, String lemma) {
        SselithDictionary.PronounConfig p = dict.getPronouns();
        String lower = original.toLowerCase(Locale.ROOT);
        if (lower.endsWith("self") || lower.endsWith("selves")) return p.reflexive;
        if ("PRP$".equals(pennTag) || "WP$".equals(pennTag)) return p.possessive;
        if ("PRP".equals(pennTag) || "WP".equals(pennTag)) return p.subject;
        if (DEMONSTRATIVES.contains(lemma)) return p.demonstrative;
        return null;
    }

    private String applyMorphology(SselithDictionary.LexiconEntry entry, String engPrefix,
                                   String pennTag) {
        String stem = entry.sselith();
        Set<Character> vowels = dict.getVowels();

        // Apply English-derived prefix (NEG/RE) first.
        if (engPrefix != null) {
            String prefix = dict.getPrefixes().get(engPrefix);
            if (prefix != null) {
                stem = MorphologyEngine.attachPrefix(stem, prefix, vowels);
            }
        }

        Set<String> features = PosTagMapper.extractFeatures(pennTag);
        String pos = entry.pos();
        switch (pos) {
            case "NOUN" -> {
                if (features.contains("PLURAL")) {
                    stem = MorphologyEngine.attachSuffix(stem, dict.getSuffixes().get("PL"), vowels);
                }
                if (features.contains("POSSESSIVE")) {
                    stem = MorphologyEngine.attachSuffix(stem, dict.getSuffixes().get("POSS"), vowels);
                }
            }
            case "VERB" -> {
                if (features.contains("PAST")) {
                    stem = MorphologyEngine.attachSuffix(stem, dict.getSuffixes().get("PAST"), vowels);
                } else if (features.contains("PROGRESSIVE")) {
                    stem = MorphologyEngine.attachSuffix(stem, dict.getSuffixes().get("PROG"), vowels);
                } else if (features.contains("PERFECT")) {
                    stem = MorphologyEngine.attachSuffix(stem, dict.getSuffixes().get("PERF"), vowels);
                } else if (features.contains("INFINITIVE")) {
                    stem = MorphologyEngine.attachSuffix(stem, dict.getSuffixes().get("INF"), vowels);
                }
            }
            // Lexicon ADJ/ADV forms are final: a derived form carries -egh/-orl, and a root takes none.
            default -> { /* ADJ/ADV/DET/ADP/CCONJ/SCONJ/AUX/PART/INTJ/PRON: bare stem */ }
        }
        return stem;
    }

    private static final String NO_SPACE_BEFORE = ".,;:!?)]}";
    private static final String NO_SPACE_AFTER = "([{";

    /** A number becomes hyphen-joined words, so a number that touches a word or symbol in the source
     *  ("C418", "5×5×3") gets a space. Sentence punctuation and brackets stay attached. */
    private static String numeralGap(EnglishTokenizer.Token tok, EnglishTokenizer.Token prev) {
        if (prev == null || !tok.gap().isEmpty()) return tok.gap();
        if (!startsWithDigit(tok) && !startsWithDigit(prev)) return tok.gap();
        if (NO_SPACE_BEFORE.indexOf(tok.original().charAt(0)) >= 0) return tok.gap();
        if (NO_SPACE_AFTER.indexOf(prev.original().charAt(prev.original().length() - 1)) >= 0) return tok.gap();
        return " ";
    }

    private static boolean startsWithDigit(EnglishTokenizer.Token tok) {
        return Character.isDigit(tok.original().charAt(0));
    }

    private static String joinTokens(List<String> tokens, List<String> gaps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.isEmpty()) continue;
            sb.append(gaps.get(i)).append(t);
        }
        return sb.toString();
    }
}
