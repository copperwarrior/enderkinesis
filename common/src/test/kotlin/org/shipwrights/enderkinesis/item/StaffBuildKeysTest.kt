package org.shipwrights.enderkinesis.item

import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import org.junit.jupiter.api.Test

class StaffBuildKeysTest {

    @Test
    fun `mark always shows, save with two corners and clear with any corner`() {
        StaffBuildKeys.wheelKeys(corners = 0) shouldBe listOf(StaffBuildKeys.MARK)
        StaffBuildKeys.wheelKeys(corners = 1) shouldBe listOf(StaffBuildKeys.MARK, StaffBuildKeys.CLEAR)
        StaffBuildKeys.wheelKeys(corners = 2) shouldBe listOf(StaffBuildKeys.MARK, StaffBuildKeys.SAVE, StaffBuildKeys.CLEAR)
    }

    @Test
    fun `capture keys do not collide with block or entity ids`() {
        StaffBuildKeys.ALL.size shouldBe 3
        StaffBuildKeys.ALL.all { it.startsWith("enderkinesis:build/") } shouldBe true
    }

    @Test
    fun `the anchor puts the bottom centre on the base for every pose`() {
        val base = BlockPos(10, 64, -7)
        for (size in listOf(Vec3i(1, 1, 1), Vec3i(4, 3, 4), Vec3i(5, 2, 2), Vec3i(64, 64, 1))) {
            for (rotation in Rotation.entries) {
                for (mirror in Mirror.entries) {
                    val anchor = PlanPlacement.anchorFor(base, size, rotation, mirror)
                    PlanPlacement.baseOf(anchor, size, rotation, mirror) shouldBe base
                    val box = PlanPlacement.box(anchor, size, rotation, mirror)
                    box.minY() shouldBe base.y
                    box.isInside(base) shouldBe true
                }
            }
        }
    }

    @Test
    fun `a square plan keeps its box when turned`() {
        val size = Vec3i(4, 2, 4)
        val base = BlockPos(0, 0, 0)
        val boxes = Rotation.entries.map {
            val box = PlanPlacement.box(PlanPlacement.anchorFor(base, size, it, Mirror.NONE), size, it, Mirror.NONE)
            listOf(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())
        }
        boxes.distinct().size shouldBe 1
    }

    @Test
    fun `four turns and three mirror steps come back`() {
        var rotation = Rotation.NONE
        repeat(4) { rotation = rotation.turned(clockwise = true) }
        rotation shouldBe Rotation.NONE
        Rotation.CLOCKWISE_90.turned(true).turned(false) shouldBe Rotation.CLOCKWISE_90
        var mirror = Mirror.NONE
        repeat(Mirror.entries.size) { mirror = mirror.cycled(forward = true) }
        mirror shouldBe Mirror.NONE
        Mirror.NONE.cycled(forward = false) shouldBe Mirror.entries.last()
    }
}
