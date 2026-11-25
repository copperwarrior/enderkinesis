package org.shipwrights.enderkinesis.client

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class WoodVeinMaskTest {

    private fun visible(mask: ByteArray, growth: Float): Int =
        mask.count { isVeinVisible(it.toInt() and 0xFF, growth) }

    @Test
    fun `the same seed gives the same mask`() {
        buildWoodVeinMask(42L, slim = false).contentEquals(buildWoodVeinMask(42L, slim = false)) shouldBe true
        buildWoodVeinMask(42L, slim = true).contentEquals(buildWoodVeinMask(42L, slim = true)) shouldBe true
    }

    @Test
    fun `other seeds give other masks`() {
        buildWoodVeinMask(1L, slim = false).contentEquals(buildWoodVeinMask(2L, slim = false)) shouldBe false
    }

    @Test
    fun `no wood shows no veins and the veins only grow with wood`() {
        val mask = buildWoodVeinMask(7L, slim = false)
        visible(mask, 0f) shouldBe 0
        var last = 0
        for (step in 1..20) {
            val count = visible(mask, step / 20f)
            (count >= last) shouldBe true
            last = count
        }
        last shouldBeGreaterThan 0
    }

    @Test
    fun `the veins start at the head`() {
        val mask = buildWoodVeinMask(7L, slim = false)
        val first = mask.indices.filter { isVeinVisible(mask[it].toInt() and 0xFF, 0.02f) }
        first.size shouldNotBe 0
        // Rows 0..15 hold only the head and the hat.
        first.all { it / VEIN_SKIN_SIZE < 16 } shouldBe true
    }

    @Test
    fun `full wood reaches the limbs but stays thin`() {
        for (slim in listOf(false, true)) {
            val mask = buildWoodVeinMask(99L, slim)
            val shown = mask.indices.filter { isVeinVisible(mask[it].toInt() and 0xFF, 1f) }
            // Leg base layers: right leg v 16..31 u 0..15, left leg v 48..63 u 16..31.
            shown.any { (it / VEIN_SKIN_SIZE in 16..31 && it % VEIN_SKIN_SIZE < 16) ||
                (it / VEIN_SKIN_SIZE >= 48 && it % VEIN_SKIN_SIZE in 16..31) } shouldBe true
            shown.size shouldBeLessThan VEIN_SKIN_SIZE * VEIN_SKIN_SIZE / 2
        }
    }

    @Test
    fun `growth is clamped`() {
        isVeinVisible(VEIN_MAX_ORDER, 2f) shouldBe true
        isVeinVisible(1, -1f) shouldBe false
        isVeinVisible(0, 1f) shouldBe false
    }
}
