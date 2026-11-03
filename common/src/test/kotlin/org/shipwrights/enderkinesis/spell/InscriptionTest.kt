package org.shipwrights.enderkinesis.spell

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.shipwrights.enderkinesis.spell.Element.BLOOD
import org.shipwrights.enderkinesis.spell.Element.CHAOS
import org.shipwrights.enderkinesis.spell.Element.WINTER
import org.shipwrights.enderkinesis.spell.Element.GRAVITY
import org.shipwrights.enderkinesis.spell.Element.GENESIS
import org.shipwrights.enderkinesis.spell.Element.PYRE
import org.shipwrights.enderkinesis.spell.Element.CORRUPTION
import org.shipwrights.enderkinesis.spell.Element.QUINTESSENCE
import org.shipwrights.enderkinesis.spell.Element.SUNFIRE
import org.shipwrights.enderkinesis.spell.Element.VOID

class InscriptionTest {

    private val shards = listOf(PYRE, WINTER, GENESIS, QUINTESSENCE, GRAVITY, CHAOS, BLOOD)

    private fun inscribeAll(vararg added: Element): List<Element> =
        added.fold(emptyList()) { glyphs, shard -> Inscription.inscribe(glyphs, shard)!!.glyphs }

    @Test
    fun `opposites annihilate`() {
        inscribeAll(PYRE, WINTER) shouldBe emptyList()
        inscribeAll(GRAVITY, BLOOD, CHAOS) shouldBe listOf(BLOOD)
    }

    @Test
    fun `fusion partners fuse`() {
        inscribeAll(PYRE, QUINTESSENCE) shouldBe listOf(SUNFIRE)
        inscribeAll(GENESIS, WINTER) shouldBe listOf(CORRUPTION)
        inscribeAll(BLOOD, GRAVITY) shouldBe listOf(VOID)
    }

    @Test
    fun `an opposite breaks a fusion and leaves its other component`() {
        inscribeAll(PYRE, QUINTESSENCE, WINTER) shouldBe listOf(QUINTESSENCE)
        inscribeAll(PYRE, QUINTESSENCE, GENESIS) shouldBe listOf(PYRE)
        inscribeAll(WINTER, GENESIS, PYRE) shouldBe listOf(GENESIS)
        inscribeAll(WINTER, GENESIS, QUINTESSENCE) shouldBe listOf(WINTER)
        inscribeAll(GRAVITY, BLOOD, CHAOS) shouldBe listOf(BLOOD)
    }

    @Test
    fun `annihilation comes before fusion`() {
        // Quintessence could fuse with Pyre, but Genesis is its opposite.
        val result = Inscription.inscribe(listOf(PYRE, GENESIS), QUINTESSENCE)!!
        result.glyphs shouldBe listOf(PYRE)
        result.outcome shouldBe Inscription.Outcome.Annihilated(GENESIS, null)
    }

    @Test
    fun `the most recently inscribed glyph is hit`() {
        inscribeAll(PYRE, CHAOS, PYRE, WINTER) shouldBe listOf(PYRE, CHAOS)
        inscribeAll(PYRE, QUINTESSENCE, PYRE, WINTER) shouldBe listOf(SUNFIRE)
        // Pyre, Pyre + Quintessence fuses with the later Pyre, so Winter breaks that fusion.
        inscribeAll(PYRE, PYRE, QUINTESSENCE, WINTER) shouldBe listOf(PYRE, QUINTESSENCE)
    }

    @Test
    fun `a full scroll takes no new glyph but still resolves`() {
        val full = List(Inscription.MAX_GLYPHS) { PYRE }
        Inscription.inscribe(full, CHAOS).shouldBeNull()
        Inscription.inscribe(full, WINTER).shouldNotBeNull().glyphs.size shouldBe 6
        Inscription.inscribe(full, QUINTESSENCE).shouldNotBeNull().glyphs.last() shouldBe SUNFIRE
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
