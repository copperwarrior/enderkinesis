package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import org.junit.jupiter.api.Test

class TablePlacementTest {

    private fun at(x: Int, y: Int, z: Int) = TablePlacement(Vec3i(x, y, z), Rotation.NONE, Mirror.NONE)

    @Test
    fun `the plan centre may be at most one and a half radii from the table`() {
        // 20 wide: reach 15. Base x 14 puts the centre 14.5 out in x and 0.5 in z.
        val size = Vec3i(20, 1, 20)
        at(14, 0, 0).inReach(size) shouldBe true
        at(15, 0, 0).inReach(size) shouldBe false
    }

    @Test
    fun `the reach is at least eight blocks`() {
        // 10 wide: 1.5 radii is 7.5, so the 8 block minimum decides. Base x 7 puts the centre 7.5 out, x 8 puts it 8.5 out.
        val size = Vec3i(10, 1, 10)
        at(7, 0, 0).inReach(size) shouldBe true
        at(8, 0, 0).inReach(size) shouldBe false
        at(0, 7, 0).inReach(Vec3i(1, 1, 1)) shouldBe true
    }

    @Test
    fun `the offset goes to the bottom centre cell`() {
        val size = Vec3i(3, 2, 3)
        val box = at(5, 1, 0).box(BlockPos(100, 64, 100), size)
        box.minY() shouldBe 65
        box.isInside(BlockPos(105, 65, 100)) shouldBe true
    }

    @Test
    fun `the first placement is in reach and clear of the table`() {
        for (size in listOf(Vec3i(10, 5, 10), Vec3i(3, 20, 3), Vec3i(10, 1, 10), Vec3i(2, 1, 2), Vec3i(1, 1, 1), Vec3i(2, 2, 2), Vec3i(64, 16, 8))) {
            for (front in Direction.Plane.HORIZONTAL) {
                val p = TablePlacement.initial(size, front)
                p shouldNotBe null
                checkNotNull(p).inReach(size) shouldBe true
                p.covers(size) shouldBe false
            }
        }
    }

    @Test
    fun `turning keeps the largest side, so reach does not change for a square plan`() {
        val size = Vec3i(6, 3, 6)
        for (rotation in Rotation.entries) {
            TablePlacement(Vec3i(4, 0, 0), rotation, Mirror.NONE).inReach(size) shouldBe at(4, 0, 0).inReach(size)
        }
    }
}
