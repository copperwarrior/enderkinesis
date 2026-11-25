package org.shipwrights.enderkinesis.item

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WoodHeartsTest {

    /** A body at [health] with [wood] of it already wood. */
    private fun body(health: Float, wood: Float): WoodHearts {
        val hearts = WoodHearts()
        hearts.observe(health - wood, crownWorn = true)
        hearts.observe(health, crownWorn = true)
        return hearts
    }

    @Test
    fun `an even hit takes half from red and half from wood`() {
        woodShare(loss = 4f, red = 10f, wood = 10f) shouldBe 2f
        woodShare(loss = 2f, red = 10f, wood = 10f) shouldBe 1f
    }

    @Test
    fun `an odd half heart goes to wood`() {
        woodShare(loss = 1f, red = 10f, wood = 10f) shouldBe 1f
        woodShare(loss = 3f, red = 10f, wood = 10f) shouldBe 2f
        woodShare(loss = 2.5f, red = 10f, wood = 10f) shouldBe 1.5f
    }

    @Test
    fun `with no wood the red hearts take all`() {
        woodShare(loss = 4f, red = 10f, wood = 0f) shouldBe 0f
    }

    @Test
    fun `with no red the wood hearts take all`() {
        woodShare(loss = 4f, red = 0f, wood = 10f) shouldBe 4f
    }

    @Test
    fun `when wood runs out red takes the rest`() {
        woodShare(loss = 6f, red = 10f, wood = 1f) shouldBe 1f
    }

    @Test
    fun `when red runs out wood takes the rest`() {
        woodShare(loss = 6f, red = 1f, wood = 10f) shouldBe 5f
    }

    @Test
    fun `a hit lowers wood by its share`() {
        val hearts = body(health = 20f, wood = 6f)
        hearts.takeHit(health = 20f, loss = 4f)
        hearts.wood shouldBe 4f
    }

    @Test
    fun `a fatal hit leaves no wood`() {
        val hearts = body(health = 6f, wood = 6f)
        hearts.takeHit(health = 6f, loss = 30f)
        hearts.wood shouldBe 0f
    }

    @Test
    fun `negative loss is refused`() {
        assertThrows<IllegalArgumentException> { WoodHearts().takeHit(20f, -1f) }
    }

    @Test
    fun `healing under the crown grows wood`() {
        val hearts = WoodHearts()
        hearts.observe(10f, crownWorn = true)
        hearts.observe(13f, crownWorn = true)
        hearts.wood shouldBe 3f
        hearts.halfHearts shouldBe 3
    }

    @Test
    fun `healing without the crown stays red and wood stays`() {
        val hearts = body(health = 10f, wood = 4f)
        hearts.observe(14f, crownWorn = false)
        hearts.wood shouldBe 4f
    }

    @Test
    fun `the first look after a load gains no wood`() {
        val hearts = WoodHearts()
        hearts.load(2f)
        hearts.observe(20f, crownWorn = true)
        hearts.wood shouldBe 2f
    }

    @Test
    fun `lost health takes the wood with it`() {
        val hearts = body(health = 20f, wood = 10f)
        hearts.observe(4f, crownWorn = true)
        hearts.wood shouldBe 4f
        hearts.observe(0f, crownWorn = true)
        hearts.wood shouldBe 0f
    }

    @Test
    fun `one heart regrows in ten seconds, one half heart at a time`() {
        val hearts = WoodHearts()
        var health = 10f
        hearts.observe(health, crownWorn = true)
        var regrown = 0f
        repeat(TICKS_PER_HEART) {
            val step = hearts.regrow(health, 20f, crownWorn = true)
            (step == 0f || step == 1f) shouldBe true
            health += step
            regrown += step
            hearts.observe(health, crownWorn = true)
        }
        regrown shouldBe (2f plusOrMinus 1f)
        hearts.wood shouldBe regrown
    }

    @Test
    fun `nothing regrows without the crown or at full health`() {
        val hearts = WoodHearts()
        repeat(TICKS_PER_HEART) {
            hearts.regrow(10f, 20f, crownWorn = false) shouldBe 0f
            hearts.regrow(20f, 20f, crownWorn = true) shouldBe 0f
        }
    }

    @Test
    fun `regrowth never passes max health`() {
        val hearts = WoodHearts()
        var step = 0f
        repeat(TICKS_PER_HEART) { step = maxOf(step, hearts.regrow(19.5f, 20f, crownWorn = true)) }
        step shouldBe 0.5f
    }

    @Test
    fun `load rejects bad values and forces a resend`() {
        val hearts = WoodHearts()
        hearts.sentHalfHearts = 4
        hearts.load(Float.NaN)
        hearts.wood shouldBe 0f
        hearts.sentHalfHearts shouldBe -1
        hearts.load(-3f)
        hearts.wood shouldBe 0f
        hearts.load(6.5f)
        hearts.halfHearts shouldBe 7
    }

    @Test
    fun `resistance and slowness grow with the wood fraction`() {
        woodResistance(woodFraction(0, 20f)) shouldBe 0f
        woodSlowness(woodFraction(0, 20f)) shouldBe 0f
        woodResistance(woodFraction(10, 20f)) shouldBe (0.3f plusOrMinus 1e-6f)
        woodSlowness(woodFraction(10, 20f)) shouldBe (0.175f plusOrMinus 1e-6f)
        woodResistance(woodFraction(20, 20f)) shouldBe WOOD_MAX_RESISTANCE
        woodSlowness(woodFraction(20, 20f)) shouldBe WOOD_MAX_SLOWNESS
    }

    @Test
    fun `wood resists a hit with or without the crown`() {
        val hearts = body(health = 20f, wood = 10f)
        hearts.observe(20f, crownWorn = false)
        woodDamageTaken(10f, hearts.halfHearts, 20f, bypassesInvulnerability = false) shouldBe (7f plusOrMinus 1e-5f)
        woodSlowness(woodFraction(hearts.halfHearts, 20f)) shouldBe (0.175f plusOrMinus 1e-6f)
    }

    @Test
    fun `no wood or a bypassing hit gets no resistance`() {
        woodDamageTaken(10f, 0, 20f, bypassesInvulnerability = false) shouldBe 10f
        woodDamageTaken(10f, 20, 20f, bypassesInvulnerability = true) shouldBe 10f
    }

    @Test
    fun `the fraction stays in range`() {
        woodFraction(40, 20f) shouldBe 1f
        woodFraction(5, 0f) shouldBe 0f
        woodResistance(2f) shouldBe WOOD_MAX_RESISTANCE
        woodSlowness(-1f) shouldBe 0f
    }

    private companion object {
        const val TICKS_PER_HEART = 200
    }
}
