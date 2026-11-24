package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConstriliarOrderArrivalTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    // The spot of the stuck trapped-exit walk: floor -118, 113, -95, so its top centre.
    private val tx = -117.5
    private val ty = 114.0
    private val tz = -94.5

    private fun floorArrived(px: Double, py: Double, pz: Double, slack: Double = 0.0) =
        ConstriliarOrderArrival.arrived(px, py, pz, tx, ty, tz, null, slack)

    @Test
    fun `a body standing on the spot arrives`() {
        floorArrived(tx, ty, tz) shouldBe true
        floorArrived(tx + 0.5, ty + 0.2, tz - 0.5) shouldBe true
    }

    @Test
    fun `the goal node point arrives but a body riding a block over it does not`() {
        // goalArrives tests the node centre less half a block: exactly the spot.
        floorArrived(tx, ty + 0.5 - 0.5, tz) shouldBe true
        // The logged body: rounds to -118, 115, -95 and is still inside the node advance radius of 1.2.
        floorArrived(-118.0, 115.2, -95.0) shouldBe false
    }

    @Test
    fun `slack keeps a swaying body that arrived`() {
        floorArrived(tx + 1.5, ty, tz) shouldBe false
        floorArrived(tx + 1.5, ty, tz, slack = 0.5) shouldBe true
    }

    @Test
    fun `a face order arrives only on the face side and near along it`() {
        val up = Direction.UP
        ConstriliarOrderArrival.arrived(tx, ty + 1.0, tz, tx, ty, tz, up) shouldBe true
        ConstriliarOrderArrival.arrived(tx, ty - 0.2, tz, tx, ty, tz, up) shouldBe false
        ConstriliarOrderArrival.arrived(tx, ty + 2.0, tz, tx, ty, tz, up) shouldBe false
        ConstriliarOrderArrival.arrived(tx + 1.3, ty + 0.5, tz, tx, ty, tz, up) shouldBe false
    }

    @Test
    fun `a used-up route fails only after the grace`() {
        val since = 1000L
        val grace = ConstriliarOrderArrival.SPENT_GRACE_TICKS
        ConstriliarOrderArrival.spentRouteFails(ConstriliarOrderArrival.NOT_SPENT, Long.MAX_VALUE) shouldBe false
        ConstriliarOrderArrival.spentRouteFails(since, since) shouldBe false
        ConstriliarOrderArrival.spentRouteFails(since, since + grace - 1) shouldBe false
        ConstriliarOrderArrival.spentRouteFails(since, since + grace) shouldBe true
    }
}
