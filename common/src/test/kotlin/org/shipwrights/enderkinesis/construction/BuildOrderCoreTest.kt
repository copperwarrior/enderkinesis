package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Test

class BuildOrderCoreTest {

    private fun p(x: Int, y: Int, z: Int) = BlockPos.asLong(x, y, z)

    private fun order(
        cells: List<Pair<Long, Byte>>,
        rank: IntArray = IntArray(cells.size),
        budget: Int = 1,
        ground: (Long) -> Boolean = { BlockPos.getY(it) < 0 },
    ): List<Int> {
        val core = BuildOrderCore(
            LongArray(cells.size) { cells[it].first },
            ByteArray(cells.size) { cells[it].second },
            rank,
        ) { ground(it) }
        var steps = 0
        while (!core.step(budget)) {
            steps++
            check(steps < 100_000) { "order did not finish" }
        }
        return core.result().toList()
    }

    @Test
    fun `a column builds from the ground up even when listed top first`() {
        val cells = listOf(p(0, 2, 0) to CellKind.SOLID, p(0, 1, 0) to CellKind.SOLID, p(0, 0, 0) to CellKind.SOLID)
        order(cells) shouldContainExactly listOf(2, 1, 0)
    }

    @Test
    fun `skipped cells are not tasks`() {
        val cells = listOf(p(0, 0, 0) to CellKind.SOLID, p(0, 1, 0) to CellKind.SKIP)
        order(cells) shouldContainExactly listOf(0)
    }

    @Test
    fun `attached blocks come after every solid`() {
        val cells = listOf(
            p(0, 0, 0) to CellKind.ATTACHED,
            p(1, 0, 0) to CellKind.SOLID,
            p(1, 5, 0) to CellKind.SOLID,
        )
        order(cells).last() shouldBe 0
    }

    @Test
    fun `weak blocks come after solids and before attached blocks`() {
        val cells = listOf(
            p(0, 1, 0) to CellKind.ATTACHED,
            p(0, 0, 0) to CellKind.WEAK,
            p(2, 0, 0) to CellKind.SOLID,
        )
        order(cells) shouldContainExactly listOf(2, 1, 0)
    }

    @Test
    fun `a floating solid comes after the supported ones`() {
        val cells = listOf(
            p(5, 3, 5) to CellKind.SOLID,
            p(0, 0, 0) to CellKind.SOLID,
            p(0, 1, 0) to CellKind.SOLID,
        )
        order(cells) shouldContainExactly listOf(1, 2, 0)
    }

    @Test
    fun `seeds in one layer go by the worker's pattern rank`() {
        val cells = listOf(p(3, 0, 0) to CellKind.SOLID, p(1, 0, 0) to CellKind.SOLID, p(-2, 0, 0) to CellKind.SOLID)
        order(cells, rank = intArrayOf(2, 0, 1), budget = 64) shouldContainExactly listOf(1, 2, 0)
    }

    @Test
    fun `the pattern rank never puts a layer before the one below`() {
        val cells = listOf(p(0, 1, 0) to CellKind.SOLID, p(1, 0, 0) to CellKind.SOLID)
        order(cells, rank = intArrayOf(0, 255)) shouldContainExactly listOf(1, 0)
    }

    @Test
    fun `growth spreads from a wall into an overhang`() {
        // Only the cell beside the wall at x = -1 touches the world.
        val wall: (Long) -> Boolean = { BlockPos.getX(it) == -1 }
        val cells = listOf(p(2, 4, 0) to CellKind.SOLID, p(1, 4, 0) to CellKind.SOLID, p(0, 4, 0) to CellKind.SOLID)
        order(cells, ground = wall) shouldContainExactly listOf(2, 1, 0)
    }

    @Test
    fun `plan cells never count as world support`() {
        // The probe calls the lower cell solid, but it is a plan cell, so the upper one is no seed.
        val cells = listOf(p(0, 1, 0) to CellKind.SOLID, p(0, 0, 0) to CellKind.SOLID)
        order(cells, ground = { it == p(0, 0, 0) }) shouldContainExactly listOf(1, 0)
    }

    @Test
    fun `every task appears once and the slice size does not change the order`() {
        val cells = ArrayList<Pair<Long, Byte>>()
        for (x in 0 until 6) for (y in 0 until 5) for (z in 0 until 6) {
            val kind = when ((x + y * 3 + z * 7) % 5) {
                0 -> CellKind.SKIP
                1 -> CellKind.ATTACHED
                2 -> CellKind.WEAK
                else -> CellKind.SOLID
            }
            cells.add(p(x, y, z) to kind)
        }
        val small = order(cells, budget = 1)
        val large = order(cells, budget = 100_000)
        small shouldContainExactly large
        small.toSet().size shouldBe small.size
        small.size shouldBe cells.count { it.second != CellKind.SKIP }
    }

    @Test
    fun `an empty plan finishes with no tasks`() {
        order(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `a layer finishes before the next one starts`() {
        // Only x < 3 stands on ground, so the cell at x = 5 floats in layer 0; it still comes before layer 1.
        val ground: (Long) -> Boolean = { BlockPos.getY(it) < 0 && BlockPos.getX(it) < 3 }
        val cells = listOf(p(0, 1, 0) to CellKind.SOLID, p(5, 0, 0) to CellKind.SOLID, p(0, 0, 0) to CellKind.SOLID)
        order(cells, ground = ground) shouldContainExactly listOf(2, 1, 0)
    }

    @Test
    fun `growth in a layer stays in that layer`() {
        // The cell at x = 1 grows from its neighbour in layer 0; the one above waits for layer 1.
        val ground: (Long) -> Boolean = { BlockPos.getY(it) < 0 && BlockPos.getX(it) == 0 }
        val cells = listOf(p(1, 1, 0) to CellKind.SOLID, p(1, 0, 0) to CellKind.SOLID, p(0, 0, 0) to CellKind.SOLID)
        order(cells, ground = ground) shouldContainExactly listOf(2, 1, 0)
    }
}
