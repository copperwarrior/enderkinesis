package org.shipwrights.enderkinesis.physics

import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel

/**
 * The Constriliar's body IS this ship — the entity is just the brain riding it. Three
 * force channels make it act like a creature:
 *
 *  - **Hover spring** along the surface normal: the plate centre springs toward a
 *    game-thread-sampled hover point (surface + ride height). Steps, slopes, and
 *    landings resolve as real physics; airborne (no surface in reach) means real
 *    free-fall. Weight is fed forward only while attached, so wall-hover sticks.
 *  - **Locomotion**: horizontal velocity PD toward the brain's desired walk velocity —
 *    acceleration, momentum, and collision pushback all come out naturally.
 *  - **Rotation PD** with the R·I·R⁻¹ inertia sandwich (VMod pattern, see
 *    [WyllandTomeForceInducer]) toward the body's tilt+yaw.
 */
class ConstriliarShipController(
    /** Platform centre in shipyard coords. */
    @Transient var platformX: Double = 0.0,
    @Transient var platformY: Double = 0.0,
    @Transient var platformZ: Double = 0.0,
    /** World-space hover point for the plate centre (sampled surface + ride height). */
    @Transient var hoverX: Double = 0.0,
    @Transient var hoverY: Double = 0.0,
    @Transient var hoverZ: Double = 0.0,
    /** False when no surface is in reach — free-fall, no vertical force at all. */
    @Transient var hoverActive: Boolean = false,
    /** Surface normal the hover spring acts along. */
    @Transient var upX: Double = 0.0,
    @Transient var upY: Double = 1.0,
    @Transient var upZ: Double = 0.0,
    /** Desired world-frame walk velocity (m/s); the along-normal component is removed
     *  at application time (the hover spring owns that axis), so wall/ceiling travel
     *  may carry vertical velocity. */
    @Transient var moveVelX: Double = 0.0,
    @Transient var moveVelY: Double = 0.0,
    @Transient var moveVelZ: Double = 0.0,
    /** Desired world-frame orientation (body tilt + yaw), written by the brain. */
    @Transient var idealRotation: Quaterniond = Quaterniond(),
    /** Body-mass-relative strength multiplier (see the entity's strengthScale): scales
     *  every force/torque/jump budget so a netherite body out-muscles a purpur one. */
    @Transient var strengthScale: Double = 1.0,
    /** Re-asserted every game tick by the entity; false once it dies. */
    @Transient @Volatile var active: Boolean = false,
) : ShipPhysicsListener {

    // Supplemental leg springs: a MUSCLE layer over the hover (which keeps weight and
    // primary height). Each stance leg is a modest bidirectional spring along the body
    // normal at its hip, against its planted anchor — adds per-corner stiffness (slopes,
    // load shifts) and lets the gait physically show: a swinging leg's corner sags.
    @Transient val strutAnchorX = DoubleArray(4)
    @Transient val strutAnchorY = DoubleArray(4)
    @Transient val strutAnchorZ = DoubleArray(4)
    @Transient val strutHipX = DoubleArray(4)
    @Transient val strutHipY = DoubleArray(4)
    @Transient val strutHipZ = DoubleArray(4)
    @Transient val strutStance = BooleanArray(4)
    @Transient var strutsActive = false

    /** Physics frames since the brain last wrote. The brain can stop (chunk unload,
     *  dimension change) while VS2 still simulates the body with the last command. */
    @Transient
    @Volatile
    var silentFrames = 0

    /** One consistent set of drive values. The properties of this class are the brain's
     *  scratch on the game thread; the physics thread reads only a published command. */
    private class Command(
        val platformX: Double, val platformY: Double, val platformZ: Double,
        val hoverX: Double, val hoverY: Double, val hoverZ: Double,
        val hoverActive: Boolean,
        val upX: Double, val upY: Double, val upZ: Double,
        val moveVelX: Double, val moveVelY: Double, val moveVelZ: Double,
        val idealRotation: Quaterniond,
        val strengthScale: Double,
        val strutsActive: Boolean,
        val strutAnchorX: DoubleArray, val strutAnchorY: DoubleArray, val strutAnchorZ: DoubleArray,
        val strutHipX: DoubleArray, val strutHipY: DoubleArray, val strutHipZ: DoubleArray,
        val strutStance: BooleanArray,
    )

    @Transient
    @Volatile
    private var command: Command? = null

    @Transient
    private val pendingLaunch = java.util.concurrent.atomic.AtomicReference<Vector3d?>(null)

    // Physics-thread scratch. Never pass one to an apply* call: VS2 keeps the reference.
    @Transient private val plateWorld = Vector3d()
    @Transient private val up = Vector3d()
    @Transient private val tmp = Vector3d()
    @Transient private val accel = Vector3d()
    @Transient private val comWorld = Vector3d()
    @Transient private val hipWorld = Vector3d()
    @Transient private val velHip = Vector3d()
    @Transient private val rotInverse = Quaterniond()
    @Transient private val rotDiff = Quaterniond()
    @Transient private val rotDiffVector = Vector3d()

    fun setPlatform(centre: Vector3dc) {
        platformX = centre.x(); platformY = centre.y(); platformZ = centre.z()
    }

    fun setUp(x: Double, y: Double, z: Double) {
        upX = x; upY = y; upZ = z
    }

    fun setMoveVel(v: Vector3dc) {
        moveVelX = v.x(); moveVelY = v.y(); moveVelZ = v.z()
    }

    fun setHover(x: Double, y: Double, z: Double) {
        hoverX = x; hoverY = y; hoverZ = z
        hoverActive = true
    }

    /** Game thread: makes the values written since the last call visible to the physics
     *  thread as one object, and counts as the heartbeat. */
    fun publish() {
        command = Command(
            platformX, platformY, platformZ, hoverX, hoverY, hoverZ, hoverActive,
            upX, upY, upZ, moveVelX, moveVelY, moveVelZ, Quaterniond(idealRotation),
            strengthScale, strutsActive,
            strutAnchorX.clone(), strutAnchorY.clone(), strutAnchorZ.clone(),
            strutHipX.clone(), strutHipY.clone(), strutHipZ.clone(), strutStance.clone(),
        )
        active = true
        silentFrames = 0
    }

    /** Game thread: one jump toward this world velocity (m/s); capped by JUMP_IMPULSE,
     *  so overloaded bodies jump weakly. */
    fun queueLaunch(x: Double, y: Double, z: Double) {
        pendingLaunch.set(Vector3d(x, y, z))
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) =
        physTick(physShip, physLevel, DEFAULT_STEP_SECONDS)

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel, delta: Double) {
        if (!active) return
        if (silentFrames > HEARTBEAT_FRAMES) return
        silentFrames++
        if (physShip.mass <= 0.0) return
        val c = command ?: return

        val mass = physShip.mass
        val transform = physShip.transform
        val rotation = transform.shipToWorldRotation
        transform.shipToWorld.transformPosition(plateWorld.set(c.platformX, c.platformY, c.platformZ))
        val vel = physShip.velocity

        pendingLaunch.getAndSet(null)?.let { launch ->
            val impulse = launch.sub(vel).mul(mass)
            val mag = impulse.length()
            val impulseCap = JUMP_IMPULSE * c.strengthScale
            if (mag > impulseCap) impulse.mul(impulseCap / mag)
            physShip.applyInvariantForce(impulse.mul(1.0 / stepSeconds(delta)))
        }

        val force = Vector3d()
        if (c.hoverActive) {
            up.set(c.upX, c.upY, c.upZ)
            val heightErr = tmp.set(c.hoverX, c.hoverY, c.hoverZ).sub(plateWorld).dot(up)
            val hoverAccel = HOVER_STIFFNESS * heightErr - HOVER_DAMPING * vel.dot(up)
            force.set(up).mul(mass * hoverAccel)
            // Weight relief only while attached — airborne, gravity owns the ship.
            force.y += mass * GRAVITY
            // Velocity PD in the surface plane; the along-normal component is stripped
            // so the drive never fights the hover spring.
            accel.set(c.moveVelX, c.moveVelY, c.moveVelZ).sub(vel).mul(MOVE_GAIN)
            accel.sub(tmp.set(up).mul(accel.dot(up)))
            val mag = accel.length()
            if (mag > MOVE_MAX_ACCEL) accel.mul(MOVE_MAX_ACCEL / mag)
            force.add(accel.mul(mass))
            if (c.strutsActive) applyStruts(physShip, c, mass, vel)
        }
        // Legs have finite strength: the PD asks in accelerations (mass-proportional),
        // but delivery is capped in NEWTONS. A light load leaves huge headroom (snappy);
        // a heavy one eats the budget into weight-holding — it sags, accelerates
        // sluggishly, and past ~LEG_STRENGTH/g kg simply can't be carried.
        val legStrength = LEG_STRENGTH * c.strengthScale
        val forceMag = force.length()
        if (forceMag > legStrength) force.mul(legStrength / forceMag)
        physShip.applyInvariantForce(force)

        // Quaternion error → small-angle axis vector → PD; see WyllandTomeForceInducer
        // for the derivation and the shortest-arc sign flip.
        c.idealRotation.mul(rotation.invert(rotInverse), rotDiff).normalize().invert()
        rotDiffVector.set(rotDiff.x * 2.0, rotDiff.y * 2.0, rotDiff.z * 2.0)
        if (rotDiff.w > 0.0) rotDiffVector.negate()
        rotDiffVector.mul(ROTATION_STIFFNESS).sub(tmp.set(physShip.omega).mul(ROTATION_DAMPING))

        val torque = Vector3d()
        rotation.transformInverse(rotDiffVector, torque)
        physShip.momentOfInertia.transform(torque)
        rotation.transform(torque)
        val legTorque = LEG_TORQUE * c.strengthScale
        val torqueMag = torque.length()
        if (torqueMag > legTorque) torque.mul(legTorque / torqueMag)
        physShip.applyInvariantTorque(torque)
    }

    /** Uses [up] as set by the hover block of [physTick]. */
    private fun applyStruts(physShip: PhysShip, c: Command, mass: Double, vel: Vector3dc) {
        val shipToWorld = physShip.transform.shipToWorld
        shipToWorld.transformPosition(comWorld.set(physShip.transform.positionInShip))
        val suppCap = SUPP_FORCE_CAP * c.strengthScale
        for (i in 0..3) {
            if (!c.strutStance[i]) continue
            shipToWorld.transformPosition(hipWorld.set(c.strutHipX[i], c.strutHipY[i], c.strutHipZ[i]))
            val r = Vector3d(hipWorld).sub(comWorld)
            velHip.set(physShip.omega).cross(r).add(vel)
            val lenUp = tmp.set(hipWorld)
                .sub(c.strutAnchorX[i], c.strutAnchorY[i], c.strutAnchorZ[i])
                .dot(up)
            // Bidirectional (a supplement may pull); rest matches the hover's
            // ride height so the layers agree at equilibrium.
            val springMag = ((mass / 4.0) *
                (SUPP_STIFFNESS * (STRUT_REST - lenUp) - SUPP_DAMPING * velHip.dot(up)))
                .coerceIn(-suppCap, suppCap)
            physShip.applyInvariantForceToPos(Vector3d(up).mul(springMag), r)
        }
    }

    companion object {

        /** Ride-height spring: stiff enough to crest 1-block steps briskly, damped a
         *  touch under critical so landings read as a cushioned settle. */
        const val HOVER_STIFFNESS = 45.0
        const val HOVER_DAMPING = 12.0
        /** Velocity-tracking gain: ~walk speed within half a second. */
        const val MOVE_GAIN = 6.0
        const val MOVE_MAX_ACCEL = 12.0
        const val ROTATION_STIFFNESS = 28.0
        const val ROTATION_DAMPING = 11.0

        /** Supplemental leg springs: rest = hover ride height; gains a fraction of the
         *  hover's so the layers cooperate, per-leg force capped well under a leg's
         *  share of LEG_STRENGTH. */
        const val STRUT_REST = 0.25
        const val SUPP_STIFFNESS = 15.0
        const val SUPP_DAMPING = 5.0
        const val SUPP_FORCE_CAP = 10_000.0
        const val GRAVITY = 10.0

        /** Force budget in Newtons — the strain model. The 400 kg body alone needs
         *  ~4 kN against gravity; an iron block (~7 t) is carried without issue
         *  (~26 kN of headroom left for locomotion), and the budget dead-lifts 10 t. */
        const val LEG_STRENGTH = 100_000.0
        const val LEG_TORQUE = 60_000.0
        /** Jump impulse budget (N·s): a bare body launches crisply; near the carry
         *  limit the same spring barely hops. */
        const val JUMP_IMPULSE = 25_000.0
        private const val DEFAULT_STEP_SECONDS = 1.0 / 60.0
        private const val HEARTBEAT_FRAMES = 60

        /** VS2's KDoc gives the listener delta in ms; seconds are also accepted, since
         *  no physics step lasts a full second. */
        private fun stepSeconds(delta: Double): Double = when {
            delta <= 0.0 -> DEFAULT_STEP_SECONDS
            delta > 1.0 -> delta / 1000.0
            else -> delta
        }
    }
}
