package org.shipwrights.enderkinesis.spell

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.shipwrights.enderkinesis.spell.Element.BLOOD
import org.shipwrights.enderkinesis.spell.Element.CHAOS
import org.shipwrights.enderkinesis.spell.Element.COLD
import org.shipwrights.enderkinesis.spell.Element.GRAVITY
import org.shipwrights.enderkinesis.spell.Element.GROWTH
import org.shipwrights.enderkinesis.spell.Element.HEAT
import org.shipwrights.enderkinesis.spell.Element.POISON
import org.shipwrights.enderkinesis.spell.Element.PURITY
import org.shipwrights.enderkinesis.spell.Element.SUNFIRE
import org.shipwrights.enderkinesis.spell.Element.VOID

class InscriptionTest {

    private val shards = listOf(HEAT, COLD, GROWTH, PURITY, GRAVITY, CHAOS, BLOOD)

    private fun inscribeAll(vararg added: Element): List<Element> =
        added.fold(emptyList()) { glyphs, shard -> Inscription.inscribe(glyphs, shard)!!.glyphs }

    @Test
    fun `opposites annihilate`() {
        inscribeAll(HEAT, COLD) shouldBe emptyList()
        inscribeAll(GRAVITY, BLOOD, CHAOS) shouldBe listOf(BLOOD)
    }

    @Test
    fun `fusion partners fuse`() {
        inscribeAll(HEAT, PURITY) shouldBe listOf(SUNFIRE)
        inscribeAll(GROWTH, COLD) shouldBe listOf(POISON)
        inscribeAll(BLOOD, GRAVITY) shouldBe listOf(VOID)
    }

    @Test
    fun `an opposite breaks a fusion and leaves its other component`() {
        inscribeAll(HEAT, PURITY, COLD) shouldBe listOf(PURITY)
        inscribeAll(HEAT, PURITY, GROWTH) shouldBe listOf(HEAT)
        inscribeAll(COLD, GROWTH, HEAT) shouldBe listOf(GROWTH)
        inscribeAll(COLD, GROWTH, PURITY) shouldBe listOf(COLD)
        inscribeAll(GRAVITY, BLOOD, CHAOS) shouldBe listOf(BLOOD)
    }

    @Test
    fun `annihilation comes before fusion`() {
        // Purity could fuse with Heat, but Growth is its opposite.
        val result = Inscription.inscribe(listOf(HEAT, GROWTH), PURITY)!!
        result.glyphs shouldBe listOf(HEAT)
        result.outcome shouldBe Inscription.Outcome.Annihilated(GROWTH, null)
    }

    @Test
    fun `the most recently inscribed glyph is hit`() {
        inscribeAll(HEAT, CHAOS, HEAT, COLD) shouldBe listOf(HEAT, CHAOS)
        inscribeAll(HEAT, PURITY, HEAT, COLD) shouldBe listOf(SUNFIRE)
        // Heat, Heat + Purity fuses with the later Heat, so Cold breaks that fusion.
        inscribeAll(HEAT, HEAT, PURITY, COLD) shouldBe listOf(HEAT, PURITY)
    }

    @Test
    fun `a full scroll takes no new glyph but still resolves`() {
        val full = List(Inscription.MAX_GLYPHS) { HEAT }
        Inscription.inscribe(full, CHAOS).shouldBeNull()
        Inscription.inscribe(full, COLD).shouldNotBeNull().glyphs.size shouldBe 6
        Inscription.inscribe(full, PURITY).shouldNotBeNull().glyphs.last() shouldBe SUNFIRE
    }

    @Test
    fun `every possible scroll is reachable and they share 685 names`() {
        val seen = HashSet<List<Element>>()
        val queue = ArrayDeque<List<Element>>()
        seen += emptyList<Element>()
        queue += emptyList<Element>()
        while (queue.isNotEmpty()) {
            val glyphs = queue.removeFirst()
            for (shard in shards) {
                val next = Inscription.inscribe(glyphs, shard)?.glyphs ?: continue
                if (seen.add(next)) queue += next
            }
        }
        val scrolls = seen.filter { it.isNotEmpty() }.map { glyphs -> glyphs.sortedBy { it.ordinal } }.toSet()
        scrolls.size shouldBe 4851
        scrolls.map { Spell(it).name() }.toSet().size shouldBe 685
    }
}
