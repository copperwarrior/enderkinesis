package org.shipwrights.enderkinesis.spell

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

class SpellTest {

    @Test
    fun `shape follows priority`() {
        Spell(listOf(HEAT, GRAVITY)).shape shouldBe Shape.BOLT
        Spell(listOf(HEAT, SUNFIRE, CHAOS)).shape shouldBe Shape.BEAM
        Spell(listOf(HEAT, CHAOS)).shape shouldBe Shape.CHAIN
        Spell(listOf(BLOOD, POISON)).shape shouldBe Shape.SPRAY
    }

    @Test
    fun `the spec's example name`() {
        val spell = Spell(listOf(GRAVITY, HEAT, HEAT, HEAT, HEAT, HEAT, GROWTH))
        spell.name() shouldBe SpellName(Shape.BOLT, GRAVITY, listOf(HEAT to 3, GROWTH to 1))
    }

    @Test
    fun `core noun is the strongest shape element, ties by name order`() {
        Spell(listOf(PURITY, GROWTH)).name()!!.core shouldBe GROWTH
        Spell(listOf(PURITY, PURITY, GROWTH)).name()!!.core shouldBe PURITY
    }

    @Test
    fun `only the two strongest adjectives are named`() {
        val name = Spell(listOf(CHAOS, HEAT, BLOOD, BLOOD, POISON)).name()!!
        name.core shouldBe CHAOS
        name.adjectives shouldBe listOf(BLOOD to 1, HEAT to 1)
    }

    @Test
    fun `glyph runs shorten repeats`() {
        Spell(listOf(GRAVITY, HEAT, HEAT, HEAT, GRAVITY)).glyphRuns() shouldBe
            listOf(GRAVITY to 1, HEAT to 3, GRAVITY to 1)
    }

    @Test
    fun `adjective tiers`() {
        listOf(1f, 2f, 3f, 4f, 5f, 7f).map { Spell.adjectiveTier(it) } shouldBe listOf(1, 1, 2, 2, 3, 3)
    }

    @Test
    fun `hold limit runs from 10 s at magnitude 1 to 20 s at 7`() {
        SpellCasting.holdLimitTicks(Spell(listOf(HEAT, GRAVITY))) shouldBe 200
        SpellCasting.holdLimitTicks(Spell(List(7) { CHAOS })) shouldBe 400
        SpellCasting.holdLimitTicks(Spell(listOf(PURITY, PURITY, PURITY, PURITY, HEAT))) shouldBe 300
    }

    @Test
    fun `purity applies first`() {
        Spell(listOf(HEAT, HEAT, PURITY, BLOOD)).magnitudes.keys.toList() shouldBe listOf(PURITY, HEAT, BLOOD)
    }

    @Test
    fun `magnitudes count glyphs`() {
        val spell = Spell(listOf(COLD, COLD, SUNFIRE))
        spell.magnitude(COLD) shouldBe 2f
        spell.magnitude(SUNFIRE) shouldBe 1f
        spell.magnitude(HEAT) shouldBe 0f
    }
}
