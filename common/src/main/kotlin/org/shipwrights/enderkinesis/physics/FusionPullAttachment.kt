package org.shipwrights.enderkinesis.physics

import org.shipwrights.enderkinesis.util.massFalloff
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.VsBeta

/** Staff of Fusion forces: a velocity-limited pull of the blocks inside the bubble toward a point,
 *  an attraction between nearest blocks of neighbor ships, and a rotation that tumbles, holds
 *  steady, or aligns to a goal. */
@OptIn(PhysTickOnly::class, VsBeta::class)
class FusionPullAttachment : ShipPhysicsListener {

    @Volatile private var targetX: Double = 0.0
    @Volatile private var targetY: Double = 0.0
    @Volatile private var targetZ: Double = 0.0
    @Volatile private var share: Double = 0.0
    @Volatile private var pointX: Double = 0.0
    @Volatile private var pointY: Double = 0.0
    @Volatile private var pointZ: Double = 0.0
    @Volatile private var alignGoal: Quaterniondc? = null
    @Volatile private var neighborId: Long = 0L
    @Volatile private var ownBlockX: Double = 0.0
    @Volatile private var ownBlockY: Double = 0.0
    @Volatile private var ownBlockZ: Double = 0.0
    @Volatile private var neighborBlockX: Double = 0.0
    @Volatile private var neighborBlockY: Double = 0.0
    @Volatile private var neighborBlockZ: Double = 0.0

    // Wall clock, because the phys thread cannot read the server tick. A pull or rotation mode
    // that the game thread does not refresh stops by itself.
    @Volatile private var pullExpiresAt: Long = 0L
    @Volatile private var alignExpiresAt: Long = 0L
    @Volatile private var steadyExpiresAt: Long = 0L
    @Volatile private var attractExpiresAt: Long = 0L

    // Phys thread only. VS2 queues force and torque vectors by reference, so these hold only
    // temporaries; each applied force and torque is a new vector.
    private val scratchPoint = Vector3d()
    private val scratchAccel = Vector3d()
    private val scratchOmega = Vector3d()
    private val scratchQuat = Quaterniond()

    private val spinPhase = DoubleArray(3) { Random.nextDouble(TWO_PI) }
    private val spinFrequency = DoubleArray(3) { Random.nextDouble(MIN_SPIN_FREQUENCY, MAX_SPIN_FREQUENCY) }

    /** Pulls with [insideShare] of the ship's mass, at [insidePoint] (shipyard frame), toward [worldTarget]. */
    fun pullToward(worldTarget: Vector3dc, insideShare: Double, insidePoint: Vector3dc) {
        targetX = worldTarget.x()
        targetY = worldTarget.y()
        targetZ = worldTarget.z()
        share = insideShare
        pointX = insidePoint.x()
        pointY = insidePoint.y()
        pointZ = insidePoint.z()
        pullExpiresAt = System.nanoTime() + LEASE_NANOS
    }

    /** Attracts [ownBlock] toward [neighborBlock] on ship [neighbor]; both in their shipyard frames. */
    fun attractTo(neighbor: Long, ownBlock: Vector3dc, neighborBlock: Vector3dc) {
        neighborId = neighbor
        ownBlockX = ownBlock.x()
        ownBlockY = ownBlock.y()
        ownBlockZ = ownBlock.z()
        neighborBlockX = neighborBlock.x()
        neighborBlockY = neighborBlock.y()
        neighborBlockZ = neighborBlock.z()
        attractExpiresAt = System.nanoTime() + LEASE_NANOS
    }

    /** Turns the ship toward [worldRotation] in place of the tumble. */
    fun alignTo(worldRotation: Quaterniondc) {
        alignGoal = Quaterniond(worldRotation)
        alignExpiresAt = System.nanoTime() + LEASE_NANOS
    }

    /** Stops the tumble, so that a ship that aligns to this one has a still goal. */
    fun holdSteady() {
        steadyExpiresAt = System.nanoTime() + LEASE_NANOS
    }

    fun release() {
        pullExpiresAt = 0L
        alignExpiresAt = 0L
        steadyExpiresAt = 0L
        attractExpiresAt = 0L
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        val now = System.nanoTime()
        if (now - pullExpiresAt >= 0L) return
        val point = physShip.transform.shipToWorld.transformPosition(scratchPoint.set(pointX, pointY, pointZ))
        val accel = scratchAccel.set(targetX - point.x, targetY - point.y, targetZ - point.z)
        val distance = accel.length()
        if (distance > MIN_DISTANCE) accel.mul(min(MAX_SPEED, POSITION_GAIN * distance) / distance) else accel.zero()
        accel.sub(physShip.velocity).mul(VELOCITY_GAIN)
        val maxAccel = MAX_ACCELERATION * massFalloff(physShip.mass)
        if (accel.lengthSquared() > maxAccel * maxAccel) accel.normalize(maxAccel)
        physShip.applyWorldForce(Vector3d(accel).mul(physShip.mass * share), point)
        if (now - attractExpiresAt < 0L) applyAttraction(physShip, physLevel)

        val goal = alignGoal
        val omega = scratchOmega
        when {
            goal != null && now - alignExpiresAt < 0L -> alignOmega(physShip.transform.rotation, goal, omega)
            now - steadyExpiresAt < 0L -> omega.zero()
            else -> tumbleOmega(now, omega)
        }
        applyAngular(physShip, omega)
    }

