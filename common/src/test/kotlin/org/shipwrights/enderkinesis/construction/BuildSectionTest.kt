package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildSectionTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    /** Plan air everywhere except [blocks] and the structure void cells in [keep]. */
    private fun section(
        vararg blocks: Pair<Int, BlockState>,
        size: Vec3i = Vec3i(16, 16, 16),
        keep: IntArray = IntArray(0),
    ): BuildSection {
        val palette = arrayOf(Blocks.AIR.defaultBlockState(), Blocks.STRUCTURE_VOID.defaultBlockState()) +
            blocks.map { it.second }
        val ids = IntArray(PLAN_SECTION_CELLS)
        for (local in keep) ids[local] = PLAN_KEEP_ID
        blocks.forEachIndexed { i, (local, _) -> ids[local] = PLAN_KEEP_ID + 1 + i }
        val data = PlanSectionData.of(ids)?.let { PlanSection(it, palette) }
        val plan = object : PlanSections {
            override val size = size
            override val grid = PlanGrid(size)
            override val nonAir = (data?.nonAir ?: 0).toLong()
            override val nonEmptyCount = 1
            override fun nonEmptyIndex(i: Int) = 0
            override fun state(index: Int) = PlanSectionState.READY
            override fun sectionAt(index: Int) = data
        }
        val anchor = PlanAnchor(Level.OVERWORLD, BlockPos.ZERO, Rotation.NONE, Mirror.NONE)
        return BuildSection(0, LongArray(SECTION_WORDS), data, BlockPos.ZERO, anchor, plan, 0L)
    }

    @Test
    fun `a waterlogged plan block is built dry`() {
        val wet = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.WATERLOGGED, true)
        val s = section(0 to wet)
        s.states[0] shouldBe Blocks.OAK_STAIRS.defaultBlockState()
    }

    @Test
    fun `only a status change the client can see marks the cell dirty`() {
        val s = section(0 to Blocks.STONE.defaultBlockState())
        s.setStatus(0, CellStatus.DEFERRED)
        s.dirty.isEmpty shouldBe true
        s.setStatus(0, CellStatus.MISSING)
        s.dirty[0] shouldBe true
    }

    @Test
    fun `the missing count follows the cell status`() {
        val s = section(0 to Blocks.STONE.defaultBlockState(), 1 to Blocks.DIRT.defaultBlockState())
        s.setStatus(0, CellStatus.MISSING)
        s.setStatus(1, CellStatus.MISSING)
        s.missing shouldBe 2
        s.setStatus(0, CellStatus.TODO)
        s.missing shouldBe 1
        s.markDone(1)
        s.missing shouldBe 0
    }

    @Test
    fun `a supply change wakes the passes of a section with waiting cells`() {
        val s = section(0 to Blocks.STONE.defaultBlockState())
        val p = s.pass(BuildPattern.HILBERT)
        s.wakeMissing()
        p.wake shouldBe -1
        s.setStatus(0, CellStatus.MISSING)
        s.wakeMissing()
        p.wake shouldBe 0
    }

    @Test
    fun `a section with no plan block has nothing to build but is not complete before CLEAR looks`() {
        val s = section()
        s.remaining shouldBe 0
        s.isComplete shouldBe false
        s.clearClean = true
        s.isComplete shouldBe true
    }

    @Test
    fun `CLEAR looks at every cell of the plan box, top layer first`() {
        val s = section(size = Vec3i(3, 2, 2))
        val clear = s.pass(BuildPattern.SCAN).clear
        clear.size shouldBe 12
        clear.take(6).all { planCellLocalY(it) == 1 } shouldBe true
        clear.drop(6).all { planCellLocalY(it) == 0 } shouldBe true
        clear.take(3).toList() shouldBe listOf(planCellLocal(0, 1, 0), planCellLocal(1, 1, 0), planCellLocal(2, 1, 0))
    }

    @Test
    fun `cells outside the plan size have no world position`() {
        val s = section(size = Vec3i(3, 2, 2))
        s.boxPos[planCellLocal(3, 0, 0)] shouldBe NO_POS
        s.boxPos[planCellLocal(2, 1, 1)] shouldBe BlockPos(2, 1, 1).asLong()
    }

    @Test
    fun `a fluid with no bucket waits like a missing item and shows red`() {
        val s = section()
        s.setLocalStatus(5, CellStatus.NO_BUCKET)
        s.missing shouldBe 1
        CellStatus.toClient(CellStatus.NO_BUCKET) shouldBe BuildCellStatus.BLOCKED
        s.setLocalStatus(5, CellStatus.TODO)
        s.missing shouldBe 0
    }

    @Test
    fun `a pass that changed nothing turns deferred cells into blocked ones`() {
        val s = section(0 to Blocks.STONE.defaultBlockState())
        val p = s.pass(BuildPattern.SCAN)
        s.setStatus(0, CellStatus.DEFERRED)
        s.endPass(p)
        s.status(0) shouldBe CellStatus.BLOCKED
        s.stalled shouldBe true
    }

    @Test
    fun `a pass with a place in it is not stalled`() {
        val s = section(0 to Blocks.STONE.defaultBlockState(), 1 to Blocks.STONE.defaultBlockState())
        val p = s.pass(BuildPattern.SCAN)
        s.markDone(0)
        s.endPass(p)
        s.stalled shouldBe false
        s.remaining shouldBe 1
    }

    @Test
    fun `CLEAR never looks at structure void, and a void cell has no world position`() {
        val void = planCellLocal(1, 1, 0)
        val s = section(size = Vec3i(3, 2, 2), keep = intArrayOf(void))
        val clear = s.pass(BuildPattern.SCAN).clear
        clear.size shouldBe 11
        clear.contains(void) shouldBe false
        s.boxPos[void] shouldBe NO_POS
    }

    @Test
    fun `a section of only structure void has no CLEAR work`() {
        val s = section(size = Vec3i(2, 1, 1), keep = intArrayOf(planCellLocal(0, 0, 0), planCellLocal(1, 0, 0)))
        s.pass(BuildPattern.SCAN).clear.size shouldBe 0
        s.remaining shouldBe 0
    }
}
