package org.shipwrights.enderkinesis.item

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WoodHeartsTest {

    @Test
    fun `one heart of damage marks two points of wood`() {
        val wood = WoodHearts()
        wood.absorb(2f, 20f) shouldBe false
        wood.wood shouldBe 2f
        wood.halfHearts shouldBe 2
    }

    @Test
    fun `a part of a half heart shows as a whole half heart`() {
        val wood = WoodHearts()
        wood.absorb(0.1f, 20f)
        wood.halfHearts shouldBe 1
    }

    @Test
    fun `the player petrifies when every visible half heart is wood`() {
        val wood = WoodHearts()
        wood.absorb(19f, 20f) shouldBe false
        wood.absorb(0.5f, 20f) shouldBe true
    }

    @Test
    fun `wood never passes health`() {
        val wood = WoodHearts()
        wood.absorb(100f, 7f) shouldBe true
        wood.wood shouldBe 7f
    }

    @Test
    fun `a hit too strong for the remaining health petrifies at once`() {
        val wood = WoodHearts()
        wood.absorb(25f, 20f) shouldBe true
    }

    @Test
    fun `one heart turns back in ten seconds`() {
        val wood = WoodHearts()
        wood.absorb(2f, 20f)
        repeat(TICKS_PER_HEART - 1) { wood.regenerate() }
        wood.halfHearts shouldBe 1
        wood.regenerate()
        wood.wood shouldBe (0f plusOrMinus 1e-4f)
        wood.regenerate()
        wood.wood shouldBe 0f
    }

    @Test
    fun `lost health takes the wood with it`() {
        val wood = WoodHearts()
        wood.absorb(10f, 20f)
        wood.clampTo(4f)
        wood.wood shouldBe 4f
        wood.clampTo(-1f)
        wood.wood shouldBe 0f
    }

    @Test
    fun `a dead body is not petrified`() {
        WoodHearts().isPetrified(0f) shouldBe false
    }

    @Test
    fun `negative damage is refused`() {
        assertThrows<IllegalArgumentException> { WoodHearts().absorb(-1f, 20f) }
    }

    @Test
    fun `load rejects bad values and forces a resend`() {
        val wood = WoodHearts()
        wood.sentHalfHearts = 4
        wood.load(Float.NaN)
        wood.wood shouldBe 0f
        wood.sentHalfHearts shouldBe -1
        wood.load(-3f)
        wood.wood shouldBe 0f
        wood.load(6.5f)
        wood.halfHearts shouldBe 7
    }

    @Test
    fun `resistance and slowness grow with the wood fraction`() {
        woodResistance(woodFraction(0, 20f)) shouldBe 0f
        woodSlowness(woodFraction(0, 20f)) shouldBe 0f
        woodResistance(woodFraction(10, 20f)) shouldBe (0.3f plusOrMinus 1e-6f)
        woodSlowness(woodFraction(10, 20f)) shouldBe (0.25f plusOrMinus 1e-6f)
        woodResistance(woodFraction(20, 20f)) shouldBe WOOD_MAX_RESISTANCE
        woodSlowness(woodFraction(20, 20f)) shouldBe WOOD_MAX_SLOWNESS
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