    /** Constant pull of the nearest block toward the neighbor's nearest block, eased off in the
     *  last block so that touching ships do not jitter. */
    private fun applyAttraction(physShip: PhysShip, physLevel: PhysLevel) {
        val neighbor = physLevel.getShipById(neighborId) ?: return
        val own = physShip.transform.shipToWorld.transformPosition(scratchPoint.set(ownBlockX, ownBlockY, ownBlockZ))
        val toward = neighbor.transform.shipToWorld
            .transformPosition(scratchAccel.set(neighborBlockX, neighborBlockY, neighborBlockZ)).sub(own)
        val distance = toward.length()
        if (distance < MIN_DISTANCE) return
        val accel = ATTRACT_ACCELERATION * min(1.0, distance / ATTRACT_EASE_DISTANCE)
        physShip.applyWorldForce(Vector3d(toward).mul(accel * physShip.mass * share / distance), own)
    }

    private fun tumbleOmega(now: Long, out: Vector3d) {
        val seconds = now * 1e-9
        out.set(
            sin(TWO_PI * spinFrequency[0] * seconds + spinPhase[0]),
            sin(TWO_PI * spinFrequency[1] * seconds + spinPhase[1]),
            sin(TWO_PI * spinFrequency[2] * seconds + spinPhase[2]),
        ).mul(SPIN_SPEED)
    }

    /** World angular velocity that turns [current] toward [goal], as in VS2 WeldPullController. */
    private fun alignOmega(current: Quaterniondc, goal: Quaterniondc, out: Vector3d) {
        val delta = scratchQuat.set(current).conjugate().premul(goal).normalize()
        // Not delta.mul(-1.0): VS2's physics classpath has JOML 1.10.4, which lacks Quaterniond.mul(double).
        if (delta.w < 0.0) delta.set(-delta.x, -delta.y, -delta.z, -delta.w)
        out.set(delta.x, delta.y, delta.z)
        val sinHalf = out.length()
        if (sinHalf < MIN_DISTANCE) {
            out.zero()
            return
        }
        out.mul(2.0 * atan2(sinHalf, delta.w) * ALIGN_GAIN / sinHalf)
        if (out.lengthSquared() > MAX_ALIGN_SPEED * MAX_ALIGN_SPEED) out.normalize(MAX_ALIGN_SPEED)
    }

    /** Drives the angular velocity toward [targetOmega]. The inertia tensor is in the body frame:
     *  τ = R · I · R⁻¹ · α. */
    private fun applyAngular(physShip: PhysShip, targetOmega: Vector3d) {
        val torque = Vector3d(targetOmega).sub(physShip.angularVelocity).mul(ANGULAR_GAIN)
        if (torque.lengthSquared() > MAX_ANGULAR_ACCELERATION * MAX_ANGULAR_ACCELERATION) {
            torque.normalize(MAX_ANGULAR_ACCELERATION)
        }
        val rotation = physShip.transform.rotation
        scratchQuat.set(rotation).conjugate().transform(torque)
        rotation.transform(physShip.momentOfInertia.transform(torque))
        if (torque.isFinite) physShip.applyWorldTorque(torque)
    }

    private companion object {
        const val LEASE_NANOS: Long = 150_000_000L
        const val MIN_DISTANCE: Double = 1e-6
        const val MAX_SPEED: Double = 6.0
        const val POSITION_GAIN: Double = 0.75
        const val VELOCITY_GAIN: Double = 4.0
        const val MAX_ACCELERATION: Double = 12.0
        const val TWO_PI: Double = 2.0 * Math.PI
        const val SPIN_SPEED: Double = 0.6
        const val MIN_SPIN_FREQUENCY: Double = 0.05
        const val MAX_SPIN_FREQUENCY: Double = 0.2
        const val ATTRACT_ACCELERATION: Double = 4.0
        const val ATTRACT_EASE_DISTANCE: Double = 1.0
        const val ALIGN_GAIN: Double = 2.0
        const val MAX_ALIGN_SPEED: Double = 1.2
        const val ANGULAR_GAIN: Double = 2.0
        const val MAX_ANGULAR_ACCELERATION: Double = 2.0
    }
}
