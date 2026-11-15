package org.shipwrights.enderkinesis.physics

import org.joml.Matrix3dc
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Most a walker's commanded up turns in one game tick (rad): 5 rad/s, a 90° face change in 7 ticks. */
internal const val ATTITUDE_MAX_STEP = 0.25

/** Most the controller's rotation reference turns per second (rad/s), above the up and yaw rates the brain commands. */
internal const val ROTATION_REF_MAX_RATE = 6.0

private const val PARALLEL_EPS = 1.0e-12

/**
 * Turns the unit vector [from] toward the unit vector [to] by at most [maxStep] radians, in place, at a constant
 * rate, so a target that jumps still gives a smooth turn. A target straight opposite turns toward [hint].
 */
internal fun turnUpToward(from: Vector3d, to: Vector3dc, maxStep: Double, hint: Vector3dc): Vector3d {
    require(maxStep > 0.0) { "maxStep must be positive" }
    val dot = (from.x * to.x() + from.y * to.y() + from.z * to.z()).coerceIn(-1.0, 1.0)
    val angle = acos(dot)
    if (angle <= maxStep) return from.set(to).normalize()
    var ax = from.y * to.z() - from.z * to.y()
    var ay = from.z * to.x() - from.x * to.z()
    var az = from.x * to.y() - from.y * to.x()
    if (ax * ax + ay * ay + az * az < PARALLEL_EPS) {
        ax = from.y * hint.z() - from.z * hint.y()
        ay = from.z * hint.x() - from.x * hint.z()
        az = from.x * hint.y() - from.y * hint.x()
        if (ax * ax + ay * ay + az * az < PARALLEL_EPS) {
            // Any axis square to [from] does; x or z is never parallel to both.
            if (abs(from.x) < 0.9) { ax = 0.0; ay = from.z; az = -from.y } else { ax = -from.z; ay = 0.0; az = from.x }
        }
    }
    val len = sqrt(ax * ax + ay * ay + az * az)
    ax /= len; ay /= len; az /= len
    // Rodrigues with the axis square to [from]: v' = v cos θ + (k × v) sin θ.
    val c = cos(maxStep)
    val s = sin(maxStep)
    val kx = ay * from.z - az * from.y
    val ky = az * from.x - ax * from.z
    val kz = ax * from.y - ay * from.x
    return from.set(from.x * c + kx * s, from.y * c + ky * s, from.z * c + kz * s).normalize()
}

/** Moves [ref] toward [target] by at most [maxAngle] radians, the short way, in place. JOML 1.10.4 API only. */
internal fun stepRotationToward(ref: Quaterniond, target: Quaterniondc, maxAngle: Double): Quaterniond {
    require(maxAngle > 0.0) { "maxAngle must be positive" }
    val dot = abs(ref.dot(target)).coerceAtMost(1.0)
    val angle = 2.0 * acos(dot)
    if (angle <= maxAngle) return ref.set(target)
    return ref.slerp(target, maxAngle / angle).normalize()
}

/** World-frame spin (rad/s) that turns [from] into [to] in [dt] seconds, into [out]; small steps only. */
internal fun spinBetween(from: Quaterniondc, to: Quaterniondc, dt: Double, scratch: Quaterniond, out: Vector3d): Vector3d {
    require(dt > 0.0) { "dt must be positive" }
    to.mul(from.invert(scratch), scratch)
    val sign = if (scratch.w < 0.0) -2.0 else 2.0
    return out.set(scratch.x * sign / dt, scratch.y * sign / dt, scratch.z * sign / dt)
}

/** [v] (world frame) through the body-frame inertia, as R·I·R⁻¹·v, into [out]. */
internal fun Matrix3dc.transformInWorld(rotation: Quaterniondc, v: Vector3dc, out: Vector3d): Vector3d {
    rotation.transformInverse(v, out)
    transform(out)
    return rotation.transform(out)
}

/**
 * The walker's rotation PD: quaternion error to a small-angle axis vector, then the R·I·R⁻¹ sandwich (see
 * [WyllandTomeForceInducer]), capped at [cap] N·m. Physics thread; it keeps its own scratch.
 */
internal class RotationPd {
    private val rotInverse = Quaterniond()
    private val rotDiff = Quaterniond()
    private val error = Vector3d()
    private val spin = Vector3d()

    @Suppress("LongParameterList") // One body state and one gain set; a holder would allocate per frame.
    fun torque(
        ideal: Quaterniondc,
        rotation: Quaterniondc,
        angularVelocity: Vector3dc,
        spinRef: Vector3dc,
        inertia: Matrix3dc,
        stiffness: Double,
        damping: Double,
        cap: Double,
        out: Vector3d,
    ): Vector3d {
        ideal.mul(rotation.invert(rotInverse), rotDiff).normalize().invert()
        error.set(rotDiff.x * 2.0, rotDiff.y * 2.0, rotDiff.z * 2.0)
        if (rotDiff.w > 0.0) error.negate()
        // Damping on the spin relative to the reference's own: on absolute spin a reference turning at v lagged
        // by damping·v/stiffness, about 2 rad at 5 rad/s, past the upset angle.
        error.mul(stiffness).sub(spin.set(angularVelocity).sub(spinRef).mul(damping))
        inertia.transformInWorld(rotation, error, out)
        val mag = out.length()
        if (mag > cap) out.mul(cap / mag)
        return out
    }
}
