package org.shipwrights.enderkinesis.physics

import org.joml.Quaterniond
import org.joml.Vector3d
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
    /** One-shot jump launch: when pending, a single-tick impulse drives velocity toward
     *  (launchX/Y/Z); capped by JUMP_IMPULSE so overloaded bodies jump weakly. */
    @Transient var launchX: Double = 0.0,
    @Transient var launchY: Double = 0.0,
    @Transient var launchZ: Double = 0.0,
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

    /** Game thread: one jump toward the velocity in `launchX/Y/Z`. */
    fun queueLaunch() {
        pendingLaunch.set(Vector3d(launchX, launchY, launchZ))
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        if (!active) return
        if (silentFrames > HEARTBEAT_FRAMES) return
        silentFrames++
        if (physShip.mass <= 0.0) return
        val c = command ?: return
        // The names of the scratch properties, bound to the published values.
        val platformX = c.platformX; val platformY = c.platformY; val platformZ = c.platformZ
        val hoverX = c.hoverX; val hoverY = c.hoverY; val hoverZ = c.hoverZ
        val hoverActive = c.hoverActive
        val upX = c.upX; val upY = c.upY; val upZ = c.upZ
        val moveVelX = c.moveVelX; val moveVelY = c.moveVelY; val moveVelZ = c.moveVelZ
        val idealRotation = c.idealRotation
        val strengthScale = c.strengthScale
        val strutsActive = c.strutsActive
        val strutAnchorX = c.strutAnchorX; val strutAnchorY = c.strutAnchorY; val strutAnchorZ = c.strutAnchorZ
        val strutHipX = c.strutHipX; val strutHipY = c.strutHipY; val strutHipZ = c.strutHipZ
        val strutStance = c.strutStance

        val mass = physShip.mass
        val transform = physShip.transform
        val rotation = transform.shipToWorldRotation
        val plateWorld = Vector3d(platformX, platformY, platformZ)
        transform.shipToWorld.transformPosition(plateWorld)
        val vel = physShip.velocity

        val launch = pendingLaunch.getAndSet(null)
        if (launch != null) {
            val impulse = launch.sub(vel).mul(mass)
            val mag = impulse.length()
            val impulseCap = JUMP_IMPULSE * strengthScale
            if (mag > impulseCap) impulse.mul(impulseCap / mag)
            physShip.applyInvariantForce(impulse.mul(PHYS_TPS))
        }

        val force = Vector3d()
        if (hoverActive) {
            val up = Vector3d(upX, upY, upZ)
            val heightErr = Vector3d(hoverX, hoverY, hoverZ).sub(plateWorld).dot(up)
            val upSpeed = vel.dot(up)
            val hoverAccel = HOVER_STIFFNESS * heightErr - HOVER_DAMPING * upSpeed
            force.set(up).mul(mass * hoverAccel)
            // Weight relief only while attached — airborne, gravity owns the ship.
            force.y += mass * GRAVITY
            // Velocity PD in the surface plane; the along-normal component is stripped
            // so the drive never fights the hover spring.
            val accel = Vector3d(moveVelX, moveVelY, moveVelZ).sub(Vector3d(vel)).mul(MOVE_GAIN)
            accel.sub(Vector3d(up).mul(accel.dot(up)))
            val mag = accel.length()
            if (mag > MOVE_MAX_ACCEL) accel.mul(MOVE_MAX_ACCEL / mag)
            force.add(accel.mul(mass))

            if (strutsActive) {
                val comWorld = Vector3d(transform.positionInShip)
                transform.shipToWorld.transformPosition(comWorld)
                for (i in 0..3) {
                    if (!strutStance[i]) continue
                    val hipWorld = transform.shipToWorld.transformPosition(
                        Vector3d(strutHipX[i], strutHipY[i], strutHipZ[i]),
                    )
                    val r = Vector3d(hipWorld).sub(comWorld)
                    val velHip = Vector3d(physShip.omega).cross(Vector3d(r)).add(vel)
                    val lenUp = Vector3d(hipWorld)
                        .sub(strutAnchorX[i], strutAnchorY[i], strutAnchorZ[i])
                        .dot(up)
                    // Bidirectional (a supplement may pull); rest matches the hover's
                    // ride height so the layers agree at equilibrium.
                    var springMag = (mass / 4.0) *
                        (SUPP_STIFFNESS * (STRUT_REST - lenUp) - SUPP_DAMPING * velHip.dot(up))
                    val suppCap = SUPP_FORCE_CAP * strengthScale
                    springMag = springMag.coerceIn(-suppCap, suppCap)
                    physShip.applyInvariantForceToPos(Vector3d(up).mul(springMag), r)
                }
            }
        }
        // Legs have finite strength: the PD asks in accelerations (mass-proportional),
        // but delivery is capped in NEWTONS. A light load leaves huge headroom (snappy);
        // a heavy one eats the budget into weight-holding — it sags, accelerates
        // sluggishly, and past ~LEG_STRENGTH/g kg simply can't be carried.
        val legStrength = LEG_STRENGTH * strengthScale
        val forceMag = force.length()
        if (forceMag > legStrength) force.mul(legStrength / forceMag)
        physShip.applyInvariantForce(force)

        // Quaternion error → small-angle axis vector → PD; see WyllandTomeForceInducer
        // for the derivation and the shortest-arc sign flip.
        val rotDiff = idealRotation
            .mul(rotation.invert(Quaterniond()), Quaterniond())
            .normalize()
            .invert()
        val rotDiffVector = Vector3d(
            rotDiff.x * 2.0,
            rotDiff.y * 2.0,
            rotDiff.z * 2.0,
        )
        if (rotDiff.w > 0.0) rotDiffVector.mul(-1.0)
        rotDiffVector.mul(ROTATION_STIFFNESS)
        rotDiffVector.sub(Vector3d(physShip.omega).mul(ROTATION_DAMPING))

        val torque = Vector3d()
        rotation.transformInverse(rotDiffVector, torque)
        physShip.momentOfInertia.transform(torque)
        rotation.transform(torque)
        val legTorque = LEG_TORQUE * strengthScale
        val torqueMag = torque.length()
        if (torqueMag > legTorque) torque.mul(legTorque / torqueMag)
        physShip.applyInvariantTorque(torque)
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
        private const val PHYS_TPS = 60.0
        private const val HEARTBEAT_FRAMES = 60
    }
}
