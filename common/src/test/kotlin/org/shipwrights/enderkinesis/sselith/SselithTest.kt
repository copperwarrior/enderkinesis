package org.shipwrights.enderkinesis.sselith

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class SselithTest {

    // Helper: substring check ignoring case. Output is sentence-cased so substring
    // assertions on lowercase Sselith stems would otherwise miss capitalized first letters.
    private infix fun String.shouldContainCI(substring: String) {
        this.lowercase() shouldContain substring.lowercase()
    }

    @Test
    fun `proper noun passes through unchanged`() {
        Sselith.translate("Sselith") shouldBe "Sselith"
    }

    @Test
    fun `multi-word proper noun is preserved`() {
        Sselith.translate("Sselith Repertory") shouldContain "Sselith Repertory"
    }

    @Test
    fun `plural noun gets PL suffix via stemming`() {
        // "books" → stem "book" → leamorgh → +elk → leamorghelk
        Sselith.translate("books") shouldContainCI "leamorghelk"
    }

    @Test
    fun `past verb gets PAST suffix via stemming`() {
        // "ruled" → not in dict → stem "rule" (dict VERB = regarn) → +acht (PAST) → regarnacht
        Sselith.translate("ruled") shouldContainCI "regarnacht"
    }

    @Test
    fun `progressive verb gets PROG suffix`() {
        // "jumping" — not a dictionary entry itself, but stems to "jump" (verb).
        // "ruling" used to work for this but the dictionary now lists it as a
        // standalone noun ('regarnkwerstegust' = rule + decision), so the
        // as-is lookup wins before surface-form stemming can apply PROG.
        // Pick a verb-only form to keep the test exercising the stemming path.
        Sselith.translate("jumping") shouldContainCI "ouvvolkhend"
    }

    @Test
    fun `bare noun translates without suffix`() {
        // "book" → dict NOUN entry "leamoghk" (the bare root). "books" has a
        // separate dict entry "leamorghelk" with the older 'morgh' root, so this
        // also verifies the bare form does NOT pick up the plural's "elk" suffix.
        val out = Sselith.translate("book")
        out shouldContainCI "leamoghk"
        check(!out.lowercase().contains("leamorghelk")) {
            "Bare noun should not have PL suffix: $out"
        }
    }

    @Test
    fun `arabic numeral converts to base-6 Sselith`() {
        // 34 = 54 in base 6 → unit-name form: vraestmorocht-schest (5×6) + kelkargh (4)
        Sselith.translate("34") shouldBe "Vraestmorocht-schest-kelkargh"
    }

    @Test
    fun `holy invocation renders correctly`() {
        // 34.5 → 34 (vraestmorocht-schest-kelkargh) + skarn + 0.5 (moroch). The holy number.
        Sselith.translate("34.5") shouldBe "Vraestmorocht-schest-kelkargh-skarn-moroch"
    }

    @Test
    fun `surface form dict entry wins over lemma plus morphology`() {
        // Dict has both "craft" → kraekh (root) AND "crafting" → kraekhn (explicit
        // inflected). Without surface-first lookup we'd stem to "craft" and reattach
        // PROG → "kraekhend", clobbering the dict entry.
        val out = Sselith.translate("Crafting")
        out shouldContainCI "kraekhn"
        check(!out.lowercase().contains("kraekhend")) {
            "Surface dict entry 'crafting' must win over morphology-derived 'kraekhend': $out"
        }
    }

    // ── Numeral routing: dict-first for word numbers, converter for digit strings ──

    @Test
    fun `english number word hits dict short form not converter`() {
        // "ten" → dict short form "dekht". The long algorithmic base-6 phrase
        // "schest-kelkargh" (base-6 of 10) must NOT appear — routing word numbers
        // through NumeralConverter would defeat the loan-root's purpose.
        val out = Sselith.translate("ten")
        out shouldContainCI "dekht"
        check(!out.lowercase().contains("schest-kelkargh")) {
            "'ten' must hit dict short form, not algorithmic base-6: $out"
        }
    }

    @Test
    fun `scale word hundred hits dict not converter`() {
        // "hundred" → dict short form "schekht". The long algorithmic phrase for 100
        // (tesk-schaer-kelkargh-schest-kelkargh) must NOT appear.
        val out = Sselith.translate("hundred")
        out shouldContainCI "schekht"
        check(!out.lowercase().contains("tesk-schaer-kelkargh-schest-kelkargh")) {
            "'hundred' must hit dict short form, not algorithmic base-6: $out"
        }
    }

    @Test
    fun `digit string still routes through converter even when word form is in dict`() {
        // "100" (digits) MUST still produce the algorithmic base-6 phrase even though
        // the word "hundred" has a short loan-root. Digit strings belong to the converter.
        val out = Sselith.translate("100")
        out shouldContainCI "tesk-schaer-kelkargh-schest-kelkargh"
    }

    @Test
    fun `roman numeral converts when in section context`() {
        // "Page V" → "Page" stays, "V" → 5 → vraestmorocht
        Sselith.translate("Page V") shouldContainCI "vraestmorocht"
    }

    @Test
    fun `lone capital I without section context is treated as pronoun`() {
        // "I think" → universal pronoun "zhol", not Roman numeral 1 (vir)
        val out = Sselith.translate("I think")
        check(!out.lowercase().contains("vir")) {
            "Bare 'I' should be the pronoun, not Roman numeral 1: $out"
        }
        out shouldContainCI "zhol"
    }

    @Test
    fun `sentence case is applied`() {
        Sselith.translate("the book is here.") shouldStartWith "Za"
    }

    @Test
    fun `possessive marker attaches POSS suffix`() {
        // "Sselith's" → proper noun + POSS = "Sselith" + -och → "Sselithoch"
        Sselith.translate("Sselith's") shouldContainCI "sselithoch"
    }

    @Test
    fun `markdown heading prefix is preserved`() {
        val out = Sselith.translate("# The Library")
        out shouldStartWith "# "
    }

    @Test
    fun `negation prefix recognized on un-`() {
        // "untrue" is an adjective: strip "un" → "true" → serkh → +NEG (na-) → +ADJ (-egh)
        // → "naserkhegh". Check the NEG+stem portion.
        Sselith.translate("untrue") shouldContainCI "naserkh"
    }

    @Test
    fun `missing word passes through unchanged`() {
        // Proper nouns are absent from the lexicon by design ("handling by absence, not by
        // list"), so they pass through unchanged — a stable choice as the lexicon grows.
        Sselith.translate("Barenziah") shouldContain "Barenziah"
    }

    @Test
    fun `format placeholders are preserved in order`() {
        val out = Sselith.translate("Gave %1\$s × %2\$d to %3\$s")
        out shouldContain "%1\$s"
        out shouldContain "%2\$d"
        out shouldContain "%3\$s"
        check(out.indexOf("%1\$s") < out.indexOf("%2\$d") && out.indexOf("%2\$d") < out.indexOf("%3\$s")) {
            "Placeholder order must be preserved: $out"
        }
    }

    @Test
    fun `escaped percent and simple placeholders survive`() {
        // A leading placeholder must not be corrupted by sentence-casing ('%s' must stay '%s').
        val out = Sselith.translate("%s is %d%% complete")
        out shouldContain "%s"
        out shouldContain "%d"
        out shouldContain "%%"
    }

    @Test
    fun `empty input returns empty`() {
        Sselith.translate("") shouldBe ""
    }

    @Test
    fun `line breaks are preserved`() {
        val out = Sselith.translate("Hello.\nWorld.")
        out.lines().size shouldBe 2
    }
}
