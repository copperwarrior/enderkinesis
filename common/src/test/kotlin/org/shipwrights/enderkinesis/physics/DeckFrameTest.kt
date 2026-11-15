package org.shipwrights.enderkinesis.physics

import org.joml.Matrix4d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeckFrameTest {

    private fun shipToWorld(position: Vector3d, rotation: Quaterniond, shipyardOrigin: Vector3d): Matrix4d =
        Matrix4d().translation(position).rotate(rotation).translate(-shipyardOrigin.x, -shipyardOrigin.y, -shipyardOrigin.z)

    private fun assertVec(expected: Vector3d, actual: Vector3d, eps: Double = 1.0e-9) {
        assertEquals(expected.x, actual.x, eps, "x of $actual")
        assertEquals(expected.y, actual.y, eps, "y of $actual")
        assertEquals(expected.z, actual.z, eps, "z of $actual")
    }

    @Test
    fun `a point fixed to the deck follows the deck's move and turn`() {
        val origin = Vector3d(1.2e7, 64.0, 1.2e7)
        val thenRot = Quaterniond().rotationXYZ(0.05, 0.3, -0.02)
        val nowRot = Quaterniond().rotationXYZ(0.08, 0.45, 0.01)
        val then = shipToWorld(Vector3d(100.0, 70.0, -40.0), thenRot, origin)
        val now = shipToWorld(Vector3d(101.5, 69.6, -39.2), nowRot, origin)
        val deckPoint = Vector3d(origin).add(3.0, 1.0, -2.0)
        val worldThen = then.transformPosition(Vector3d(deckPoint))
        val delta = deckMotion(now, Matrix4d(then).invert(), Matrix4d())
        assertVec(now.transformPosition(Vector3d(deckPoint)), delta.transformPosition(worldThen), 1.0e-6)
    }

    @Test
    fun `a point carried tick by tick stays on the deck`() {
        // The hard-stall anchor and a standing face target take one step per tick; they must not drift off the deck.
        val origin = Vector3d(-2.0e6, 0.0, 3.0e6)
        val deckPoint = Vector3d(origin).add(-4.0, 2.0, 1.5)
        var prev = shipToWorld(Vector3d(0.0, 64.0, 0.0), Quaterniond(), origin)
        val carried = prev.transformPosition(Vector3d(deckPoint))
        val step = Matrix4d()
        for (tick in 1..200) {
            val t = tick * 0.05
            val now = shipToWorld(
                Vector3d(3.0 * t, 64.0 + 0.4 * Math.sin(t), -1.5 * t),
                Quaterniond().rotationXYZ(0.1 * Math.sin(t), 0.3 * t, 0.05 * Math.cos(t)),
                origin,
            )
            deckMotion(now, Matrix4d(prev).invert(), step).transformPosition(carried)
            prev = now
        }
        assertVec(prev.transformPosition(Vector3d(deckPoint)), carried, 1.0e-5)
    }

    @Test
    fun `a still deck leaves every point in place`() {
        val pose = shipToWorld(Vector3d(5.0, 64.0, 5.0), Quaterniond().rotationY(1.0), Vector3d(1.0e6, 0.0, 1.0e6))
        val delta = deckMotion(pose, Matrix4d(pose).invert(), Matrix4d())
        val p = Vector3d(7.0, 66.0, -3.0)
        assertVec(p, delta.transformPosition(Vector3d(p)), 1.0e-6)
    }

    @Test
    fun `the deck turn carries the old rotation onto the new one`() {
        val thenRot = Quaterniond().rotationXYZ(0.1, -0.7, 0.2)
        val nowRot = Quaterniond().rotationXYZ(-0.05, 0.4, 0.3)
        val turn = deckTurn(nowRot, thenRot, Quaterniond())
        val carried = turn.mul(thenRot, Quaterniond())
        assertEquals(1.0, Math.abs(carried.dot(nowRot)), 1.0e-12)
    }

    @Test
    fun `a heading turned by the deck yaw step matches the deck's turn`() {
        // Body pose is rotationY(-yaw) (MC yaw, degrees); the deck turn about +Y must give the same pose.
        val yaw = 37.0
        val turn = Quaterniond().rotationY(0.03)
        val spun = Quaterniond(turn).mul(Quaterniond().rotationY(-Math.toRadians(yaw)))
        val step = deckTwistYawDegrees(turn, Vector3d(0.0, 1.0, 0.0)).toDouble()
        val stepped = Quaterniond().rotationY(-Math.toRadians(yaw + step))
        assertEquals(1.0, Math.abs(spun.dot(stepped)), 1.0e-9)
    }

    @Test
    fun `a turn about a tilted up gives its angle with MC yaw sign`() {
        val up = Vector3d(0.3, 1.0, -0.2).normalize()
        val swingAxis = Vector3d(up).cross(1.0, 0.0, 0.0).normalize()
        val angle = 0.4
        val turn = Quaterniond().rotationAxis(angle, up.x, up.y, up.z)
            .mul(Quaterniond().rotationAxis(0.25, swingAxis.x, swingAxis.y, swingAxis.z))
        assertEquals(-Math.toDegrees(angle), deckTwistYawDegrees(turn, up).toDouble(), 1.0e-5)
        // A non-unit up and the negated quaternion are the same input.
        val negated = Quaterniond(-turn.x, -turn.y, -turn.z, -turn.w)
        assertEquals(-Math.toDegrees(angle), deckTwistYawDegrees(negated, Vector3d(up).mul(3.0)).toDouble(), 1.0e-5)
    }

    @Test
    fun `deck turn square to up does not turn the heading`() {
        val axis = Vector3d(0.8, 0.0, -0.3).normalize()
        val turn = Quaterniond().rotationAxis(0.5, axis.x, axis.y, axis.z)
        assertEquals(0f, deckTwistYawDegrees(turn, Vector3d(0.0, 1.0, 0.0)), 1.0e-6f)
    }
}
