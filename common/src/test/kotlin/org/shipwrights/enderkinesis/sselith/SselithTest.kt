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
    fun `sketch tags pass through untranslated`() {
        val tag = "[sketch=minecraft:oak_stairs[facing=north] size=60 seed=3]"
        val entity = "[sketch:entity=minecraft:sheep{CustomName:'\"%s book\"'}]"
        val out = Sselith.translate("The book shows $tag\n$entity here.")
        out shouldContain tag
        out shouldContain entity
    }

    @Test
    fun `proper noun passes through unchanged`() {
        Sselith.translate("Sselith") shouldBe "Sselith"
    }

    @Test
    fun `a proper name keeps its own form beside a translated word`() {
        Sselith.translate("Sselith Repertory") shouldContain "Sselith Olmnemklokuet"
    }

    @Test
    fun `an -s word after that takes its VERB sense`() {
        Sselith.translate("Yours is the one that rings!") shouldContainCI "kreviegkh"
    }

    @Test
    fun `a noun without a VERB sense after that stays a noun`() {
        Sselith.translate("He knew that books burn.") shouldContainCI "leamorghelk"
    }

    @Test
    fun `lowercase the end is not the End dimension`() {
        Sselith.translate("At the end lie seven doors.") shouldContainCI "schrendust"
        Sselith.translate("Welcome to The End.") shouldContainCI "yghannlokuet"
    }

    @Test
    fun `a lowercase line start stays lowercase`() {
        Sselith.translate("name carved in slate") shouldStartWith "zhnomkh "
    }

    @Test
    fun `clause-initial Yet takes the conjunction sense`() {
        Sselith.translate("Yet sand was he.") shouldStartWith "Kontorl "
        Sselith.translate("It is not done yet.") shouldContainCI "navrend"
    }

    @Test
    fun `a phrase-final word after an ordinal takes its NOUN sense`() {
        Sselith.translate("the seventh bound, there") shouldContainCI "schest-viregh ouvvolkkh,"
        Sselith.translate("The rope is bound.") shouldContainCI "unteltkhegh"
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
        Sselith.translate("34") shouldBe "vraestmorocht-schest-kelkargh"
    }

    @Test
    fun `holy invocation renders correctly`() {
        // 34.5 → 34 (vraestmorocht-schest-kelkargh) + skarn + 0.5 (moroch). The holy number.
        Sselith.translate("34.5") shouldBe "vraestmorocht-schest-kelkargh-skarn-moroch"
    }

    @Test
    fun `scroll does not unravel the holy phrase after the paginator splits it`() {
        val phrase = "vraestmorocht-schest-kelkargh-skarn-moroch"
        val page = BookPaginator.paginate("Zhol $phrase, zhol.").joinToString("\n")
        val out = Sselith.translateToEnglish(page, 1.0, java.util.Random(0))
        out.replace("\n", "") shouldContain phrase
        out.count { it == '\n' } shouldBe page.count { it == '\n' }
    }

    @Test
    fun `scroll does not unravel the holy phrase with a line break in a word`() {
        val phrase = "vraestmorocht-sches\nt-kelkargh-skarn-mo\nroch"
        Sselith.translateToEnglish("Zhol $phrase, zhol.", 1.0, java.util.Random(0)) shouldContain phrase
    }

    @Test
    fun `scroll unravels a holy word that is not in the phrase`() {
        Sselith.translateToEnglish("moroch", 1.0, java.util.Random(0)) shouldBe "three"
        Sselith.translateToEnglish("schest-moroch", 1.0, java.util.Random(0)) shouldBe "nine"
    }

    @Test
    fun `number out of the numeral range passes through unchanged`() {
        Sselith.translate("99999999999999999999") shouldBe "99999999999999999999"
    }

    @Test
    fun `source spacing is kept`() {
        Sselith.translate("Zorblax-Quendimar Host") shouldStartWith "Zorblax-Quendimar "
        Sselith.translate("Upgrade: ").endsWith(": ") shouldBe true
        Sselith.translate("player(s)").endsWith("(s)") shouldBe true
        Sselith.translate("other tomes' links") shouldContain "' "
    }

    @Test
    fun `placeholders survive with attached letters`() {
        Sselith.translate("%s's Head") shouldStartWith "%soch "
        Sselith.translate("%s%s done") shouldStartWith "%s%s "
        Sselith.translate("50% done").contains("done") shouldBe false
    }

    @Test
    fun `section numeral wins over the dictionary`() {
        Sselith.translate("Page I") shouldBe "Leampagkh Vir"
        Sselith.translate("the book I read") shouldContainCI "zhol"
    }

    @Test
    fun `lexicon adjective takes no second suffix`() {
        Sselith.translate("good").lowercase() shouldBe "vielkh"
        Sselith.translate("able").lowercase() shouldBe "kwevegh"
    }

    @Test
    fun `reverse lookup prefers the root word`() {
        Sselith.translateToEnglish("corkharg", 1.0, java.util.Random(0)) shouldBe "body"
        Sselith.translateToEnglish("vielkh", 1.0, java.util.Random(0)) shouldBe "good"
    }

    @Test
    fun `decimal below one keeps its fraction`() {
        Sselith.translate("0.5") shouldBe "ul-skarn-moroch"
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
    fun `case follows the source token`() {
        Sselith.translate("the book is here.") shouldStartWith "za "
        Sselith.translate("The book is here.") shouldStartWith "Za "
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
    fun `the Sselith form of speak translates back to speak`() {
        Sselith.translateToEnglish("zhspraekh", 1.0, java.util.Random(0)) shouldBe "speak"
    }

    @Test
    fun `an apostrophe s before a participle is the verb is`() {
        Sselith.translate("first link's forged") shouldContain "unteltkh velkt"
        Sselith.translate("the link's chain") shouldContain "unteltkhoch"
    }

    @Test
    fun `an apostrophe s after there is the verb is`() {
        Sselith.translate("There's nothing") shouldStartWith "Tharst velkt "
    }

    @Test
    fun `an all-caps line keeps capitals for one-letter words and numbers`() {
        val out = Sselith.translate("LEAVE IT IN A BAG BENEATH THE 84 STAIRS")
        out shouldContain " EN "
        out shouldContain "TESK-SCHAER-TESK-SCHEST"
    }

    @Test
    fun `too before a gradable word is the excess sense`() {
        val out = Sselith.translate("his grandpa too, them too far")
        out shouldContain "aevpaerkht morl,"
        out shouldContain "ouvmorl schrend"
    }

    @Test
    fun `ness gives the base root plus ABSTRACT`() {
        Sselith.translate("grindness") shouldBe "siskmorkhust"
    }

    private fun unravel(sselith: String): String = Sselith.translateToEnglish(sselith, 1.0, java.util.Random(0))

    private fun roundTrip(english: String): String = unravel(Sselith.translate(english))

    @Test
    fun `reverse reads a sense form as its parent word`() {
        unravel("phoev") shouldBe "may"
        unravel("kreviegkh") shouldBe "ring"
        unravel("oundkwielt") shouldBe "lie"
        unravel("ouvmorl") shouldBe "too"
    }

    @Test
    fun `reverse turns a numeral chain back into digits`() {
        roundTrip("Sorrok 34, 505 AL") shouldBe "Sorrok 34, 505 AL"
        roundTrip("BENEATH THE 84 PILLAR STAIRS") shouldContain " 84 "
        roundTrip("Nothing at 11.") shouldBe "Nothing at 11."
        unravel("ont-schest-moroch") shouldBe "-9"
    }

    @Test
    fun `reverse reads a hyphenated lexicon form whole`() {
        roundTrip("the worst offender") shouldBe "the worst offender"
        roundTrip("EIGHT DIAMONDS") shouldBe "EIGHT DIAMONDS"
        unravel("rha-aikh") shouldBe "hear"
    }

    @Test
    fun `reverse strips stacked affixes`() {
        unravel("monkhtarnelkoch") shouldBe "guests'"
        roundTrip("the reweaving") shouldBe "the reweaving"
        roundTrip("Grindness") shouldBe "Grindness"
        roundTrip("kindness") shouldBe "kindness"
    }

    @Test
    fun `reverse prefers the main English word`() {
        roundTrip("I REALLY truly considered them") shouldStartWith "You REALLY really "
        roundTrip("The doors, the doors the doors!") shouldBe "The doors, the doors the doors!"
        roundTrip("YGANN-FILTH!") shouldBe "YGANN-FILTH!"
        roundTrip("those damn hinge-less doors") shouldContain "amn hinge-less doors"
        roundTrip("his grandpa too") shouldBe "your grandpa too"
    }

    @Test
    fun `reverse writes irregular verbs and contractions`() {
        unravel("zhspraekhacht") shouldBe "spoke"
        unravel("renkhakht") shouldBe "got"
        roundTrip("he went home") shouldBe "you went home"
        roundTrip("referred to with dignity") shouldBe "referred to with dignity"
        roundTrip("the goal is fulfilled") shouldBe "the goal is fulfilled"
        roundTrip("And don't get me started") shouldContain "don't"
        roundTrip("cannot be diluted") shouldBe "can't be diluted"
    }

    @Test
    fun `line breaks are preserved`() {
        val out = Sselith.translate("Hello.\nWorld.")
        out.lines().size shouldBe 2
    }
}
