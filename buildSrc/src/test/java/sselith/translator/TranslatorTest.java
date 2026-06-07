package sselith.translator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TranslatorTest {

    private static Path projectRoot;
    private static Path dictPath;
    private static Path posModel;
    private static Path lemmaDict;

    @BeforeAll
    static void setUpPaths() {
        projectRoot = findProjectRoot();
        dictPath = projectRoot.resolve(
                "common/src/main/resources/assets/enderkinesis/texts/sselith/dictionary/sselith_dictionary.json");
        posModel = projectRoot.resolve("buildSrc/src/main/resources/models/en-pos-maxent.bin");
        lemmaDict = projectRoot.resolve("buildSrc/src/main/resources/models/en-lemmatizer.dict");
    }

    private static Path findProjectRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null) {
            if (Files.exists(p.resolve("settings.gradle.kts"))
                    || Files.exists(p.resolve("settings.gradle"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("Could not locate project root from " + Paths.get("").toAbsolutePath());
    }

    private static Translator buildTranslator() throws IOException {
        return new Translator(SselithDictionary.load(dictPath), posModel, lemmaDict);
    }

    private static void assumeModels() {
        assumeTrue(Files.exists(posModel) && Files.exists(lemmaDict),
                "OpenNLP models not present; run :common:downloadSselithModels to enable this test.");
    }

    // ── Morphology (unchanged behavior) ───────────────────────────────────────

    @Test
    void verbGetsPastSuffix() throws IOException {
        assumeModels();
        // "ruled" → lemma "rule" → regarnkh + -acht → regarnkhacht.
        String out = buildTranslator().translate("He ruled.");
        assertTrue(out.contains("regarnkhacht"),
                "Expected PAST suffix on 'rule'. Got: " + out);
    }

    @Test
    void pluralNounGetsPlSuffix() throws IOException {
        assumeModels();
        // "books" → lemma "book" → leamorgh + -elk → leamorghelk.
        String out = buildTranslator().translate("The books are there.");
        assertTrue(out.contains("leamorghelk"),
                "Expected PL suffix on 'book'. Got: " + out);
    }

    @Test
    void markdownHeadingPrefixPreserved() throws IOException {
        assumeModels();
        String out = buildTranslator().translate("# The Library\n\nIt is good.");
        assertTrue(out.startsWith("# "),
                "Markdown heading marker should be preserved. Got: " + out);
    }

    // ── Change 1: lexicon is the sole source of truth ─────────────────────────

    @Test
    void lexiconWordTranslatesAndCapitalizesAtSentenceStart() throws IOException {
        assumeModels();
        // 'creeper' and 'night' are now lexicon entries; 'creeper' is sentence-initial.
        String out = buildTranslator().translate("Creeper attacks at night.");
        assertTrue(out.contains("Khruehpuhr"),
                "Sentence-initial 'Creeper' should translate and capitalize. Got: " + out);
        assertTrue(out.contains("naxt"),
                "'night' should translate to 'naxt'. Got: " + out);
    }

    @Test
    void translatedWordPreservesMidSentenceTitleCase() throws IOException {
        assumeModels();
        // 'Creeper' is Title-cased in the English source — that pattern carries through
        // to the Sselith translation regardless of position in the sentence.
        String out = buildTranslator().translate("The Creeper hunts.");
        assertTrue(out.contains("Khruehpuhr"),
                "Mid-sentence Title-case 'Creeper' should translate to Title-case 'Khruehpuhr'. Got: " + out);
        assertFalse(out.contains("khruehpuhr"),
                "Title-case source must not produce a lowercase translation. Got: " + out);
    }

    @Test
    void translatedWordPreservesAllCaps() throws IOException {
        assumeModels();
        // All-caps surface should produce an all-caps Sselith translation.
        String out = buildTranslator().translate("The CREEPER hunts.");
        assertTrue(out.contains("KHRUEHPUHR"),
                "All-caps 'CREEPER' should translate to all-caps 'KHRUEHPUHR'. Got: " + out);
    }

    @Test
    void translatedWordPreservesLowercase() throws IOException {
        assumeModels();
        // Lowercase mid-sentence should stay lowercase.
        String out = buildTranslator().translate("The creeper hunts.");
        assertTrue(out.contains("khruehpuhr"),
                "Lowercase 'creeper' should translate to lowercase 'khruehpuhr'. Got: " + out);
        assertFalse(out.contains("Khruehpuhr"),
                "Lowercase source must not produce a Title-case translation mid-sentence. Got: " + out);
    }

    @Test
    void unknownWordsPassThroughWithOriginalCase() throws IOException {
        assumeModels();
        // 'barenziah'/'mournhold' are not in the lexicon → passthrough, original case kept.
        String out = buildTranslator().translate("Barenziah was Queen of Mournhold.");
        assertTrue(out.contains("Barenziah"),
                "Unknown 'Barenziah' should pass through unchanged. Got: " + out);
        assertTrue(out.contains("Mournhold"),
                "Unknown 'Mournhold' should pass through unchanged. Got: " + out);
        // 'Queen' is Title-cased; the translation carries that pattern through.
        assertTrue(out.contains("Regarnvrelkh"),
                "Title-case 'Queen' should translate to Title-case 'Regarnvrelkh'. Got: " + out);
        assertTrue(out.contains("toch"),
                "'of' should translate to 'toch'. Got: " + out);
    }

    @Test
    void mixedKnownAndUnknownWords() throws IOException {
        assumeModels();
        // 'mojang'/'studios' not in lexicon → passthrough; 'update' translates.
        String out = buildTranslator().translate("Mojang Studios released the update.");
        assertTrue(out.contains("Mojang"),
                "Unknown 'Mojang' should pass through unchanged. Got: " + out);
        assertTrue(out.contains("Studios"),
                "Unknown 'Studios' should pass through unchanged. Got: " + out);
        assertTrue(out.contains("krekraekust"),
                "'update' should translate to 'krekraekust'. Got: " + out);
    }

    // ── Change 2: format-string placeholder preservation ──────────────────────

    @Test
    void positionalPlaceholdersPreservedInOrder() throws IOException {
        assumeModels();
        String out = buildTranslator().translate("Gave %1$s × %2$d to %3$s");
        assertTrue(out.contains("%1$s"), "Expected %1$s preserved. Got: " + out);
        assertTrue(out.contains("%2$d"), "Expected %2$d preserved. Got: " + out);
        assertTrue(out.contains("%3$s"), "Expected %3$s preserved. Got: " + out);
        assertTrue(out.indexOf("%1$s") < out.indexOf("%2$d")
                        && out.indexOf("%2$d") < out.indexOf("%3$s"),
                "Placeholder order must be preserved. Got: " + out);
    }

    @Test
    void escapedPercentAndPlaceholdersPreserved() throws IOException {
        assumeModels();
        String out = buildTranslator().translate("%s is %d%% complete");
        assertTrue(out.contains("%s"), "Expected %s preserved. Got: " + out);
        assertTrue(out.contains("%d"), "Expected %d preserved. Got: " + out);
        assertTrue(out.contains("%%"), "Expected escaped %% preserved. Got: " + out);
    }

    @Test
    void placeholderSurvivesAlongsideTranslatedWords() throws IOException {
        assumeModels();
        String out = buildTranslator().translate("Saved %s items");
        assertTrue(out.contains("%s"), "Expected %s preserved. Got: " + out);
    }

    @Test
    void escapedPercentInNumericContext() throws IOException {
        assumeModels();
        String out = buildTranslator().translate("100%% loaded");
        assertTrue(out.contains("%%"), "Expected escaped %% preserved. Got: " + out);
    }

    @Test
    void quotedPlaceholderRetainsCleanQuotes() throws IOException {
        assumeModels();
        // Strip/translate/reinsert keeps the quote characters glued to the placeholder
        // — the inline-carving approach would orphan the closing quote with a space.
        String out = buildTranslator().translate("Property '%s' not found");
        assertTrue(out.contains("'%s'"),
                "Quoted placeholder should round-trip as '%s' without stray spaces. Got: " + out);
    }

    // ── Change 3: multi-word dictionary lookup (longest-match-first) ──────────

    @Test
    void multiWordEntryWinsOverSeparateTokens() throws IOException {
        assumeModels();
        // 'iron golem' is a dictionary entry. Lookup must consume both tokens together
        // rather than translating 'iron' and 'golem' independently.
        String out = buildTranslator().translate("Summon an Iron Golem to help.");
        assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("airon kholem"),
                "Multi-word 'Iron Golem' must NOT translate as the two single-token forms. Got: " + out);
    }

    @Test
    void multiWordEntryRespectsSourceCapitalization() throws IOException {
        assumeModels();
        // 'Iron Golem' (Title-case on each word) should yield a Title-cased Sselith form.
        String out = buildTranslator().translate("Iron Golem walks.");
        // Take the first non-space, non-punctuation token; it should start uppercase.
        String firstToken = out.split("\\s+", 2)[0];
        assertTrue(!firstToken.isEmpty() && Character.isUpperCase(firstToken.charAt(0)),
                "First-letter capitalization should propagate from 'Iron Golem'. Got: " + out);
    }

    // ── Surface-form dict lookup wins over lemma + morphology ─────────────────

    @Test
    void surfaceFormWinsOverLemmaPlusMorphology() throws IOException {
        assumeModels();
        // "Crafting" lemmatizes to "craft" (VERB). The dict has BOTH:
        //   craft    → kraekh (VERB)        — root
        //   crafting → kraekhn (VERB)       — explicit inflected form
        // Surface lookup must win — applying lemma + PROG morphology would
        // produce "kraekhend" (kraekh + -end), overriding the explicit form.
        String out = buildTranslator().translate("Crafting");
        assertTrue(out.contains("Kraekhn"),
                "Surface-form dict entry 'crafting' → 'kraekhn' must win over morphology-derived 'kraekhend'. Got: " + out);
        assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("kraekhend"),
                "Lemma+PROG-derived 'kraekhend' must not appear when 'crafting' has a direct dict entry. Got: " + out);
    }

    @Test
    void surfaceFormWinsForPastTense() throws IOException {
        assumeModels();
        // Dict has explicit "walked" → "volkhakht". Without surface-first lookup,
        // we'd derive "walk" + PAST suffix and get the same string by coincidence —
        // but the routing path itself must reflect dict-first authority.
        String out = buildTranslator().translate("walked");
        assertTrue(out.toLowerCase(java.util.Locale.ROOT).contains("volkhakht"),
                "Surface-form dict entry 'walked' → 'volkhakht' should be used. Got: " + out);
    }

    @Test
    void inflectedFormFallsThroughToMorphologyWhenAbsent() throws IOException {
        assumeModels();
        // 'crafted' is NOT in the dict — surface lookup misses, lemma + PAST
        // morphology kicks in: craft (kraekh) + PAST (-acht) → kraekhakht.
        String out = buildTranslator().translate("crafted");
        assertTrue(out.toLowerCase(java.util.Locale.ROOT).contains("kraekh"),
                "Lemma-derived form for 'crafted' should still contain the 'kraekh' stem. Got: " + out);
    }

    @Test
    void bareLemmaUsesDictEntry() throws IOException {
        assumeModels();
        // Bare "craft" (no inflection) → dict["craft"] = "kraekh", no suffix.
        // Surface-first lookup catches this without needing the lemma path.
        String out = buildTranslator().translate("craft");
        assertTrue(out.toLowerCase(java.util.Locale.ROOT).contains("kraekh"),
                "Bare 'craft' should produce 'kraekh'. Got: " + out);
        assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("kraekhn"),
                "Bare 'craft' should NOT get the '-ing' form 'kraekhn'. Got: " + out);
        assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("kraekhend"),
                "Bare 'craft' should NOT get a PROG suffix. Got: " + out);
    }

    // ── Numeral routing: dict-first for word numbers, converter for digit strings ──

    @Test
    void digitStringRoutesToNumeralConverter() throws IOException {
        assumeModels();
        // "10" (base-10) → base-6 14 → unit-name form "schest-kelkargh" (1×6 + 4).
        String out = buildTranslator().translate("10");
        assertTrue(out.toLowerCase(java.util.Locale.ROOT).contains("schest-kelkargh"),
                "Digit string '10' must route to NumeralConverter. Got: " + out);
    }

    @Test
    void englishNumberWordWinsDictOverConverter() throws IOException {
        assumeModels();
        // "ten" has a dict entry ("dekht"). It must NOT be routed through NumeralConverter
        // (which would semantically interpret it and emit the long base-6 phrase). Pin both
        // directions: short form present, long algorithmic phrase absent.
        String out = buildTranslator().translate("ten");
        assertTrue(out.toLowerCase(java.util.Locale.ROOT).contains("dekht"),
                "English number word 'ten' must hit the dict short form 'dekht'. Got: " + out);
        assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("schest-kelkargh"),
                "English number word 'ten' must not be routed through NumeralConverter. Got: " + out);
    }

    @Test
    void scaleWordHundredHitsDictNotConverter() throws IOException {
        assumeModels();
        // "hundred" has a dict entry ("schekht"). The long algorithmic phrase
        // "tesk-schaer-kelkargh-schest-kelkargh" (base-6 of 100) must NOT appear.
        String out = buildTranslator().translate("hundred");
        assertTrue(out.toLowerCase(java.util.Locale.ROOT).contains("schekht"),
                "'hundred' must hit the dict short form 'schekht'. Got: " + out);
        assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("tesk-schaer-kelkargh-schest-kelkargh"),
                "'hundred' must not be routed through NumeralConverter. Got: " + out);
    }

    @Test
    void digitStringStillRoutesEvenWhenWordFormInDict() throws IOException {
        assumeModels();
        // "100" (digits) must STILL produce the algorithmic base-6 phrase even though
        // the word "hundred" now has a short loan-root. Digit strings are the converter's
        // domain; the loan-root only applies to the word form.
        String out = buildTranslator().translate("100");
        assertTrue(out.toLowerCase(java.util.Locale.ROOT).contains("tesk-schaer-kelkargh-schest-kelkargh"),
                "Digit string '100' must still produce the algorithmic base-6 phrase. Got: " + out);
    }

    @Test
    void pluralOnMultiWordEntry() throws IOException {
        assumeModels();
        // Lemmatizer reduces 'Iron Golems' to ['iron','golem']; dict-hit on 'iron golem';
        // plural feature from the last token (Golems → NNS) attaches PL morphology.
        String out = buildTranslator().translate("Iron Golems guard the village.");
        // Sselith plural suffix is '-elk' (per existing tests). Verify a plural-shaped
        // output for the compound rather than a bare-stem one.
        assertTrue(out.toLowerCase(java.util.Locale.ROOT).contains("elk"),
                "Plural multi-word entry should carry the PL '-elk' suffix. Got: " + out);
    }
}
