package org.shipwrights.enderkinesis.physics

import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.util.ServerTicks
import org.shipwrights.enderkinesis.util.gravity
import org.shipwrights.enderkinesis.util.pointVelocity
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import kotlin.math.atan2
import kotlin.math.sqrt

/** Targets are in the cradle frame, so a cradle on a carrier ship holds relative to that carrier. */
@OptIn(PhysTickOnly::class, VsBeta::class)
class QuiescentCradleHold : ShipPhysicsListener {

    /**
     * [holdCentre] and [orientation] are in the cradle frame; [shipCentre] is the held point in shipyard coordinates.
     * [carrierId] is [NO_CARRIER] for a cradle in the world.
     */
    class Target(
        val owner: Long,
        val carrierId: Long,
        val holdCentre: Vector3dc,
        val orientation: Quaterniondc,
        val shipCentre: Vector3dc,
    )

    /** The game thread replaces the whole target, so the physics thread never reads a mixed one. */
    @Transient @Volatile var target: Target? = null

    /** Server tick of the last refresh. A target that is not refreshed goes stale, so an unloaded cradle lets go. */
    @Transient @Volatile var refreshTick: Int = 0

    /** True while a fixed joint holds the ship; the joint carries it, so the spring rests. */
    @Transient @Volatile var jointed: Boolean = false

    fun isFresh(): Boolean = target != null && ServerTicks.now - refreshTick <= STALE_TICKS

    // Physics thread only.
    private val holdWorld = Vector3d()
    private val targetRot = Quaterniond()
    private val offset = Vector3d()
    private val error = Vector3d()
    private val carrierVel = Vector3d()
    private val omegaRel = Vector3d()
    private val turn = Quaterniond()
    private val rotation = Matrix3d()
    private val inertiaWorld = Matrix3d()

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        val t = target ?: return
        if (jointed || !isFresh() || physShip.isStatic || physShip.mass <= 0.0) return
        val carrier = if (t.carrierId == NO_CARRIER) null else physLevel.getShipById(t.carrierId) ?: return
        val transform = physShip.transform

        holdWorld.set(t.holdCentre)
        targetRot.set(t.orientation)
        if (carrier != null) {
            carrier.transform.shipToWorld.transformPosition(holdWorld)
            targetRot.premul(carrier.transform.shipToWorldRotation)
        }

        // Aim the centre of mass where it sits when the held point is at the hold centre at the target turn.
        offset.set(t.shipCentre).sub(transform.positionInShip).mul(transform.shipToWorldScaling.x())
        targetRot.transform(offset)
        error.set(holdWorld).sub(offset).sub(transform.positionInWorld)
        val length = error.length()
        if (length > MAX_ERROR) error.mul(MAX_ERROR / length)

        carrierVel.zero()
        omegaRel.set(physShip.angularVelocity)
        if (carrier != null) {
            pointVelocity(carrier.velocity, carrier.angularVelocity, carrier.transform.positionInWorld, transform.positionInWorld, carrierVel)
            omegaRel.sub(carrier.angularVelocity)
        }
        val mass = physShip.mass
        // The spring carries the ship's weight, so a carrier feels it through the reaction, as it will through the joint.
        val force = Vector3d(error).mul(LINEAR_K)
            .sub(carrierVel.negate().add(physShip.velocity).mul(LINEAR_C))
            .add(0.0, physLevel.gravity(), 0.0)
            .mul(mass)
        val torque = turnTorque(physShip)
        if (!force.isFinite || !torque.isFinite) return
        physShip.applyWorldForceToBodyPos(force)
        physShip.applyWorldTorque(torque)
        // Equal and opposite on the carrier, at the held ship's centre of mass; in the world it goes to nothing.
        if (carrier != null && !carrier.isStatic) {
            carrier.applyWorldForce(Vector3d(force).negate(), Vector3d(transform.positionInWorld))
            carrier.applyWorldTorque(Vector3d(torque).negate())
        }
    }

    /** A fresh vector: VS2 queues torques by reference. */
    private fun turnTorque(physShip: PhysShip): Vector3d {
        val transform = physShip.transform
        turn.set(transform.shipToWorldRotation).conjugate().premul(targetRot)
        if (turn.w < 0.0) turn.set(-turn.x, -turn.y, -turn.z, -turn.w)
        val sinHalf = sqrt(turn.x * turn.x + turn.y * turn.y + turn.z * turn.z)
        val alpha = error.set(turn.x, turn.y, turn.z)
        if (sinHalf > EPSILON) alpha.mul(2.0 * atan2(sinHalf, turn.w) / sinHalf) else alpha.mul(2.0)
        alpha.mul(ANGULAR_K).fma(-ANGULAR_C, omegaRel)
        // momentOfInertia is in ship axes; ω and applyWorldTorque are world-frame.
        rotation.set(transform.shipToWorldRotation)
        inertiaWorld.set(rotation).mul(physShip.momentOfInertia)
        inertiaWorld.mul(rotation.transpose())
        return inertiaWorld.transform(Vector3d(alpha))
    }

    companion object {
        const val NO_CARRIER = -1L

        private const val STALE_TICKS = 20
        private const val EPSILON = 1e-9

        /** Clampotron gains, per kilogram and per unit of inertia: k = 64, c = 16 (linear); k = 40, c = 13 (angular). */
        private const val LINEAR_K = 64.0
        private const val LINEAR_C = 16.0
        private const val ANGULAR_K = 40.0
        private const val ANGULAR_C = 13.0

        /** Metres. A far ship comes in at a capped speed instead of a huge spring force. */
        private const val MAX_ERROR = 4.0
    }
}
