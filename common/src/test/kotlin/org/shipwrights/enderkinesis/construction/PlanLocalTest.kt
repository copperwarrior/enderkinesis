package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanLocalTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `the packed plan-local x and z match cellLocalPos for every turn and mirror`() {
        val origin = BlockPos(100, 64, -40)
        for (rotation in Rotation.entries) {
            for (mirror in Mirror.entries) {
                val anchor = PlanAnchor(Level.OVERWORLD, origin, rotation, mirror)
                for (dx in -3..3) {
                    for (dz in -3..3) {
                        val world = origin.offset(dx, 2, dz)
                        val want = anchor.cellLocalPos(world)
                        val got = planLocal(anchor, world.x, world.z)
                        (planLocalX(got) to planLocalZ(got)) shouldBe (want.x to want.z)
                        want.y shouldBe 2
                    }
                }
            }
        }
    }
}
