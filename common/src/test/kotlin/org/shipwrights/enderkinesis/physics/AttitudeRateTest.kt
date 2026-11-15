package org.shipwrights.enderkinesis.physics

import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The walker's rotation law on a plain rigid body: the controller's own [RotationPd], reference step and gains, with
 * Euler's equations integrated at the physics rate. A jump in the target must turn the body at a bounded spin, and a
 * body that follows a turning target must stay well inside the upset angle.
 */
class AttitudeRateTest {

    private class Body(val inertia: Matrix3d) {
        val rotation = Quaterniond()
        val spin = Vector3d()
        private val inverse = Matrix3d(inertia).invert()
        private val tmp = Vector3d()
        private val momentum = Vector3d()

        /** Euler's equations in the world frame: α = R I⁻¹ Rᵀ (τ − ω × R I Rᵀ ω). */
        fun step(torque: Vector3d, dt: Double) {
            rotation.transformInverse(spin, tmp)
            inertia.transform(tmp)
            rotation.transform(tmp, momentum)
            val net = Vector3d(torque).sub(Vector3d(spin).cross(momentum))
            rotation.transformInverse(net)
            inverse.transform(net)
            rotation.transform(net)
            spin.fma(dt, net)
            val angle = spin.length() * dt
            if (angle > 0.0) {
                rotation.premul(Quaterniond().rotationAxis(angle, spin.x / spin.length(), spin.y / spin.length(), spin.z / spin.length()))
                rotation.normalize()
            }
        }

        fun up(): Vector3d = rotation.transform(Vector3d(0.0, 1.0, 0.0))
    }

    /** The controller's loop: the reference steps toward [command] at the capped rate, and the PD follows it. */
    private class Loop(val body: Body) {
        val ref = Quaterniond(body.rotation)
        private val prev = Quaterniond()
        private val scratch = Quaterniond()
        private val refSpin = Vector3d()
        private val pd = RotationPd()

        fun frame(command: Quaterniond, limited: Boolean) {
            if (limited) {
                prev.set(ref)
                stepRotationToward(ref, command, ROTATION_REF_MAX_RATE * DT)
                spinBetween(prev, ref, DT, scratch, refSpin)
            } else {
                ref.set(command)
                refSpin.zero()
            }
            val torque = pd.torque(
                ref, body.rotation, body.spin, refSpin, body.inertia,
                ConstriliarShipController.ROTATION_STIFFNESS, ConstriliarShipController.ROTATION_DAMPING,
                ConstriliarShipController.LEG_TORQUE, Vector3d(),
            )
            body.step(torque, DT)
        }
    }

    private fun body() = Body(Matrix3d().scaling(INERTIA_X, INERTIA_Y, INERTIA_Z))

    private fun angleDeg(a: Vector3d, b: Vector3d) = Math.toDegrees(Math.acos(a.dot(b).coerceIn(-1.0, 1.0)))

    @Test
    fun `a target that flips upside down turns the body at a bounded spin and settles`() {
        val loop = Loop(body())
        val flipped = Quaterniond().rotationX(Math.PI)
        var maxSpin = 0.0
        repeat(SETTLE_FRAMES) {
            loop.frame(flipped, limited = true)
            maxSpin = maxOf(maxSpin, loop.body.spin.length())
        }
        assertTrue(maxSpin <= ROTATION_REF_MAX_RATE * SPIN_MARGIN) { "peak spin $maxSpin rad/s" }
        assertEquals(-1.0, loop.body.up().y, SETTLED_TOLERANCE)
    }

    /** Worst angle (degrees) between the body's up and the brain's commanded up while the brain turns it over. */
    private fun followFlip(loop: Loop, limited: Boolean): Double {
        val target = Vector3d(0.0, -1.0, 0.0)
        val commandUp = Vector3d(0.0, 1.0, 0.0)
        val forward = Vector3d(0.0, 0.0, 1.0)
        val command = Quaterniond()
        var worst = 0.0
        for (frame in 0 until SETTLE_FRAMES) {
            // The brain writes at 20 Hz; the physics runs three frames per game tick.
            if (frame % FRAMES_PER_TICK == 0) {
                turnUpToward(commandUp, target, ATTITUDE_MAX_STEP, forward)
                command.rotationTo(Vector3d(0.0, 1.0, 0.0), commandUp)
            }
            loop.frame(command, limited)
            worst = maxOf(worst, angleDeg(loop.body.up(), commandUp))
        }
        return worst
    }

    @Test
    fun `the body stays inside the upset angle while it follows the brain's rate-capped up`() {
        val loop = Loop(body())
        val worst = followFlip(loop, limited = true)
        assertTrue(worst < MAX_LAG_DEG) { "the body lagged the commanded up by $worst°" }
        assertEquals(-1.0, loop.body.up().y, SETTLED_TOLERANCE)
    }

    @Test
    fun `damping on the absolute spin lags a turning target past the upset angle`() {
        // The law before the reference spin: this lag set the upset flag and dropped the feet's carry mid-turn.
        val worst = followFlip(Loop(body()), limited = false)
        assertTrue(worst > UPSET_DEG) { "the old law lagged only $worst°" }
    }

    @Test
    fun `a 90 degree face change takes 7 brain ticks`() {
        val up = Vector3d(0.0, 1.0, 0.0)
        val wall = Vector3d(0.0, 0.0, 1.0)
        var ticks = 0
        while (up.dot(wall) < 1.0 - 1.0e-9 && ticks < 100) {
            val before = Vector3d(up)
            turnUpToward(up, wall, ATTITUDE_MAX_STEP, wall)
            assertTrue(Math.acos(before.dot(up).coerceIn(-1.0, 1.0)) <= ATTITUDE_MAX_STEP + 1.0e-9)
            ticks++
        }
        assertEquals(7, ticks)
    }

    @Test
    fun `an opposed target turns toward the hint`() {
        val up = Vector3d(0.0, 1.0, 0.0)
        turnUpToward(up, Vector3d(0.0, -1.0, 0.0), ATTITUDE_MAX_STEP, Vector3d(0.0, 0.0, 1.0))
        assertTrue(up.z > 0.0 && Math.abs(up.x) < 1.0e-9) { "turned to $up" }
        assertEquals(1.0, up.length(), 1.0e-9)
    }

    private companion object {
        const val DT = 1.0 / 60.0
        const val FRAMES_PER_TICK = 3
        const val SETTLE_FRAMES = 240
        const val INERTIA_X = 600.0
        const val INERTIA_Y = 900.0
        const val INERTIA_Z = 700.0
        const val SPIN_MARGIN = 1.25
        const val SETTLED_TOLERANCE = 0.01
        /** Well inside the controller's upset angle (60°). */
        const val MAX_LAG_DEG = 45.0
        const val UPSET_DEG = 60.0
    }
}
