package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConstriliarWorkSpotFootingTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    // Packed as computeStance packs it: legs * 16 + ring, SPREAD 256, grips * 512, foothold legs * 4096, beam 32768.
    private fun stance(legs: Int, ring: Int, grips: Int, footLegs: Int, beam: Boolean): Int =
        legs * 16 + ring + 256 + grips * 512 + footLegs * 4096 + if (beam) 32768 else 0

    @Test
    fun `ground with footholds in three or four quadrants holds work`() {
        ConstriliarSurfaceNavigator.workSpotFooting(stance(4, 8, 0, 4, beam = false)) shouldBe true
        ConstriliarSurfaceNavigator.workSpotFooting(stance(4, 5, 1, 3, beam = false)) shouldBe true
    }

    @Test
    fun `a straddle on a beam or column holds work, with feet or grips in three quadrants`() {
        ConstriliarSurfaceNavigator.workSpotFooting(stance(4, 2, 2, 4, beam = true)) shouldBe true
        ConstriliarSurfaceNavigator.workSpotFooting(stance(4, 0, 4, 0, beam = true)) shouldBe true
        ConstriliarSurfaceNavigator.workSpotFooting(stance(4, 3, 2, 2, beam = false)) shouldBe true
    }

    @Test
    fun `feet and grips in only two quadrants do not hold work`() {
        ConstriliarSurfaceNavigator.workSpotFooting(stance(2, 2, 0, 2, beam = false)) shouldBe false
        ConstriliarSurfaceNavigator.workSpotFooting(stance(2, 0, 2, 0, beam = true)) shouldBe false
    }

    @Test
    fun `a stance that was not read does not block`() {
        ConstriliarSurfaceNavigator.workSpotFooting(-1) shouldBe true
    }
}
