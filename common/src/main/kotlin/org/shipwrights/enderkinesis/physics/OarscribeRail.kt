package org.shipwrights.enderkinesis.physics

import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.sqrt
import org.shipwrights.enderkinesis.util.gravity
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.VsBeta

/**
 * Holds a ship on the line its writing Oarscribes draw: it drives the ship to a speed along the line, pulls it
 * back onto the line, holds the heading the ship had when the writing started, and carries its weight.
 * One per ship, so two Oarscribes on one ship do not carry the weight twice.
 */
@OptIn(PhysTickOnly::class, VsBeta::class)
class OarscribeRail : ShipPhysicsListener {

    private class Drive(val right: Vector3dc, val power: Int, val expiresAtNanos: Long)

    // Keyed by packed shipyard BlockPos. A drive expires by itself, so a block that left the ship without a
    // release cannot hold the ship forever.
    private val drives = ConcurrentHashMap<Long, Drive>()

    /** [right] is a unit vector in ship axes. */
    fun drive(pos: Long, right: Vector3dc, power: Int) {
        drives[pos] = Drive(right, power, System.nanoTime() + DRIVE_LIFETIME_NANOS)
    }

    fun release(pos: Long) {
        drives.remove(pos)
    }

    // Physics thread only from here.
    private var engaged = false
    private var liveDrives = 0

    /** Upward acceleration the rail gives against gravity, 0 to the dimension gravity. */
    private var support = 0.0

