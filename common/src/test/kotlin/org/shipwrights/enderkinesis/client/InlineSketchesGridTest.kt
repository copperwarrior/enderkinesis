package org.shipwrights.enderkinesis.client

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InlineSketchesGridTest {

    @Test
    fun `a full grid fills the tag square around the page centre`() {
        val grid = InlineSketches.gridGeometry(36, 126)
        grid.cell shouldBe 21
        grid.left shouldBe -62
        grid.top shouldBe 0
        grid.width shouldBe 126
        grid.height shouldBe 126
    }

    @Test
    fun `a short last row centres under the page centre`() {
        val grid = InlineSketches.gridGeometry(5, 120)
        grid.xs.toList().subList(3, 5) shouldBe listOf(-40, 0)
    }

    @Test
    fun `cells stay in the box and do not overlap`() {
        for (size in 16..256 step 10) {
            for (count in 1..36) {
                val grid = InlineSketches.gridGeometry(count, size)
                val cell = grid.cell
                for (i in 0 until count) {
                    grid.xs[i] shouldBeGreaterThanOrEqual grid.left
                    grid.ys[i] shouldBeGreaterThanOrEqual grid.top
                    grid.xs[i] + cell shouldBeLessThanOrEqual grid.left + grid.width
                    grid.ys[i] + cell shouldBeLessThanOrEqual grid.top + grid.height
                    for (j in 0 until i) {
                        val apart = grid.xs[i] - grid.xs[j] >= cell || grid.xs[j] - grid.xs[i] >= cell ||
                            grid.ys[i] - grid.ys[j] >= cell || grid.ys[j] - grid.ys[i] >= cell
                        apart shouldBe true
                    }
                }
            }
        }
    }
}
