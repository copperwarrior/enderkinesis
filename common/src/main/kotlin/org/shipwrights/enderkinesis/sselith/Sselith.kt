package org.shipwrights.enderkinesis.sselith

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Runtime Sselith translator — the "lite" counterpart to the build-time `sselith.translator`
 * pipeline in `buildSrc/`.
 *
 * The build-time pipeline uses OpenNLP for POS-tagging and lemmatization and produces the
 * authored book texts that ship in `assets/enderkinesis/texts/sselith/books/`. This runtime
 * version is for short, dynamic snippets — player names, generated lore lines, chat —
 * where shipping OpenNLP (~13 MB of models + ~80–150 MB resident) is not worth the cost.
 *
 * Instead, this performs surface-form stemming (drop common English inflectional suffixes,
 * try each candidate against the dictionary, apply the matched morphology) and falls back
 * to silent passthrough when no entry is found. Output quality is noticeably below the
 * build-time pipeline for long passages but is fine for short labels.
 *
 * Sselith is the language, the realm, and the librarian — all at once. To call
 * `Sselith.translate(...)` is to invoke all three.
 */
object Sselith {

    private const val DICT_RESOURCE =
        "assets/enderkinesis/texts/sselith/dictionary/sselith_dictionary.json"

    private val dictionary: SselithDictionary by lazy { loadDictionary() }

