package org.shipwrights.enderkinesis.physics

import org.joml.AxisAngle4d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.shipwrights.enderkinesis.util.pointVelocity

class DeckPointVelocityTest {

    private fun assertVec(x: Double, y: Double, z: Double, v: Vector3d, eps: Double = 1.0e-9) {
        assertEquals(x, v.x, eps, "x of $v")
        assertEquals(y, v.y, eps, "y of $v")
        assertEquals(z, v.z, eps, "z of $v")
    }

    @Test
    fun `a deck that only moves gives every point its velocity`() {
        val out = pointVelocity(Vector3d(2.0, 0.0, -1.0), Vector3d(), Vector3d(5.0, 64.0, 5.0), Vector3d(9.0, 65.0, 0.0), Vector3d())
        assertVec(2.0, 0.0, -1.0, out)
    }

    @Test
    fun `a deck that yaws carries a plate off its centre sideways`() {
        // 1 rad/s about +Y, plate 3 m east of the centre of mass: v = ω × r = (0, 0, -3).
        val out = pointVelocity(Vector3d(), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 64.0, 0.0), Vector3d(3.0, 64.0, 0.0), Vector3d())
        assertVec(0.0, 0.0, -3.0, out)
    }

    @Test
    fun `a rolling deck lifts a plate on one side`() {
        // Roll about +Z at 0.5 rad/s, plate 4 m east and 1 m up: ω × r = (-0.5, 2.0, 0), plus the deck drift.
        val out = pointVelocity(
            Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.5), Vector3d(0.0, 60.0, 0.0), Vector3d(4.0, 61.0, 0.0), Vector3d(),
        )
        assertVec(0.5, 2.0, 0.0, out)
    }

    @Test
    fun `the point velocity matches the motion of a rigid deck`() {
        val velocity = Vector3d(0.7, -0.2, 1.1)
        val omega = Vector3d(0.3, -0.8, 0.4)
        val centre = Vector3d(10.0, 70.0, -4.0)
        val point = Vector3d(12.5, 69.0, -1.5)
        val dt = 1.0e-6
        val turn = Quaterniond(AxisAngle4d(omega.length() * dt, Vector3d(omega).normalize()))
        val moved = turn.transform(Vector3d(point).sub(centre)).add(centre).fma(dt, velocity)
        val expected = moved.sub(point).div(dt)
        val out = pointVelocity(velocity, omega, centre, point, Vector3d())
        assertVec(expected.x, expected.y, expected.z, out, 1.0e-5)
    }

    @Test
    fun `the inputs stay unchanged`() {
        val velocity = Vector3d(1.0, 2.0, 3.0)
        val omega = Vector3d(0.0, 1.0, 0.0)
        val centre = Vector3d(0.0, 0.0, 0.0)
        val point = Vector3d(1.0, 0.0, 0.0)
        pointVelocity(velocity, omega, centre, point, Vector3d())
        assertVec(1.0, 2.0, 3.0, velocity)
        assertVec(0.0, 1.0, 0.0, omega)
        assertVec(1.0, 0.0, 0.0, point)
    }
}
