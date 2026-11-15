package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ConstriliarCargoShapeTest {

    private fun shape(vararg blocks: IntArray): ConstriliarCargoShape = ConstriliarCargoShape().apply {
        begin()
        for (b in blocks) add(b[0], b[1], b[2])
        finish()
    }

    @Test
    fun `a bare plate keeps one layer of headroom in its own column`() {
        val s = shape()
        s.height shouldBe 2
        s.radius shouldBe 0
        s.radiusAt(0) shouldBe -1
        s.radiusAt(1) shouldBe 0
        s.radiusAt(2) shouldBe -1
    }

    @Test
    fun `a chest and a clawhod stacked on the plate make the body three layers tall`() {
        val s = shape(intArrayOf(0, 1, 0), intArrayOf(0, 2, 0))
        s.height shouldBe 3
        s.blockAbove shouldBe true
        s.radiusAt(1) shouldBe 0
        s.radiusAt(2) shouldBe 0
    }

    @Test
    fun `each layer keeps its own reach`() {
        val s = shape(intArrayOf(1, 0, 0), intArrayOf(0, 1, -2), intArrayOf(0, 2, 0))
        s.radius shouldBe 2
        s.radiusAt(0) shouldBe 1
        s.radiusAt(1) shouldBe 2
        s.radiusAt(2) shouldBe 0
    }

    @Test
    fun `reach is capped and load below the plate is ignored`() {
        val s = shape(intArrayOf(9, 1, 0), intArrayOf(0, -1, 3))
        s.radiusAt(1) shouldBe ConstriliarCargoShape.MAX_REACH
        s.radiusAt(0) shouldBe -1
    }
}
