package org.shipwrights.enderkinesis.sselith

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Behavioural tests for the Wylland Tome book-repair scorer. These assert
 * the *gradient* the design calls for rather than exact magic numbers, so
 * they survive constant tuning: empty < gibberish < English < Sselith.
 */
class SselithBookScoreTest {

    // A chunk of English prose, and the same prose run through the
    // runtime translator so the Sselith case uses real dictionary forms.
    private val english = """
        The rule of the great library binds every ship to its anchor.
        Knowledge is the only true power in this place, and the keeper
        guards it well. Read the words, learn the way, and the world will
        open before you. Each page carries the weight of an age.
    """.trimIndent()

    private val sselith = Sselith.translate(english)

    @Test
    fun `empty text scores zero`() {
        SselithBookScore.score("").quality shouldBe 0.0
        SselithBookScore.score("   \n  ").quality shouldBe 0.0
    }

    @Test
    fun `a known Sselith word scores high confidence`() {
        // "book" → leamorgh is a dictionary surface form.
        val book = Sselith.translate("book").lowercase()
        Sselith.sselithConfidence(book) shouldBeGreaterThan 0.8
    }

    @Test
    fun `a plain English word scores near zero`() {
        Sselith.sselithConfidence("the") shouldBeLessThan 0.2
        Sselith.sselithConfidence("library") shouldBeLessThan 0.4
    }

    @Test
    fun `Sselith prose outscores the same prose in English`() {
        val en = SselithBookScore.score(english).quality
        val ss = SselithBookScore.score(sselith).quality
        ss shouldBeGreaterThan en
    }

    @Test
    fun `English prose still beats word-spam`() {
        val spam = ("zzz ".repeat(60))
        SselithBookScore.score(english).quality shouldBeGreaterThan
            SselithBookScore.score(spam).quality
    }

    @Test
    fun `well-written Sselith approaches a full repair`() {
        // Repair scaled to a 1000-point bar; a solid Sselith passage
        // should restore a substantial fraction in one book.
        val points = SselithBookScore.score(sselith).quality * 1000
        points shouldBeGreaterThan 400.0
    }
}
