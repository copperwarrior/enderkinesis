package org.shipwrights.enderkinesis.sselith

import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scores the text written in a vanilla written book and turns it into a
 * Wylland Tome repair amount. The richer and more *Sselith* the writing,
 * the more durability one book restores; an empty book restores nothing.
 *
 * Quality is a weighted blend of the four factors the design calls for,
 * each normalised to [0, 1] with saturating (diminishing) returns so a
 * wall of text can't farm unlimited repair:
 *
 *  1. **Word count** — any words at all ([WORD_TARGET] to saturate).
 *  2. **Sselith mass** — Σ per-word [Sselith.sselithConfidence], so words
 *     actually written in Sselith carry far more weight than English.
 *  3. **Sentence structure** — language-agnostic well-formedness
 *     (capitalised, terminated, sane length, low repetition).
 *  4. **Quality sentences** — count of sentences that are *both*
 *     structurally sound *and* Sselith-dense ([QSENT_TARGET] to saturate).
 *
 * Factors 2 and 4 are what make a well-written Sselith book the best
 * possible fuel: only Sselith writing lights them up. A well-written
 * English book still earns partial credit through factors 1 and 3.
 *
 * The scorer is deterministic and side-effect-free, so it runs
 * identically on client and server (both have the dictionary), keeping
 * the repair animation in sync with the actual effect.
 */
object SselithBookScore {

    /** Words needed before the word-count factor saturates. */
    const val WORD_TARGET = 120.0
    /** Summed Sselith confidence needed before that factor saturates. */
    const val SSELITH_TARGET = 80.0
    /** Quality-sentence count needed before that factor saturates. */
    const val QSENT_TARGET = 8.0

    // Factor weights (sum to 1). Sselith-sensitive factors (2 + 4)
    // dominate so a book written in Sselith outscores everything else.
    private const val W_WORDS = 0.15
    private const val W_SSELITH = 0.40
    private const val W_STRUCT = 0.20
    private const val W_QSENT = 0.25

    /** Structural score a sentence must clear to be a "quality sentence". */
    private const val SENTENCE_QUALITY_BAR = 0.6
    /** Sselith density (mean per-word confidence) a sentence must clear. */
    private const val SENTENCE_SSELITH_BAR = 0.5
    /** Minimum words for a sentence to *count* as a quality sentence —
     *  blocks one-word "Zhaelk." spam from inflating factor 4. */
    private const val MIN_QSENT_WORDS = 4

    data class Score(
        val words: Int,
        val sselithMass: Double,
        val structMean: Double,
        val qualitySentences: Int,
        /** Composite quality in [0, 1]. */
        val quality: Double,
    )

    // A "sentence" is a run of non-terminator text, optionally closed by
    // terminal punctuation. Newlines also break sentences so book page
    // breaks / headings don't glue unrelated lines together.
    private val SENTENCE = Regex("[^.!?\\n]+[.!?]*")
    // A "word" is letters with optional internal hyphen/apostrophe joins
    // (Sselith compounds hyphenate; English contractions apostrophise).
    private val WORD = Regex("[A-Za-z]+(?:[-'][A-Za-z]+)*")

    /** Score raw plain text. */
    fun score(text: String): Score {
        if (text.isBlank()) return Score(0, 0.0, 0.0, 0, 0.0)

        var totalWords = 0
        var sselithMass = 0.0
        val structScores = mutableListOf<Double>()
        var qualitySentences = 0

        for (m in SENTENCE.findAll(text)) {
            val raw = m.value
            val words = WORD.findAll(raw).map { it.value }.toList()
            if (words.isEmpty()) continue

            totalWords += words.size
            var sentenceMass = 0.0
            for (w in words) sentenceMass += Sselith.sselithConfidence(w)
            sselithMass += sentenceMass

            val struct = sentenceStructure(raw, words)
            structScores.add(struct)

            val density = sentenceMass / words.size
            if (words.size >= MIN_QSENT_WORDS &&
                struct >= SENTENCE_QUALITY_BAR &&
                density >= SENTENCE_SSELITH_BAR
            ) {
                qualitySentences++
            }
        }

        val structMean = if (structScores.isEmpty()) 0.0 else structScores.average()
        val quality = (
            W_WORDS * min(1.0, totalWords / WORD_TARGET) +
                W_SSELITH * min(1.0, sselithMass / SSELITH_TARGET) +
                W_STRUCT * structMean +
                W_QSENT * min(1.0, qualitySentences / QSENT_TARGET)
            ).coerceIn(0.0, 1.0)

        return Score(totalWords, sselithMass, structMean, qualitySentences, quality)
    }

    /** Repair points from a written-book stack, scaled to [maxRepair]. */
    fun repairPoints(stack: ItemStack, maxRepair: Int): Int =
        (score(plainText(stack)).quality * maxRepair).roundToInt()

    /** Language-agnostic structural quality of one sentence in [0, 1]:
     *  capitalised opening, terminal punctuation, at least a few words,
     *  and a high type-token ratio (low word repetition). */
    private fun sentenceStructure(raw: String, words: List<String>): Double {
        var q = 0.0
        val firstLetter = raw.firstOrNull { it.isLetter() }
        if (firstLetter != null && firstLetter.isUpperCase()) q += 0.20
        when (raw.trimEnd().lastOrNull()) {
            '.', '!', '?' -> q += 0.20
        }
        if (words.size >= 3) q += 0.20
        val ttr = words.map { it.lowercase() }.toSet().size.toDouble() / words.size
        q += 0.40 * ttr
        return q.coerceIn(0.0, 1.0)
    }

    /** Flatten a written book's pages to plain text. Pages are a list of
     *  JSON-serialised [Component]s (vanilla signed-book format); we
     *  parse each to its display string, falling back to the raw page on
     *  any malformed entry. */
    fun plainText(stack: ItemStack): String {
        val tag = stack.tag ?: return ""
        if (!tag.contains("pages", Tag.TAG_LIST.toInt())) return ""
        val pages = tag.getList("pages", Tag.TAG_STRING.toInt())
        val sb = StringBuilder()
        for (i in 0 until pages.size) {
            val rawPage = pages.getString(i)
            val text = try {
                Component.Serializer.fromJson(rawPage)?.string ?: rawPage
            } catch (e: Exception) {
                rawPage
            }
            sb.append(text).append('\n')
        }
        return sb.toString()
    }
}