    /** Part of the force limit the rail used on the last physics tick, 0 to 1. Read on the server thread. */
    @Volatile var strain = 0.0
        private set
    private val anchor = Vector3d()
    private val heading = Quaterniond()
    private val axisLocal = Vector3d()
    private val axisSum = Vector3d()
    private val axisWorld = Vector3d()
    private val scratch = Vector3d()
    private val lateral = Vector3d()
    private val turn = Quaterniond()
    private val rotation = Matrix3d()
    private val inertiaWorld = Matrix3d()

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        physTick(physShip, physLevel, 0.0)
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel, delta: Double) {
        val power = sumLiveDrives()
        if (power == 0 || physShip.isStatic || physShip.mass <= 0.0 || axisSum.lengthSquared() < AXIS_EPSILON) {
            engaged = false
            strain = 0.0
            support = 0.0
            return
        }
        axisSum.normalize()
        val transform = physShip.transform
        if (!engaged) {
            heading.set(transform.shipToWorldRotation)
            axisLocal.set(axisSum)
            anchor.set(transform.positionInWorld)
            engaged = true
        } else if (axisSum.distanceSquared(axisLocal) > AXIS_EPSILON) {
            // A different set of Oarscribes writes now. Start a new line from here.
            axisLocal.set(axisSum)
            anchor.set(transform.positionInWorld)
        }
        heading.transform(axisWorld.set(axisLocal))
        val scale = transform.shipToWorldScaling.x()
        val speed = MAX_SPEED * power / MAX_POWER * scale
        // Mass already scales as scale³; the limits get the same scale³ as the other ship-mounted drives.
        val volume = scale * scale * scale
        val mass = physShip.mass
        val limit = liveDrives * MAX_FORCE * volume
        val maxForce = minOf(liveDrives * FORCE_PER_KG * mass, limit)
        val applied = applyLineForce(physShip, speed, maxForce, physicsStepSeconds(delta), physLevel.gravity())
        strain = applied / limit
        applyHeadingTorque(physShip, liveDrives * minOf(TORQUE_PER_KG * mass, MAX_TORQUE * volume))
    }

    /**
     * Sums the live drive axes into [axisSum], counts them into [liveDrives], and returns the strongest power,
     * or 0 when no drive is live.
     */
    private fun sumLiveDrives(): Int {
        axisSum.zero()
        liveDrives = 0
        var power = 0
        val now = System.nanoTime()
        val iterator = drives.values.iterator()
        while (iterator.hasNext()) {
            val drive = iterator.next()
            if (now - drive.expiresAtNanos > 0) {
                iterator.remove()
                continue
            }
            axisSum.add(drive.right)
            liveDrives++
            power = maxOf(power, drive.power)
        }
        return power
    }

    /** Returns the size of the force it applied. */
    private fun applyLineForce(physShip: PhysShip, speed: Double, maxForce: Double, dt: Double, gravity: Double): Double {
        val velocity = physShip.velocity
        val along = velocity.dot(axisWorld)
        val offset = scratch.set(physShip.transform.positionInWorld).sub(anchor)
        offset.fma(-offset.dot(axisWorld), axisWorld)
        val mass = physShip.mass
        // Learns the weight the ship lacks, so a ship that floats or is held by other blocks is not lifted.
        support = (support - SUPPORT_KI * offset.y * dt).coerceIn(0.0, gravity)
        // Acceleration: speed drive along the line, PD back onto the line across it, and the learned support.
        val force = Vector3d(axisWorld).mul(SPEED_GAIN * (speed - along))
            .fma(-LINE_KP, offset)
            .fma(-LINE_KD, lateral.set(velocity).fma(-along, axisWorld))
            .add(0.0, support, 0.0)
            .mul(mass)
        val applied = clamp(force, maxForce)
        physShip.applyWorldForceToBodyPos(applied)
        return applied.length()
    }

    private fun applyHeadingTorque(physShip: PhysShip, maxTorque: Double) {
        val transform = physShip.transform
        turn.set(transform.shipToWorldRotation).conjugate().premul(heading)
        if (turn.w < 0.0) turn.set(-turn.x, -turn.y, -turn.z, -turn.w)
        val sinHalf = sqrt(turn.x * turn.x + turn.y * turn.y + turn.z * turn.z)
        val error = scratch.set(turn.x, turn.y, turn.z)
        if (sinHalf > AXIS_EPSILON) error.mul(2.0 * atan2(sinHalf, turn.w) / sinHalf) else error.mul(2.0)
        error.mul(HEADING_KP).fma(-HEADING_KD, physShip.angularVelocity)
        // momentOfInertia is in ship axes; ω and applyWorldTorque are world-frame.
        rotation.set(transform.shipToWorldRotation)
        inertiaWorld.set(rotation).mul(physShip.momentOfInertia)
        inertiaWorld.mul(rotation.transpose())
        physShip.applyWorldTorque(clamp(inertiaWorld.transform(Vector3d(error)), maxTorque))
    }

    /** The rail is weak: past [max], the ship's weight and inertia win. */
    private fun clamp(vector: Vector3d, max: Double): Vector3d {
        val length = vector.length()
        return if (length > max) vector.mul(max / length) else vector
    }

    private companion object {
        const val DRIVE_LIFETIME_NANOS = 500_000_000L
        const val AXIS_EPSILON = 1e-9
        const val MAX_POWER = 15.0

        /** World blocks per second at full redstone power. */
        const val MAX_SPEED = 2.0

        /** 1/s. The speed error closes with a 2 s time constant. */
        const val SPEED_GAIN = 0.5

        /** 1/s² and 1/s. Critically damped at 0.75 rad/s. */
        const val LINE_KP = 0.5625
        const val LINE_KD = 1.5

        /** 1/s³. Stable while it stays below LINE_KP × LINE_KD. */
        const val SUPPORT_KI = 0.3
        const val HEADING_KP = 0.5625
        const val HEADING_KD = 1.5

        /**
         * For each writing Oarscribe: newtons for each kilogram of ship, up to [MAX_FORCE]. A little over VS2's
         * default 10 m/s² gravity, so one Oarscribe holds a ship up to 150 t with a little to spare.
         */
        const val FORCE_PER_KG = 12.0
        const val MAX_FORCE = 1_800_000.0

        /** For each writing Oarscribe: newton-metres for each kilogram of ship, up to [MAX_TORQUE]. */
        const val TORQUE_PER_KG = 12.0
        const val MAX_TORQUE = 1_800_000.0
    }
}
