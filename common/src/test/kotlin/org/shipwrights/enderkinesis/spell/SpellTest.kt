package org.shipwrights.enderkinesis.spell

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

class SpellTest {

    @Test
    fun `shape follows the strongest element, ties by priority`() {
        Spell(listOf(PYRE, PYRE, GRAVITY)).shape shouldBe Shape.SPRAY
        Spell(listOf(GRAVITY, GRAVITY, PYRE, QUINTESSENCE)).shape shouldBe Shape.BOLT
        Spell(listOf(PYRE, GRAVITY)).shape shouldBe Shape.BOLT
        Spell(listOf(PYRE, SUNFIRE, CHAOS)).shape shouldBe Shape.BEAM
        Spell(listOf(PYRE, CHAOS)).shape shouldBe Shape.CHAIN
        Spell(listOf(BLOOD, CORRUPTION)).shape shouldBe Shape.SPRAY
    }

    @Test
    fun `the spec's example name`() {
        val spell = Spell(listOf(GRAVITY, PYRE, PYRE, PYRE, PYRE, PYRE, GENESIS))
        spell.name() shouldBe SpellName(Shape.SPRAY, PYRE, listOf(GRAVITY to 1, GENESIS to 1))
    }

    @Test
    fun `core noun is the strongest shape element, ties by name order`() {
        Spell(listOf(QUINTESSENCE, GENESIS)).name()!!.core shouldBe GENESIS
        Spell(listOf(QUINTESSENCE, QUINTESSENCE, GENESIS)).name()!!.core shouldBe QUINTESSENCE
    }

    @Test
    fun `only the two strongest adjectives are named`() {
        val name = Spell(listOf(CHAOS, PYRE, BLOOD, BLOOD, CORRUPTION)).name()!!
        name.core shouldBe BLOOD
        name.adjectives shouldBe listOf(CHAOS to 1, PYRE to 1)
    }

    @Test
    fun `glyph runs shorten repeats`() {
        Spell(listOf(GRAVITY, PYRE, PYRE, PYRE, GRAVITY)).glyphRuns() shouldBe
            listOf(GRAVITY to 1, PYRE to 3, GRAVITY to 1)
    }

    @Test
    fun `adjective tiers`() {
        listOf(1f, 2f, 3f, 4f, 5f, 7f).map { Spell.adjectiveTier(it) } shouldBe listOf(1, 1, 2, 2, 3, 3)
    }

    @Test
    fun `hold limit runs from 10 s at magnitude 1 to 20 s at 7`() {
        SpellCasting.holdLimitTicks(Spell(listOf(PYRE, GRAVITY))) shouldBe 200
        SpellCasting.holdLimitTicks(Spell(List(7) { CHAOS })) shouldBe 400
        SpellCasting.holdLimitTicks(Spell(listOf(QUINTESSENCE, QUINTESSENCE, QUINTESSENCE, QUINTESSENCE, PYRE))) shouldBe 300
    }

    @Test
    fun `quintessence applies first`() {
        Spell(listOf(PYRE, PYRE, QUINTESSENCE, BLOOD)).magnitudes.keys.toList() shouldBe listOf(QUINTESSENCE, PYRE, BLOOD)
    }

    @Test
    fun `magnitudes count glyphs`() {
        val spell = Spell(listOf(WINTER, WINTER, SUNFIRE))
        spell.magnitude(WINTER) shouldBe 2f
        spell.magnitude(SUNFIRE) shouldBe 1f
        spell.magnitude(PYRE) shouldBe 0f
    }
}