    /**
     * Translate a snippet of English into Sselith. Empty input returns empty.
     *
     * Output preserves line breaks and Markdown structural prefixes (`#`, `-`, etc.).
     * Each source word's case pattern (lowercase / Title-case / ALL-CAPS) is mirrored
     * onto its Sselith translation; sentence-initial lowercase tokens are then
     * uppercased post-hoc.
     *
     * Format-token handling uses the strip-translate-reinsert pattern: each placeholder
     * matching [FORMAT] is replaced by a marker (see [MARKER]) before the prose engine
     * sees the text, then reinserted verbatim afterwards. The prose engine therefore sees
     * an unbroken sentence in every case, including ones with adjacent quotes like
     * `"Property '%s' not found"`.
     */
    fun translate(english: String): String {
        if (english.isEmpty()) return english
        val (afterInvocation, invocationSlots) = stripInvocationPhrase(english)
        val (clean, slots) = stripFormatTokens(afterInvocation)
        val translated = clean.split("\n").joinToString("\n") { translateLine(it) }
        val withFormats = reinsertFormatTokens(translated, slots)
        return reinsertFormatTokens(withFormats, invocationSlots)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Format-token stripping / reinsertion
    // ──────────────────────────────────────────────────────────────────────────

    private data class FormatSlot(val marker: String, val original: String)

    private fun stripFormatTokens(text: String): Pair<String, List<FormatSlot>> {
        val matches = FORMAT.findAll(text).toList()
        if (matches.isEmpty()) return text to emptyList()
        val slots = mutableListOf<FormatSlot>()
        val sb = StringBuilder()
        var last = 0
        for ((i, m) in matches.withIndex()) {
            sb.append(text, last, m.range.first)
            val marker = "qzfmt${encodeMarkerIndex(i)}qz"
            sb.append(marker)
            slots.add(FormatSlot(marker, m.value))
            last = m.range.last + 1
        }
        sb.append(text, last, text.length)
        return sb.toString() to slots
    }

    private fun reinsertFormatTokens(text: String, slots: List<FormatSlot>): String {
        if (slots.isEmpty()) return text
        var result = text
        for (slot in slots) {
            // Case-insensitive because applySentenceCase may have uppercased a
            // sentence-initial marker (e.g. `Qzfmtaqz`).
            result = Regex(Regex.escape(slot.marker), RegexOption.IGNORE_CASE)
                .replace(result, Regex.escapeReplacement(slot.original))
        }
        return result
    }

    /** Encode a non-negative integer as base-26 lowercase letters
     *  (`0 → "a"`, `25 → "z"`, `26 → "ba"`, …). Digit characters would
     *  cause the tokenizer to split the marker across the letter↔digit
     *  boundary into three pieces; letter-only keeps it as one token. */
    private fun encodeMarkerIndex(n: Int): String {
        if (n == 0) return "a"
        val sb = StringBuilder()
        var v = n
        while (v > 0) {
            sb.append('a' + (v % 26))
            v /= 26
        }
        return sb.reverse().toString()
    }

    private val MARKER = Regex("qzfmt[a-z]+qz", RegexOption.IGNORE_CASE)

    // ──────────────────────────────────────────────────────────────────────────
    // Line / structure handling
    // ──────────────────────────────────────────────────────────────────────────

    private val HEADING = Regex("^(\\s*)(#+\\s+)(.*)$")
    private val LIST_ITEM = Regex("^(\\s*)([-*]\\s+)(.*)$")
    private val HORIZONTAL_RULE = Regex("^\\s*-{3,}\\s*$")

    private fun translateLine(line: String): String {
        if (line.isEmpty()) return line
        if (HORIZONTAL_RULE.matches(line)) return line
        HEADING.matchEntire(line)?.let { m ->
            return m.groupValues[1] + m.groupValues[2] + applySentenceCase(translateText(m.groupValues[3]))
        }
        LIST_ITEM.matchEntire(line)?.let { m ->
            return m.groupValues[1] + m.groupValues[2] + applySentenceCase(translateText(m.groupValues[3]))
        }
        // Preserve indentation, translate the rest.
        val leadEnd = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }
        return line.substring(0, leadEnd) + applySentenceCase(translateText(line.substring(leadEnd)))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tokenization + translation
    // ──────────────────────────────────────────────────────────────────────────

    // Splits text into: words (letters/digits/apostrophe-internal), and single non-word
    // characters as their own tokens. Format placeholders are stripped before tokenisation
    // (see translate's strip/reinsert wrappers), so this regex never has to think about them.
    private val TOKEN = Regex("""[A-Za-z]+(?:'[A-Za-z]+)?|\d+(?:\.\d+)?|[^\sA-Za-z0-9]""")

    // Java/C-style format conversions (e.g. %s, %d, %1$s, %2$d, %-5s, %.2f, %%, %n). These
    // must survive translation completely intact and in order. translate() strips spans
    // matching this pattern out of the input before tokenisation and reinserts them at the
    // end; sentence-case here also masks these spans for the rare case the strip leaks one.
    private val FORMAT = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[bBhHsScCdoxXeEfgGaAtT%n]""")

    private enum class CasePattern { LOWER, TITLE, UPPER, MIXED }

    private data class Token(
        val original: String,
        val isPossessive: Boolean,
        val casePattern: CasePattern,
    )

    private fun translateText(text: String): String {
        if (text.isBlank()) return text
        val tokens = tokenize(text)

        val outputs = mutableListOf<String>()
        val maxMulti = dictionary.maxMultiWordTokens
        var i = 0
        while (i < tokens.size) {
            val matchedN = tryMultiWordLookup(tokens, i, maxMulti, outputs)
            if (matchedN > 0) {
                i += matchedN
                continue
            }
            val tok = tokens[i]
            val prev = if (i > 0) tokens[i - 1].original else null
            val rendered = renderToken(tok, prev, outputs)
            if (rendered != null) outputs.add(rendered)
            i++
        }
        return joinTokens(outputs)
    }

    private fun tokenize(text: String): List<Token> {
        if (text.isEmpty()) return emptyList()
        val raw = TOKEN.findAll(text).map { it.value }.toMutableList()
        val result = mutableListOf<Token>()
        var i = 0
        while (i < raw.size) {
            val v = raw[i]
            // Possessive `'s` cleaved off a word — emit possessive marker.
            if (v.length > 2 && v.endsWith("'s") && v[v.length - 3].isLetter()) {
                result.add(Token(v.dropLast(2), false, casePatternOf(v.dropLast(2))))
                result.add(Token("'s", true, CasePattern.LOWER))
                i++
                continue
            }
            // Standalone `'s` after a letter token — same possessive treatment.
            if (v == "'s" && result.isNotEmpty() && result.last().original.lastOrNull()?.isLetter() == true) {
                result.add(Token("'s", true, CasePattern.LOWER))
                i++
                continue
            }
            // Standalone `'` followed by a contraction suffix — glue onto previous token.
            if (v == "'" && i + 1 < raw.size && isContractionSuffix(raw[i + 1])
                && result.isNotEmpty() && result.last().original.lastOrNull()?.isLetter() == true) {
                val prev = result.removeAt(result.size - 1)
                val merged = prev.original + "'" + raw[i + 1]
                result.add(Token(merged, false, prev.casePattern))
                i += 2
                continue
            }
            result.add(Token(v, false, casePatternOf(v)))
            i++
        }
        return result
    }

    private fun isContractionSuffix(s: String): Boolean = when (s.lowercase(Locale.ROOT)) {
        "t", "d", "m", "ll", "re", "ve" -> true
        else -> false
    }

    /**
     * Classify a token's case pattern. See the build-time
     * `EnglishTokenizer.casePatternOf` for the rule set — they must agree.
     */
    private fun casePatternOf(s: String): CasePattern {
        if (s.isEmpty()) return CasePattern.LOWER
        val firstLetterIdx = s.indexOfFirst { it.isLetter() }
        if (firstLetterIdx < 0) return CasePattern.LOWER
        val first = s[firstLetterIdx]
        if (!first.isUpperCase()) return CasePattern.LOWER
        var letters = 0
        var allUpper = true
        var restAllLower = true
        for (i in s.indices) {
            val c = s[i]
            if (!c.isLetter()) continue
            letters++
            if (i == firstLetterIdx) continue
            if (c.isUpperCase()) restAllLower = false else allUpper = false
        }
        if (letters == 1) return CasePattern.TITLE
        return when {
            allUpper -> CasePattern.UPPER
            restAllLower -> CasePattern.TITLE
            else -> CasePattern.MIXED
        }
    }

    private fun applyCase(pattern: CasePattern, s: String): String {
        if (s.isEmpty()) return s
        return when (pattern) {
            CasePattern.UPPER -> s.uppercase(Locale.ROOT)
            CasePattern.TITLE -> capitalizeFirstLetter(s)
            CasePattern.LOWER, CasePattern.MIXED -> s
        }
    }

    /** Uppercase the first letter, leaving leading non-letters (e.g. a
     *  prefix-hyphen like `-ielkh`) alone. */
    private fun capitalizeFirstLetter(s: String): String {
        for (i in s.indices) {
            val c = s[i]
            if (c.isLetter()) {
                if (c.isUpperCase()) return s
                return s.substring(0, i) + c.uppercaseChar() + s.substring(i + 1)
            }
        }
        return s
    }

    /**
     * Try a longest-match-first multi-word dictionary lookup starting at
     * [start]. On hit, emits the translated entry to [outputs] with the
     * first token's case pattern applied, and returns the number of
     * tokens consumed (≥ 2). On miss, returns 0 and the caller falls back
     * to per-token rendering.
     *
     * Includes a plural-stem fallback on the LAST word so phrases like
     * "iron golems" still hit the "iron golem" lexicon entry; in that
     * case [Feature.PLURAL_OR_VBZ] is attached so the PL suffix lands.
     */
    private fun tryMultiWordLookup(
        tokens: List<Token>, start: Int, maxMulti: Int, outputs: MutableList<String>,
    ): Int {
        if (maxMulti < 2) return 0
        val limit = minOf(maxMulti, tokens.size - start)
        if (limit < 2) return 0
        for (n in limit downTo 2) {
            if (!areAllWordLike(tokens, start, n)) continue
            val joined = joinLowercased(tokens, start, n)
            var entry = dictionary.lexicon[joined]
            var feature: Feature? = null
            if (entry == null) {
                val stripped = stripTrailingPlural(joined)
                if (stripped != null) {
                    entry = dictionary.lexicon[stripped]
                    if (entry != null) feature = Feature.PLURAL_OR_VBZ
                }
            }
            if (entry == null) continue
            val rendered = applyMorphology(entry, feature, null)
            outputs.add(applyCase(tokens[start].casePattern, rendered))
            return n
        }
        return 0
    }

    private fun areAllWordLike(tokens: List<Token>, start: Int, n: Int): Boolean {
        for (j in 0 until n) {
            val t = tokens[start + j]
            if (t.isPossessive) return false
            if (isPunctuation(t.original)) return false
            if (t.original.toDoubleOrNull() != null) return false
            if (ROMAN.matches(t.original)) return false
        }
        return true
    }

    private fun joinLowercased(tokens: List<Token>, start: Int, n: Int): String =
        buildString {
            for (j in 0 until n) {
                if (j > 0) append(' ')
                append(tokens[start + j].original.lowercase(Locale.ROOT))
            }
        }

    private fun stripTrailingPlural(joined: String): String? {
        val lastSpace = joined.lastIndexOf(' ')
        if (lastSpace < 0) return null
        val last = joined.substring(lastSpace + 1)
        if (last.endsWith("es") && last.length > 2) {
            return joined.substring(0, lastSpace + 1) + last.dropLast(2)
        }
        if (last.endsWith("s") && last.length > 1 && !last.endsWith("ss")) {
            return joined.substring(0, lastSpace + 1) + last.dropLast(1)
        }
        return null
    }

    /** Returns the translated token, or null to emit nothing (e.g., possessive marker). */
    private fun renderToken(tok: Token, prev: String?, outputsSoFar: MutableList<String>): String? {
        // Possessive marker: attach POSS to previous output, emit nothing.
        if (tok.isPossessive) {
            if (outputsSoFar.isNotEmpty()) {
                val last = outputsSoFar.last()
                val poss = dictionary.suffixes["POSS"] ?: ""
                outputsSoFar[outputsSoFar.lastIndex] = attachSuffix(last, poss)
            }
            return null
        }
        if (isPunctuation(tok.original)) return tok.original

        // The dictionary is the source of truth for English number words ("one",
        // "ten", "hundred", "monday", "january", ...) — they carry short loan-root
        // entries that win over algorithmic base-6 conversion. Routing "hundred"
        // through NumeralConverter would regenerate the long base-6 phrase and
        // defeat the loan-root's purpose. Only pure digit strings ("10", "100",
        // "1.5") and context-resolved Roman numerals fall through to the
        // converter, AFTER a dictionary miss. The holy number
        // (decimalToSselith(34.5)) is still assembled algorithmically and must
        // not be shortcut by these loan-roots.
        val lower = tok.original.lowercase(Locale.ROOT)
        val hit = lookupWithStemming(lower)
        if (hit != null) {
            return applyCase(tok.casePattern, applyMorphology(hit.entry, hit.feature, hit.prefix))
        }

        // Pure digit string → algorithmic base-6 (dict has no digit-string keys).
        tok.original.toDoubleOrNull()?.let { v ->
            return NumeralConverter.decimalToSselith(v)
        }
        if (isRomanNumeral(tok.original, prev)) {
            return NumeralConverter.integerToCardinal(parseRoman(tok.original).toLong())
        }

        // Pronoun fallback, then passthrough with original case preserved.
        return pronounFallback(tok.original, lower)?.let { applyCase(tok.casePattern, it) }
            ?: tok.original
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Stemming + lookup
    // ──────────────────────────────────────────────────────────────────────────

    private enum class Feature { PLURAL_OR_VBZ, PAST_OR_PERF, PROGRESSIVE }

    private data class LookupHit(val entry: LexEntry, val feature: Feature?, val prefix: String?)

    private fun lookupWithStemming(word: String): LookupHit? {
        // 1. Try as-is (covers bare words like "book", "rule", "good", as well as already-
        //    plural-only-form words that happen to be in the dictionary directly).
        dictionary.lexicon[word]?.let { return LookupHit(it, null, null) }

        // 2. Try candidate stems with the suffix that was stripped, in order of most-
        //    specific to most-general so the right feature gets attached.
        for ((cand, feat) in candidateStems(word)) {
            dictionary.lexicon[cand]?.let { return LookupHit(it, feat, null) }
        }

        // 3. Try English derivational prefixes (NEG/RE).
        prefixCandidates(word).forEach { (stripped, prefixTag) ->
            // Recursively re-stem the stripped form (un-ruled → ruled → rule)
            dictionary.lexicon[stripped]?.let { return LookupHit(it, null, prefixTag) }
            for ((cand, feat) in candidateStems(stripped)) {
                dictionary.lexicon[cand]?.let { return LookupHit(it, feat, prefixTag) }
            }
        }

        return null
    }

    /**
     * Produce candidate lemma stems for a surface form, paired with the morphology feature
     * implied by the stripping. Most specific first.
     */
    private fun candidateStems(word: String): List<Pair<String, Feature>> {
        val out = mutableListOf<Pair<String, Feature>>()
        val n = word.length

        // -ies → -y (studies → study)
        if (n > 3 && word.endsWith("ies")) {
            out.add(word.dropLast(3) + "y" to Feature.PLURAL_OR_VBZ)
        }
        // -ied → -y (studied → study)
        if (n > 3 && word.endsWith("ied")) {
            out.add(word.dropLast(3) + "y" to Feature.PAST_OR_PERF)
        }
        // -ing
        if (n > 4 && word.endsWith("ing")) {
            out.add(word.dropLast(3) to Feature.PROGRESSIVE)          // jumping → jump
            out.add(word.dropLast(3) + "e" to Feature.PROGRESSIVE)    // making → make
            if (n > 5 && word[n - 4] == word[n - 5]) {                // running → run
                out.add(word.dropLast(4) to Feature.PROGRESSIVE)
            }
        }
        // -ed
        if (n > 3 && word.endsWith("ed")) {
            out.add(word.dropLast(2) to Feature.PAST_OR_PERF)         // jumped → jump
            out.add(word.dropLast(1) to Feature.PAST_OR_PERF)         // ruled → rule
            if (n > 4 && word[n - 3] == word[n - 4]) {                // stopped → stop
                out.add(word.dropLast(3) to Feature.PAST_OR_PERF)
            }
        }
        // -es → -e or strip
        if (n > 3 && word.endsWith("es")) {
            out.add(word.dropLast(2) to Feature.PLURAL_OR_VBZ)        // boxes → box
            out.add(word.dropLast(1) to Feature.PLURAL_OR_VBZ)        // sees → see
        }
        // -s (final fallback, after -es)
        if (n > 2 && word.endsWith("s") && !word.endsWith("ss")) {
            out.add(word.dropLast(1) to Feature.PLURAL_OR_VBZ)        // books → book
        }
        // -est / -er (rarely used here but the dictionary stores stems so ADJ entries still
        // benefit from finding the bare form).
        if (n > 4 && word.endsWith("est")) out.add(word.dropLast(3) to Feature.PLURAL_OR_VBZ)
        if (n > 3 && word.endsWith("er")) out.add(word.dropLast(2) to Feature.PLURAL_OR_VBZ)

        return out
    }

    private fun prefixCandidates(word: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (word.length > 2 && word.startsWith("un")) out.add(word.substring(2) to "NEG")
        if (word.length > 3 && word.startsWith("non")) out.add(word.substring(3) to "NEG")
        if (word.length > 2 && word.startsWith("in")) out.add(word.substring(2) to "NEG")
        if (word.length > 2 && word.startsWith("re")) out.add(word.substring(2) to "RE")
        return out
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Morphology
    // ──────────────────────────────────────────────────────────────────────────

    private fun applyMorphology(entry: LexEntry, feature: Feature?, prefixTag: String?): String {
        var stem = entry.sselith
        if (prefixTag != null) {
            val pre = dictionary.prefixes[prefixTag]
            if (!pre.isNullOrEmpty()) stem = attachPrefix(stem, pre)
        }
        when (entry.pos) {
            "NOUN" -> if (feature == Feature.PLURAL_OR_VBZ) {
                stem = attachSuffix(stem, dictionary.suffixes["PL"] ?: "")
            }
            "VERB" -> when (feature) {
                Feature.PAST_OR_PERF -> stem = attachSuffix(stem, dictionary.suffixes["PAST"] ?: "")
                Feature.PROGRESSIVE -> stem = attachSuffix(stem, dictionary.suffixes["PROG"] ?: "")
                Feature.PLURAL_OR_VBZ, null -> { /* VBZ or bare — no suffix */ }
            }
            "ADJ" -> stem = attachSuffix(stem, dictionary.suffixes["ADJ"] ?: "")
            "ADV" -> stem = attachSuffix(stem, dictionary.suffixes["ADV"] ?: "")
        }
        return stem
    }

    /** Strict elision: drop the suffix's first char if it matches the stem's last char. */
    internal fun attachSuffix(stem: String, suffix: String): String {
        val s = if (suffix.startsWith("-")) suffix.substring(1) else suffix
        if (stem.isEmpty() || s.isEmpty()) return stem + s
        return if (stem.last().lowercaseChar() == s.first().lowercaseChar()) stem + s.substring(1)
        else stem + s
    }

    /** Strict elision: drop the stem's first char if it matches the prefix's last char. */
    internal fun attachPrefix(stem: String, prefix: String): String {
        val p = if (prefix.endsWith("-")) prefix.substring(0, prefix.length - 1) else prefix
        if (stem.isEmpty() || p.isEmpty()) return p + stem
        return if (p.last().lowercaseChar() == stem.first().lowercaseChar()) p + stem.substring(1)
        else p + stem
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Pronouns / Roman numerals / spacing / sentence case
    // ──────────────────────────────────────────────────────────────────────────

    private val SUBJ_OBJ_PRONOUNS = setOf(
        "i", "me", "you", "he", "him", "she", "her", "it", "we", "us", "they", "them"
    )
    private val POSSESSIVE_PRONOUNS = setOf(
        "my", "your", "his", "its", "our", "their", "mine", "yours", "hers", "ours", "theirs"
        // note: "her" is ambiguous (subj/obj OR possessive); we treat the subj/obj reading
        // by default since it's more common in narrative.
    )
    private val DEMONSTRATIVES = setOf("this", "that", "these", "those")

    private fun pronounFallback(original: String, lower: String): String? {
        val p = dictionary.pronouns
        if (lower.endsWith("self") || lower.endsWith("selves")) return p.reflexive
        if (lower in POSSESSIVE_PRONOUNS) return p.possessive
        if (lower in SUBJ_OBJ_PRONOUNS) return p.subject
        if (lower in DEMONSTRATIVES) return p.demonstrative
        return null
    }

    private val ROMAN = Regex("^[IVXLCDM]+$")
    private val ROMAN_CONTEXT = setOf(
        "page", "chapter", "volume", "part", "section", "book", "act", "scene", "verse", "canto"
    )

    private fun isRomanNumeral(token: String, prev: String?): Boolean {
        if (!ROMAN.matches(token)) return false
        if (token.length > 1) return true
        return prev != null && prev.lowercase(Locale.ROOT) in ROMAN_CONTEXT
    }

    private fun parseRoman(s: String): Int {
        var total = 0
        var prev = 0
        for (i in s.length - 1 downTo 0) {
            val v = when (s[i]) {
                'I' -> 1; 'V' -> 5; 'X' -> 10; 'L' -> 50
                'C' -> 100; 'D' -> 500; 'M' -> 1000
                else -> 0
            }
            if (v < prev) total -= v else total += v
            prev = v
        }
        return total
    }

    private fun isPunctuation(token: String): Boolean =
        token.isNotEmpty() && token.none { it.isLetterOrDigit() }

    private val NO_SPACE_BEFORE = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
    private val NO_SPACE_AFTER = setOf('(', '[', '{')
    // Apostrophe is paired-emphasis here too — the tokenizer pre-collapses
    // contractions and possessive 's so a standalone `'` always means a quote.
    private val PAIRED_PUNCT = setOf('*', '_', '"', '\'')

    private fun joinTokens(tokens: List<String>): String {
        val sb = StringBuilder()
        var suppressLeading = true
        val openState = HashMap<Char, Boolean>().also { PAIRED_PUNCT.forEach { c -> it[c] = false } }
        for (t in tokens) {
            if (t.isEmpty()) continue
            if (t.length == 1 && t[0] in PAIRED_PUNCT) {
                val c = t[0]
                val wasOpen = openState[c] == true
                if (!wasOpen) {
                    if (!suppressLeading) sb.append(' ')
                    sb.append(t)
                    suppressLeading = true
                } else {
                    sb.append(t)
                    suppressLeading = false
                }
                openState[c] = !wasOpen
                continue
            }
            val first = t[0]
            val noSpace = suppressLeading || first in NO_SPACE_BEFORE
            if (!noSpace) sb.append(' ')
            sb.append(t)
            suppressLeading = t.last() in NO_SPACE_AFTER
        }
        return sb.toString()
    }

    private fun applySentenceCase(text: String): String {
        if (text.isEmpty()) return text
        // Format placeholders AND format-strip markers are opaque — their internal letters
        // ('s', 'd', 'q', ...) must never be uppercased. Mask both span families and step
        // over them without consuming the pending sentence-initial capital, so the first
        // real word after a leading placeholder still gets capitalized.
        val masked = BooleanArray(text.length)
        for (m in FORMAT.findAll(text)) for (k in m.range) masked[k] = true
        for (m in MARKER.findAll(text)) for (k in m.range) masked[k] = true
        val sb = StringBuilder(text)
        var capitalizeNext = true
        for (i in sb.indices) {
            if (masked[i]) continue
            val c = sb[i]
            when {
                c.isLetter() -> if (capitalizeNext) {
                    sb.setCharAt(i, c.uppercaseChar())
                    capitalizeNext = false
                }
                c == '.' || c == '!' || c == '?' -> capitalizeNext = true
            }
        }
        return sb.toString()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sselith recognition (reverse direction — "is this token Sselith?")
    //
    // The translator above goes English → Sselith. For scoring authored
    // text (see [SselithBookScore]) we need the reverse: given a token a
    // player wrote, how confidently is it *Sselith*? We stand on three
    // tiers of the same dictionary data — exact surface forms, known
    // stem + valid affix, and finally phonotactic plausibility for novel
    // compounds the lexicon doesn't list verbatim.
    // ──────────────────────────────────────────────────────────────────────────

    /** Every Sselith surface form we can name outright: all lexicon
     *  values, the pronoun forms, and the numeral digit / radix words.
     *  Hyphen- and space-joined compounds are also indexed by part so a
     *  player writing one half of a compound still registers. */
    internal val sselithForms: Set<String> by lazy {
        val set = HashSet<String>()
        fun add(word: String?) {
            if (word.isNullOrEmpty()) return
            val lw = word.lowercase(Locale.ROOT)
            set.add(lw)
            if ('-' in lw || ' ' in lw) {
                lw.split('-', ' ').forEach { if (it.isNotEmpty()) set.add(it) }
            }
        }
        dictionary.lexicon.values.forEach { add(it.sselith) }
        dictionary.pronouns.let {
            add(it.subject); add(it.possessive); add(it.reflexive); add(it.demonstrative)
        }
        dictionary.numerals.digits.values.forEach { add(it) }
        add(dictionary.numerals.radixWord)
        add(dictionary.numerals.nonTerminatingMarker)
        set
    }

    private val sselithSuffixForms: List<String> by lazy {
        dictionary.suffixes.values.map { it.trimStart('-').lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
    }

    private val sselithPrefixForms: List<String> by lazy {
        dictionary.prefixes.values.map { it.trimEnd('-').lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
    }

    /** Clusters that are common in Sselith and near-absent in English —
     *  the cheapest reliable "this looks Sselith" signal. Deliberately
     *  excludes English-frequent `gh`/`ght` (light, thought…) so English
     *  text scores ≈ 0 on the phonotactic tier. */
    private val STRONG_MARKERS = listOf("zh", "kh", "sch", "kht", "rkh")

    /** Characteristic Sselith word endings (affix surfaces + common
     *  derivational tails). */
    private val SSELITH_ENDINGS = listOf("egh", "ust", "argh", "ocht", "elk", "och", "orl", "arn", "kh")

    /**
     * Confidence in [0, 1] that [rawToken] is a Sselith word:
     *
     *  - **1.0** — an exact known surface form (or an all-known hyphen
     *    compound / numeral chain).
     *  - **0.85** — a known stem carrying a valid Sselith affix (elision
     *    approximated both ways), or a partly-known compound.
     *  - **0.0–0.6** — phonotactic plausibility from Sselith-specific
     *    clusters and endings, for inflected/compound forms not listed
     *    verbatim. English tokens land at ≈ 0 here.
     */
    internal fun sselithConfidence(rawToken: String): Double {
        val t = rawToken.lowercase(Locale.ROOT).trim()
        if (t.isEmpty() || t.none { it.isLetter() }) return 0.0
        if (t in sselithForms) return 1.0
        if ('-' in t) {
            val parts = t.split('-').filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) {
                val known = parts.count { it in sselithForms }
                if (known == parts.size) return 1.0
                if (known > 0) return 0.85
            }
        }
        if (isInflectedKnownStem(t)) return 0.85
        return phonotacticScore(t)
    }

    /** True if [t] is a known Sselith stem plus a valid affix. Tries the
     *  affix both whole and with its eliding first/last char dropped —
     *  the mirror of [attachSuffix] / [attachPrefix]. */
    private fun isInflectedKnownStem(t: String): Boolean {
        for (s in sselithSuffixForms) {
            if (t.length > s.length && t.endsWith(s) && t.dropLast(s.length) in sselithForms) return true
            if (s.length >= 2) {
                val elided = s.substring(1)
                if (t.length > elided.length && t.endsWith(elided) &&
                    t.dropLast(elided.length) in sselithForms
                ) return true
            }
        }
        for (p in sselithPrefixForms) {
            if (t.length > p.length && t.startsWith(p) && t.substring(p.length) in sselithForms) return true
            if (p.length >= 2) {
                val elided = p.dropLast(1)
                if (t.length > elided.length && t.startsWith(elided) &&
                    t.substring(elided.length) in sselithForms
                ) return true
            }
        }
        return false
    }

    private fun phonotacticScore(t: String): Double {
        var markerHits = 0
        for (m in STRONG_MARKERS) {
            var idx = t.indexOf(m)
            while (idx >= 0) {
                markerHits++
                idx = t.indexOf(m, idx + m.length)
            }
        }
        var s = minOf(markerHits, 2) * 0.22
        if (SSELITH_ENDINGS.any { t.endsWith(it) }) s += 0.25
        return s.coerceAtMost(0.6)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sselith → English (reverse translation)
    //
    // Powers the Scroll of Unravelling. The dictionary is the same one the
    // forward translator uses, just inverted: each `lexicon[en] = {sselith}`
    // entry contributes an `english <- sselith` reverse mapping. Sselith
    // suffixes/prefixes map back to standard English morphology (PL → -s,
    // PAST → -ed, PROG → -ing, NEG → un-, …). Per-call randomness gives
    // progressive reveal without any per-book state — each pass flips
    // ~[fraction] of the still-Sselith tokens, and untranslated forms
    // simply pass through, so repeat calls converge naturally to English.
    // ──────────────────────────────────────────────────────────────────────────

    private data class ReverseEntry(val english: String, val pos: String)

    /** Sselith surface form (lowercased) → English lemma + POS. First-wins
     *  on collisions; the lexicon does have a handful of synonyms mapped
     *  to the same Sselith stem, but for player-facing reveal any of them
     *  reads fine. */
    private val reverseLexicon: Map<String, ReverseEntry> by lazy {
        val map = HashMap<String, ReverseEntry>()
        for ((english, entry) in dictionary.lexicon) {
            val key = entry.sselith.lowercase(Locale.ROOT)
            if (key !in map) map[key] = ReverseEntry(english, entry.pos)
        }
        map
    }

    /**
     * Reverse-translate a Sselith snippet to English, gated by [fraction]
     * per recognised Sselith token. Tokens we can't reverse-look-up
     * (no dictionary hit even after stripping known Sselith affixes,
     * or already English) pass through unchanged.
     *
     * At `fraction = 0.345` (the holy number) about a third of recognised
     * Sselith tokens flip per call, so repeated calls converge to fully
     * English. Format tokens, punctuation, and structural markdown
     * survive the round-trip — handled the same way [translate] handles
     * them in the forward direction.
     *
     * The holy-number invocation phrase
     * (`vraestmorocht … schest … kelkargh … skarn … moroch` in any
     * separator form) is excluded: it has no English form — it IS
     * Sselith — so it survives every pass intact, and gets normalised to
     * its space-separated written form so it stays readable as the
     * teleport invocation [SselithChatTeleport] listens for.
     */
    fun translateToEnglish(sselith: String, fraction: Double, rng: java.util.Random): String {
        if (sselith.isEmpty()) return sselith
        val (afterInvocation, invocationSlots) = stripInvocationPhrase(sselith)
        val (clean, slots) = stripFormatTokens(afterInvocation)
        val translated = clean.split("\n").joinToString("\n") { reverseLine(it, fraction, rng) }
        val withFormats = reinsertFormatTokens(translated, slots)
        return reinsertFormatTokens(withFormats, invocationSlots)
    }

    /** The five canonical holy-number words, in order. Kept here as the
     *  scroll-exclusion source of truth alongside [SselithChatTeleport]'s
     *  copy — both must agree, and the build-time numeral algorithm in
     *  `NumeralConverter.decimalToSselith(34.5)` is the authority on the
     *  contents and order. */
    private val INVOCATION_WORDS = listOf(
        "vraestmorocht", "schest", "kelkargh", "skarn", "moroch",
    )

    /** Canonical hyphen-joined written form — matches the dictionary's
     *  `numerals.holyNumber.sselith` entry verbatim. The whole phrase is
     *  one written word; no internal spaces. */
    private const val INVOCATION_CANONICAL = "vraestmorocht-schest-kelkargh-skarn-moroch"

    /** Match the five-word invocation with whitespace, hyphens, or commas
     *  (or any combination) between the words. Matches the natural
     *  written forms — `a-b-c-d-e`, `a b c d e`, `a, b, c, d, e` — without
     *  bleeding across sentence boundaries the way `\W+` would. */
    private val INVOCATION_PATTERN: Regex by lazy {
        Regex(INVOCATION_WORDS.joinToString("[\\s,\\-]+"), RegexOption.IGNORE_CASE)
    }

    /** Mask invocation occurrences with format-token-style markers so the
     *  reverse-translation walk treats them as opaque. The marker shape
     *  (`qzfmt[a-z]+qz`) reuses [reinsertFormatTokens]; the slot's
     *  `original` is the *canonical* form, not the matched text, so the
     *  reinsert step is also the normalisation step. */
    private fun stripInvocationPhrase(text: String): Pair<String, List<FormatSlot>> {
        val matches = INVOCATION_PATTERN.findAll(text).toList()
        if (matches.isEmpty()) return text to emptyList()
        val slots = mutableListOf<FormatSlot>()
        val sb = StringBuilder()
        var last = 0
        for ((i, m) in matches.withIndex()) {
            sb.append(text, last, m.range.first)
            val marker = "qzfmtinv${encodeMarkerIndex(i)}qz"
            sb.append(marker)
            slots.add(FormatSlot(marker, INVOCATION_CANONICAL))
            last = m.range.last + 1
        }
        sb.append(text, last, text.length)
        return sb.toString() to slots
    }

    private fun reverseLine(line: String, fraction: Double, rng: java.util.Random): String {
        if (line.isEmpty()) return line
        if (HORIZONTAL_RULE.matches(line)) return line
        HEADING.matchEntire(line)?.let { m ->
            return m.groupValues[1] + m.groupValues[2] + reverseText(m.groupValues[3], fraction, rng)
        }
        LIST_ITEM.matchEntire(line)?.let { m ->
            return m.groupValues[1] + m.groupValues[2] + reverseText(m.groupValues[3], fraction, rng)
        }
        val leadEnd = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }
        return line.substring(0, leadEnd) + reverseText(line.substring(leadEnd), fraction, rng)
    }

    private fun reverseText(text: String, fraction: Double, rng: java.util.Random): String {
        if (text.isBlank()) return text
        val matches = TOKEN.findAll(text).toList()
        if (matches.isEmpty()) return text
        val sb = StringBuilder()
        var last = 0
        for (m in matches) {
            sb.append(text, last, m.range.first)
            val tok = m.value
            val replaced = reverseToken(tok, fraction, rng)
            sb.append(replaced ?: tok)
            last = m.range.last + 1
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }

    private fun reverseToken(token: String, fraction: Double, rng: java.util.Random): String? {
        val first = token.firstOrNull() ?: return null
        if (!first.isLetter()) return null
        val lower = token.lowercase(Locale.ROOT)
        val hit = reverseLookup(lower) ?: return null
        if (rng.nextDouble() >= fraction) return null
        val english = applyEnglishMorphology(hit.english, hit.pos, hit.feature, hit.prefix)
        return applyCase(casePatternOf(token), english)
    }

    private data class ReverseHit(
        val english: String,
        val pos: String,
        val feature: Feature?,
        val prefix: String?,
    )

    /** Try direct hit, then strip Sselith suffixes (both whole and
     *  elided form), then prefixes — mirroring [attachSuffix] / [attachPrefix]
     *  exactly so any inflection the forward translator could have
     *  produced is recoverable here. */
    private fun reverseLookup(word: String): ReverseHit? {
        reverseLexicon[word]?.let { return ReverseHit(it.english, it.pos, null, null) }

        for ((tag, suffix) in dictionary.suffixes) {
            val feat = morphologyFeature(tag) ?: continue
            for (form in elidedForms(suffix.trimStart('-'))) {
                if (form.isEmpty() || !word.endsWith(form)) continue
                val stem = word.dropLast(form.length)
                if (stem.isEmpty()) continue
                // Stem must be a known Sselith form — guards against
                // English words that happen to end in the suffix shape.
                reverseLexicon[stem]?.let {
                    return ReverseHit(it.english, it.pos, feat, null)
                }
                // Elision restores the dropped stem-final char from the
                // suffix's first; try that too.
                val unelidedStem = stem + form.first()
                reverseLexicon[unelidedStem]?.let {
                    return ReverseHit(it.english, it.pos, feat, null)
                }
            }
        }

        for ((tag, prefix) in dictionary.prefixes) {
            val englishPrefix = englishPrefixFor(tag) ?: continue
            for (form in elidedForms(prefix.trimEnd('-'))) {
                if (form.isEmpty() || !word.startsWith(form)) continue
                val stem = word.substring(form.length)
                if (stem.isEmpty()) continue
                reverseLexicon[stem]?.let {
                    return ReverseHit(it.english, it.pos, null, englishPrefix)
                }
                val unelidedStem = form.last() + stem
                reverseLexicon[unelidedStem]?.let {
                    return ReverseHit(it.english, it.pos, null, englishPrefix)
                }
            }
        }
        return null
    }

    /** Candidate surface forms of a Sselith affix: the affix itself and
     *  its elided variant (first char dropped) so we can spot whichever
     *  shape `attachSuffix` / `attachPrefix` happened to produce. */
    private fun elidedForms(affix: String): List<String> =
        if (affix.length >= 2) listOf(affix, affix.substring(1)) else listOf(affix)

    private fun morphologyFeature(tag: String): Feature? = when (tag) {
        "PL" -> Feature.PLURAL_OR_VBZ
        "PAST" -> Feature.PAST_OR_PERF
        "PROG" -> Feature.PROGRESSIVE
        else -> null
    }

    private fun englishPrefixFor(tag: String): String? = when (tag) {
        "NEG" -> "un"
        "RE" -> "re"
        else -> null
    }

    private fun applyEnglishMorphology(
        lemma: String, pos: String, feature: Feature?, prefix: String?,
    ): String {
        val base = when (feature) {
            Feature.PLURAL_OR_VBZ -> when (pos) {
                "NOUN", "VERB" -> englishPluralOrVbz(lemma)
                else -> lemma
            }
            Feature.PAST_OR_PERF -> if (pos == "VERB") englishPast(lemma) else lemma
            Feature.PROGRESSIVE -> if (pos == "VERB") englishProgressive(lemma) else lemma
            null -> lemma
        }
        return if (prefix != null) prefix + base else base
    }

    private fun englishPluralOrVbz(lemma: String): String = when {
        lemma.endsWith("s") || lemma.endsWith("x") || lemma.endsWith("z")
                || lemma.endsWith("ch") || lemma.endsWith("sh") -> lemma + "es"
        lemma.endsWith("y") && lemma.length > 1 && lemma[lemma.length - 2] !in "aeiou" ->
            lemma.dropLast(1) + "ies"
        else -> lemma + "s"
    }

    private fun englishPast(lemma: String): String = when {
        lemma.endsWith("e") -> lemma + "d"
        lemma.endsWith("y") && lemma.length > 1 && lemma[lemma.length - 2] !in "aeiou" ->
            lemma.dropLast(1) + "ied"
        else -> lemma + "ed"
    }

    private fun englishProgressive(lemma: String): String = when {
        lemma.endsWith("e") && lemma.length > 1 && lemma != "be" -> lemma.dropLast(1) + "ing"
        else -> lemma + "ing"
    }

    /** Count tokens in [text] that this translator can reverse-look-up.
     *  Used by the Scroll of Unravelling to phrase its action-bar line. */
    fun countReversibleTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var n = 0
        for (m in TOKEN.findAll(text)) {
            val v = m.value
            if (v.firstOrNull()?.isLetter() != true) continue
            if (reverseLookup(v.lowercase(Locale.ROOT)) != null) n++
        }
        return n
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Dictionary loading
    // ──────────────────────────────────────────────────────────────────────────

    private fun loadDictionary(): SselithDictionary {
        val stream = this::class.java.classLoader.getResourceAsStream(DICT_RESOURCE)
            ?: throw IllegalStateException("Sselith dictionary not found on classpath: $DICT_RESOURCE")
        return stream.use { s ->
            InputStreamReader(s, StandardCharsets.UTF_8).use { reader ->
                parseDictionary(JsonParser.parseReader(reader).asJsonObject)
            }
        }
    }

    private fun parseDictionary(root: JsonObject): SselithDictionary {
        val morph = root.getAsJsonObject("morphology")
        val suffixes = morph.getAsJsonObject("suffixes").entrySet().associate { it.key to it.value.asString }
        val prefixes = morph.getAsJsonObject("prefixes").entrySet().associate { it.key to it.value.asString }

        val num = root.getAsJsonObject("numerals")
        val digits = num.getAsJsonObject("digits").entrySet().associate { it.key to it.value.asString }
        val numerals = NumeralConfig(
            base = num.get("base").asInt,
            digits = digits,
            radixWord = num.get("radixWord").asString,
            nonTerminatingMarker = num.get("nonTerminatingMarker").asString,
            fractionDigitCap = num.get("fractionDigitCap").asInt,
            joiner = num.get("joiner").asString
        )

        val pr = root.getAsJsonObject("pronouns")
        val pronouns = Pronouns(
            subject = pr.get("subject").asString,
            possessive = pr.get("possessive").asString,
            reflexive = pr.get("reflexive").asString,
            demonstrative = pr.get("demonstrative").asString
        )

        val lexicon = HashMap<String, LexEntry>()
        for ((key, value) in root.getAsJsonObject("lexicon").entrySet()) {
            val obj = value.asJsonObject
            lexicon[key.lowercase(Locale.ROOT)] = LexEntry(
                pos = obj.get("pos").asString,
                sselith = obj.get("sselith").asString
            )
        }

        return SselithDictionary(
            lexicon = lexicon,
            suffixes = suffixes,
            prefixes = prefixes,
            numerals = numerals,
            pronouns = pronouns
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Data classes
    // ──────────────────────────────────────────────────────────────────────────

    internal data class LexEntry(val pos: String, val sselith: String)

    internal data class Pronouns(
        val subject: String,
        val possessive: String,
        val reflexive: String,
        val demonstrative: String
    )

    internal data class NumeralConfig(
        val base: Int,
        val digits: Map<String, String>,
        val radixWord: String,
        val nonTerminatingMarker: String,
        val fractionDigitCap: Int,
        val joiner: String
    )

    internal data class SselithDictionary(
        val lexicon: Map<String, LexEntry>,
        val suffixes: Map<String, String>,
        val prefixes: Map<String, String>,
        val numerals: NumeralConfig,
        val pronouns: Pronouns,
    ) {
        /** Max number of space-separated tokens in any lexicon key. The
         *  translator uses this to cap its longest-match-first n-gram
         *  window for multi-word lookups like `"ender dragon"`. */
        val maxMultiWordTokens: Int = lexicon.keys.maxOfOrNull { k ->
            1 + k.count { it == ' ' }
        } ?: 1
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Numeral converter (base-6, with strict-elision-style hyphen joining)
    // ──────────────────────────────────────────────────────────────────────────

    // Base-6 cardinals with unit names. Kept in lock-step with the build-time
    // `sselith.translator.NumeralConverter` (buildSrc) — change both together. Cardinals
    // 0–46,655 use unit-name form; 46,656+ fall back to a pure digit-chain. `skarn` is
    // reserved for the fractional (holy-number) pattern and never separates cardinals.
    private object NumeralConverter {
        private val DIGITS = arrayOf("ul", "vir", "tesk", "moroch", "kelkargh", "vraestmorocht")
        private val UNITS = arrayOf(null, "schest", "schaer", "schalmokt", "schaelvit", "schorruekt")
        private const val MAX_UNIT_POWER = 5
        private const val RADIX = "skarn"
        private const val NON_TERMINATING = "vrecht"

        fun integerToCardinal(n: Long): String {
            if (n < 0) return "ont-" + integerToCardinal(-n)   // REV prefix for negatives
            if (n == 0L) return "ul"

            val digitList = mutableListOf<Int>()
            var temp = n
            while (temp > 0) {
                digitList.add((temp % 6).toInt())
                temp /= 6
            }
            val highest = digitList.size - 1

            // Highest place beyond the unit-name family → pure digit-chain.
            if (highest > MAX_UNIT_POWER) {
                val sb = StringBuilder()
                for (i in highest downTo 0) {
                    if (sb.isNotEmpty()) sb.append('-')
                    sb.append(DIGITS[digitList[i]])
                }
                return sb.toString()
            }

            val sb = StringBuilder()
            for (i in highest downTo 0) {
                val d = digitList[i]
                if (d == 0) continue
                if (sb.isNotEmpty()) sb.append('-')
                when {
                    i == 0 -> sb.append(DIGITS[d])
                    d == 1 -> sb.append(UNITS[i])           // bare unit means 1× that unit
                    else -> sb.append(DIGITS[d]).append('-').append(UNITS[i])
                }
            }
            return sb.toString()
        }

        fun decimalToSselith(d: Double): String {
            val integerPart = d.toLong()
            var fraction = d - integerPart
            val intStr = integerToCardinal(integerPart)
            if (fraction == 0.0) return intStr

            val fracDigits = StringBuilder()
            val maxFracDigits = 8
            var terminating = true
            var i = 0
            while (i < maxFracDigits && fraction > 0) {
                fraction *= 6
                val digit = fraction.toInt()
                fracDigits.append('-').append(DIGITS[digit])
                fraction -= digit
                i++
            }
            if (fraction > 0) terminating = false

            val result = StringBuilder(intStr).append('-').append(RADIX).append(fracDigits)
            if (!terminating) result.append('-').append(NON_TERMINATING)
            return result.toString()
        }
    }
}
