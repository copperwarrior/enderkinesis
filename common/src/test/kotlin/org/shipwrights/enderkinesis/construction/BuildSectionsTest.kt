package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import org.junit.jupiter.api.Test

class BuildSectionsTest {

    private fun p(x: Int, y: Int, z: Int) = BlockPos.asLong(x, y, z)

    private fun round(grid: PlanGrid): List<Int> {
        val sweep = SectionSweep(grid)
        return List(grid.count) { sweep.next() }
    }

    @Test
    fun `local index matches the paletted container order`() {
        planCellLocal(1, 0, 0) shouldBe 1
        planCellLocal(0, 0, 1) shouldBe 16
        planCellLocal(0, 1, 0) shouldBe 256
        val l = planCellLocal(7, 11, 3)
        listOf(planCellLocalX(l), planCellLocalY(l), planCellLocalZ(l)) shouldContainExactly listOf(7, 11, 3)
    }

    @Test
    fun `one sweep round visits every section once, bottom layer first`() {
        val grid = PlanGrid(Vec3i(70, 40, 33))
        val order = round(grid)
        order.toSet().size shouldBe grid.count
        order.map { grid.sectionY(it) } shouldBe order.map { grid.sectionY(it) }.sorted()
    }

    @Test
    fun `in a layer the sweep goes out in rings from the anchor section`() {
        val grid = PlanGrid(Vec3i(48, 16, 48))
        val rings = round(grid).map { maxOf(grid.sectionX(it), grid.sectionZ(it)) }
        rings shouldBe rings.sorted()
        rings.first() shouldBe 0
    }

    @Test
    fun `the sweep wraps to the bottom after the top layer`() {
        val grid = PlanGrid(Vec3i(16, 32, 16))
        val sweep = SectionSweep(grid)
        sweep.next() shouldBe grid.index(0, 0, 0)
        sweep.next() shouldBe grid.index(0, 1, 0)
        sweep.wrapped shouldBe false
        sweep.next() shouldBe grid.index(0, 0, 0)
        sweep.wrapped shouldBe true
    }

    @Test
    fun `a top-down sweep visits every section once, top layer first, and wraps to the top`() {
        val grid = PlanGrid(Vec3i(40, 48, 20))
        val sweep = SectionSweep(grid, topDown = true)
        val order = List(grid.count) { sweep.next() }
        order.toSet().size shouldBe grid.count
        order.map { grid.sectionY(it) } shouldBe order.map { grid.sectionY(it) }.sortedDescending()
        sweep.wrapped shouldBe false
        sweep.next() shouldBe grid.index(0, grid.y - 1, 0)
        sweep.wrapped shouldBe true
    }

    @Test
    fun `a long thin grid sweeps in bounded steps`() {
        val grid = PlanGrid(Vec3i(16, 16, 16 * 5000))
        round(grid).toSet().size shouldBe grid.count
    }

    @Test
    fun `progress saves complete sections as bits and partial ones as done cells`() {
        val grid = PlanGrid(Vec3i(64, 64, 64))
        val progress = SectionProgress(grid)
        progress.doneBits(3).setBit(planCellLocal(1, 2, 3))
        progress.doneBits(9)
        progress.markComplete(5)
        progress.markComplete(40)
        val loaded = SectionProgress(grid).also { it.load(progress.save()) }
        loaded.completeCount shouldBe 2
        loaded.isComplete(5) shouldBe true
        loaded.isComplete(40) shouldBe true
        loaded.partialSections().toList() shouldContainExactly listOf(3)
        checkNotNull(loaded.partialOrNull(3)).bit(planCellLocal(1, 2, 3)) shouldBe true
    }

    @Test
    fun `completing a section drops its cell bits`() {
        val progress = SectionProgress(PlanGrid(Vec3i(32, 16, 16)))
        progress.doneBits(1).setBit(0)
        progress.markComplete(1)
        progress.partialOrNull(1) shouldBe null
        progress.completeCount shouldBe 1
    }

    /**
     * Builds a plan section by section the way a job does: each section's order sees only the world, which holds
     * the ground (y < 0) and every cell of the sections built before it.
     */
    private fun buildBySections(grid: PlanGrid, cells: List<Long>): List<Long> {
        val bySection = cells.groupBy {
            grid.index(BlockPos.getX(it) shr 4, BlockPos.getY(it) shr 4, BlockPos.getZ(it) shr 4)
        }
        val world = LongOpenHashSet()
        val built = ArrayList<Long>()
        val sweep = SectionSweep(grid)
        repeat(grid.count) {
            val section = bySection[sweep.next()] ?: return@repeat
            val positions = section.toLongArray()
            val core = BuildOrderCore(positions, ByteArray(positions.size) { CellKind.SOLID }, IntArray(positions.size)) {
                BlockPos.getY(it) < 0 || world.contains(it)
            }
            var steps = 0
            while (!core.step(PLAN_SECTION_CELLS)) check(++steps < 10_000) { "order did not finish" }
            for (i in core.result()) {
                world.add(positions[i])
                built.add(positions[i])
            }
        }
        return built
    }

    @Test
    fun `a plan many sections in size builds every cell once and layer by layer`() {
        val grid = PlanGrid(Vec3i(80, 48, 64))
        val cells = ArrayList<Long>()
        for (x in 0 until 80) for (y in 0 until 48) for (z in 0 until 64) {
            if ((x * 7 + y * 13 + z * 3) % 4 != 0) cells.add(p(x, y, z))
        }
        val built = buildBySections(grid, cells)
        built.size shouldBe cells.size
        LongOpenHashSet(built.toLongArray()).size shouldBe cells.size
        val layers = built.map { BlockPos.getY(it) shr 4 }
        layers shouldBe layers.sorted()
    }

    @Test
    fun `a finished section below seeds the section above, and its layer ends before the next`() {
        // Lower section: one block under the column. Upper section: a column on it and a floating block in the
        // column's first layer, which comes after the supported cell but before the layer above.
        val grid = PlanGrid(Vec3i(16, 32, 16))
        val base = p(0, 15, 0)
        val column = listOf(p(0, 16, 0), p(0, 17, 0), p(0, 18, 0), p(0, 19, 0))
        val floating = p(5, 16, 5)
        val lower = (0 until 15).map { p(0, it, 0) } + base
        val built = buildBySections(grid, lower + floating + column)
        built.drop(lower.size) shouldContainExactly listOf(column[0], floating) + column.drop(1)
    }
}
