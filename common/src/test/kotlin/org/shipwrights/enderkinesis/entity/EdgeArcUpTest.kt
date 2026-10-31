package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.junit.jupiter.api.Test

class EdgeArcUpTest {

    private val out = Vector3d()

    private fun up(a: ConstriliarSurfaceNavigator.Node, b: ConstriliarSurfaceNavigator.Node, concave: Boolean, x: Double, y: Double) =
        ConstriliarSurfaceNavigator.edgeArcUp(a, b, concave, Vec3(x, y, 0.5), out).let { Vector3d(out) }

    // Floor node on a block at the origin, wrapping east over its top edge onto its east face.
    private val ledgeTop = ConstriliarSurfaceNavigator.Node(BlockPos(0, 1, 0), Direction.DOWN)
    private val ledgeSide = ConstriliarSurfaceNavigator.Node(BlockPos(1, 0, 0), Direction.WEST)

    // Floor node meeting a wall to the east in the same cell.
    private val floor = ConstriliarSurfaceNavigator.Node(BlockPos(0, 1, 0), Direction.DOWN)
    private val wall = ConstriliarSurfaceNavigator.Node(BlockPos(0, 1, 0), Direction.EAST)

    @Test
    fun `convex edge keeps the old up before the edge and the new up past it`() {
        up(ledgeTop, ledgeSide, false, 0.5, 1.75).y shouldBe (1.0 plusOrMinus 1e-9)
        up(ledgeTop, ledgeSide, false, 1.75, 0.5).x shouldBe (1.0 plusOrMinus 1e-9)
    }

    @Test
    fun `convex edge turns monotonically round the edge`() {
        var lastX = -1.0
        for (k in 0..20) {
            val a = Math.PI / 2 * k / 20
            val u = up(ledgeTop, ledgeSide, false, 1.0 + 0.75 * Math.sin(a), 1.0 + 0.75 * Math.cos(a))
            u.x shouldBeGreaterThanOrEqual lastX
            u.length() shouldBe (1.0 plusOrMinus 1e-9)
            lastX = u.x
        }
    }

    @Test
    fun `concave corner turns from floor to wall up`() {
        up(floor, wall, true, -3.0, 1.75).y shouldBe (1.0 plusOrMinus 1e-9)
        up(floor, wall, true, 0.25, 4.0).x shouldBe (-1.0 plusOrMinus 1e-9)
        // Arc centre (-0.25, 2.25), radius 0.5: the 45° point.
        val r = 0.5 * Math.sqrt(0.5)
        val mid = up(floor, wall, true, -0.25 + r, 2.25 - r)
        mid.x shouldBe (-Math.sqrt(0.5) plusOrMinus 1e-9)
        mid.y shouldBe (Math.sqrt(0.5) plusOrMinus 1e-9)
    }
}
