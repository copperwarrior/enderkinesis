package org.shipwrights.enderkinesis.client

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CoarsePlanCellsTest {

    @Test
    fun `the factor is the least power of two that fits the edge`() {
        CoarsePlanCells.coarseFactor(64, 64) shouldBe 2
        CoarsePlanCells.coarseFactor(128, 64) shouldBe 2
        CoarsePlanCells.coarseFactor(129, 64) shouldBe 4
        CoarsePlanCells.coarseFactor(4096, 64) shouldBe 64
        CoarsePlanCells.coarseFactor(Int.MAX_VALUE / 2, 1) shouldBe (1 shl 30)
    }

    @Test
    fun `cells cover the plan and stop at the edge`() {
        val cells = CoarsePlanCells<String>(300, 20, 100, 64)
        cells.factor shouldBe 8
        cells.countX shouldBe 38
        cells.countY shouldBe 3
        cells.countZ shouldBe 13
        cells.get(38, 0, 0) shouldBe null
        cells.get(-1, 0, 0) shouldBe null
    }

    @Test
    fun `a cell keeps the last block put into it`() {
        val cells = CoarsePlanCells<String>(16, 16, 16, 4)
        cells.factor shouldBe 4
        cells.put(1, 0, 1, "floor")
        cells.put(2, 3, 0, "roof")
        cells.get(0, 0, 0) shouldBe "roof"
        cells.put(5, 0, 0, "other")
        cells.get(1, 0, 0) shouldBe "other"
        cells.get(0, 1, 0) shouldBe null
    }

    @Test
    fun `an empty plan size is refused`() {
        assertThrows<IllegalArgumentException> { CoarsePlanCells<String>(0, 1, 1, 64) }
    }
}
