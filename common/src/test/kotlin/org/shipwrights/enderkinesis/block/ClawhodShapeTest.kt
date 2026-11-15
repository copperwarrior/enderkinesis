package org.shipwrights.enderkinesis.block

import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClawhodShapeTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    // VS2 MassDatapackResolver.generateShapeFromVoxel gives a full cube past 20 boxes or outside the cell.
    @Test
    fun `every facing keeps a shape that VS2 can use for ship collision`() {
        for (facing in Direction.values()) {
            var boxes = 0
            var inCell = true
            ClawhodBlock.shapeFor(facing).forAllBoxes { x0, y0, z0, x1, y1, z1 ->
                boxes++
                if (minOf(x0, y0, z0) < 0.0 || maxOf(x1, y1, z1) > 1.0) inCell = false
            }
            boxes shouldBeInRange 1..MAX_VS2_BOXES
            inCell shouldBe true
        }
    }

    @Test
    fun `the column points along its facing`() {
        val up = ClawhodBlock.shapeFor(Direction.UP).bounds()
        up.minY shouldBe 0.0
        up.maxY shouldBe 13.0 / 16.0
        val down = ClawhodBlock.shapeFor(Direction.DOWN).bounds()
        down.minY shouldBe 3.0 / 16.0
        down.maxY shouldBe 1.0
        val east = ClawhodBlock.shapeFor(Direction.EAST).bounds()
        east.minX shouldBe 0.0
        east.maxX shouldBe 13.0 / 16.0
    }

    private companion object {
        const val MAX_VS2_BOXES = 20
    }
}
