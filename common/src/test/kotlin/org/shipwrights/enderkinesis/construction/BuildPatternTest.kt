package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

class BuildPatternTest {

    private fun walk(pattern: BuildPattern, sx: Int, sz: Int, seed: Long = 7L): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>()
        for (z in 0 until sz) for (x in 0 until sx) cells.add(x to z)
        return cells.sortedBy { (x, z) -> pattern.key(x, z, sx, sz, seed) }
    }

    @Test
    fun `every pattern gives each column its own key`() {
        for (pattern in BuildPattern.entries) {
            val keys = walk(pattern, 13, 9).map { (x, z) -> pattern.key(x, z, 13, 9, 7L) }
            keys.toSet().size shouldBe 13 * 9
        }
    }

    @Test
    fun `scan goes along x, then z`() {
        walk(BuildPattern.SCAN, 3, 2) shouldContainExactly listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1)
    }

    @Test
    fun `the inward circle walks the outer ring clockwise before the inner one`() {
        walk(BuildPattern.INWARD_CIRCLE, 3, 3) shouldContainExactly listOf(
            0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2, 1 to 2, 0 to 2, 0 to 1, 1 to 1,
        )
    }

    @Test
    fun `each step of the inward circle goes to a neighbour inside a ring`() {
        val order = walk(BuildPattern.INWARD_CIRCLE, 7, 5)
        val ring = { c: Pair<Int, Int> -> minOf(minOf(c.first, c.second), minOf(6 - c.first, 4 - c.second)) }
        for (i in 1 until order.size) {
            val a = order[i - 1]
            val b = order[i]
            if (ring(a) != ring(b)) continue
            (abs(a.first - b.first) + abs(a.second - b.second)) shouldBe 1
        }
    }

    @Test
    fun `each step of the Hilbert curve goes to a neighbour on a square of side two to the n`() {
        val order = walk(BuildPattern.HILBERT, 16, 16)
        for (i in 1 until order.size) {
            (abs(order[i].first - order[i - 1].first) + abs(order[i].second - order[i - 1].second)) shouldBe 1
        }
    }

    @Test
    fun `the random pattern is fixed for one seed and differs for another`() {
        walk(BuildPattern.RANDOM, 8, 8, seed = 1L) shouldBe walk(BuildPattern.RANDOM, 8, 8, seed = 1L)
        (walk(BuildPattern.RANDOM, 8, 8, seed = 1L) == walk(BuildPattern.RANDOM, 8, 8, seed = 2L)) shouldBe false
    }

    @Test
    fun `column ranks are a permutation, with columns outside the plan last`() {
        val rank = columnRanks(BuildPattern.SCAN, 0, 0, 2, 2, 0L)
        rank.toSet().size shouldBe SECTION_COLUMNS
        rank[0] shouldBe 0
        rank[1] shouldBe 1
        rank[16] shouldBe 2
        rank[17] shouldBe 3
        (rank[2] >= 4) shouldBe true
    }

    @Test
    fun `column ranks follow plan-wide keys in a section away from the origin`() {
        val rank = columnRanks(BuildPattern.SCAN, 16, 16, 40, 40, 0L)
        rank[0] shouldBe 0
        rank[15] shouldBe 15
        rank[16] shouldBe 16
    }

    @Test
    fun `a pattern id from a save always maps to a pattern`() {
        BuildPattern.byId(1) shouldBe BuildPattern.INWARD_CIRCLE
        BuildPattern.byId(-1) shouldBe BuildPattern.RANDOM
    }
}
