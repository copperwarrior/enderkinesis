package org.shipwrights.enderkinesis.block

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.joml.Vector3d
import org.joml.primitives.AABBi
import org.junit.jupiter.api.Test

class QuiescentCradleFrameTest {

    private val facings = listOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

    private fun Vector3d.shouldBeAt(x: Double, y: Double, z: Double) {
        this.x shouldBe (x plusOrMinus EPS)
        this.y shouldBe (y plusOrMinus EPS)
        this.z shouldBe (z plusOrMinus EPS)
    }

    // A BlockPos is the block's min corner, so the block centre is pos + 0.5 on x and z.
    private fun world(pos: BlockPos, local: Vector3d) = local.add(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())

    @Test
    fun `the block centre stays at the centre for every facing`() {
        for (facing in facings) {
            QuiescentCradleFrame.modelToBlock(facing, 0.5, 0.25, 0.5, Vector3d()).shouldBeAt(0.5, 0.25, 0.5)
        }
    }

    @Test
    fun `the orrery sits on the control face for every facing`() {
        val expected = mapOf(
            Direction.NORTH to Vector3d(0.5, 0.75, 0.0625),
            Direction.EAST to Vector3d(0.9375, 0.75, 0.5),
            Direction.SOUTH to Vector3d(0.5, 0.75, 0.9375),
            Direction.WEST to Vector3d(0.0625, 0.75, 0.5),
        )
        for (facing in facings) {
            val e = expected.getValue(facing)
            QuiescentCradleFrame.orreryCentre(facing, Vector3d()).shouldBeAt(e.x, e.y, e.z)
        }
    }

    @Test
    fun `the hold centre is above the top gap, behind the control face, for every facing`() {
        val expected = mapOf(
            Direction.NORTH to Vector3d(0.5, 1.2, 0.65625),
            Direction.EAST to Vector3d(0.34375, 1.2, 0.5),
            Direction.SOUTH to Vector3d(0.5, 1.2, 0.34375),
            Direction.WEST to Vector3d(0.65625, 1.2, 0.5),
        )
        for (facing in facings) {
            val e = expected.getValue(facing)
            QuiescentCradleFrame.holdCentre(facing, 0.0, Vector3d()).shouldBeAt(e.x, e.y, e.z)
        }
    }

    @Test
    fun `world points add the corner block position`() {
        // The playtest log: a cradle at (6, -60, -9) facing east with reach 29.5 gave (6.34375, -29.3, -8.5).
        world(BlockPos(6, -60, -9), QuiescentCradleFrame.holdCentre(Direction.EAST, 29.5, Vector3d()))
            .shouldBeAt(6.34375, -29.3, -8.5)
    }

    @Test
    fun `ship box centre and sphere use the exclusive shipAABB maximum`() {
        // A Roaming End ship: blocks 0..12, 0..23, 0..28, so shipAABB is (0, 0, 0) to (13, 24, 29).
        val box = AABBi(0, 0, 0, 13, 24, 29)
        val centre = QuiescentCradleFrame.boxCentre(box, Vector3d())
        centre.shouldBeAt(6.5, 12.0, 14.5)
        QuiescentCradleFrame.sphereAround(box, centre) shouldBe (0.5 * Math.sqrt(1586.0) plusOrMinus EPS)
    }

    private companion object {
        const val EPS = 1e-9
    }
}
