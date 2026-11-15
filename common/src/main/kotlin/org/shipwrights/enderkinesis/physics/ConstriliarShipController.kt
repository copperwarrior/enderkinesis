package org.shipwrights.enderkinesis.physics

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.JsonNode
import org.joml.Matrix3d
import org.joml.Matrix3dc
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.util.gravity
import org.shipwrights.enderkinesis.util.pointVelocity
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.VsBeta

/**
 * The Constriliar's body IS this ship; the entity is the brain riding it. One body
 * wrench (ride-height PD + weight, in-plane velocity PD, rotation PD with the R·I·R⁻¹
 * sandwich, see [WyllandTomeForceInducer]) is delivered only through contacts: the
 * stance feet, or the knees of an upset body. Swimming and the spawn intro keep the
 * hover, which applies the wrench to the body directly.
 */
@OptIn(PhysTickOnly::class, VsBeta::class)
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
    /** Desired walk velocity (m/s), world axes, relative to the deck; the along-normal component is removed
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
    /** The ship whose deck the body walks on, or -1. [moveVelX] and the hover act relative to its motion. */
    @Transient var deckShipId: Long = -1L,
) : ShipPhysicsListener {

    /** The deck's pose when the brain wrote the world values: the physics thread carries them along with the deck. */
    @Transient val deckWorldToShip = Matrix4d()
    @Transient val deckRotation = Quaterniond()

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
    /** Server thread. Set while a releaseShip clears this body, so other owners' platforms do not delete it again. */
    @Transient var releasing = false
    /** No hover share: only the stance feet hold the body. */
    @Transient var legOnly = false
    /** The walker's own mass (its body, no cargo), kg: grip scales with it. */
    @Transient var selfMass = 0.0
    /** World force (N) the hanging legs' swing puts on the body, at the platform. */
    @Transient val legSwing = Vector3d()
    /** False for the modes the legs do not drive (swimming, the spawn intro): the
     *  hover holds the body there. */
    @Transient var legSystem = false
    /** Knee points (world) a body on its side or back could push with, and whether
     *  each touches a surface. The game thread casts for them. */
    @Transient val kneeX = DoubleArray(4)
    @Transient val kneeY = DoubleArray(4)
    @Transient val kneeZ = DoubleArray(4)
    @Transient val kneeContact = BooleanArray(4)
    /** The path is turning the body onto a new face: a large gap to the target up is the
     *  turn itself, not a body knocked over. */
    @Transient var plannedTurn = false
    /** A fall shake plays: the feet give [EXERT_BOOST] for it without drawing the budget,
     *  which the fall and the righting had mostly spent. */
    @Transient var shaking = false
    /** The body pushes off VS2 contacts; off where it is meant to meet a face or line up in a slot. */
    @Transient var feelBumps = false
    @Transient private var upAgree = 1.0

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
        val legOnly: Boolean,
        val selfMass: Double,
        val legSystem: Boolean,
        val legSwing: Vector3dc,
        val kneeX: DoubleArray, val kneeY: DoubleArray, val kneeZ: DoubleArray,
        val kneeContact: BooleanArray,
        val plannedTurn: Boolean,
        val shaking: Boolean,
        val feelBumps: Boolean,
        val deckShipId: Long,
        val deckWorldToShip: Matrix4dc,
        val deckRotation: Quaterniondc,
    )

    @Transient
    @Volatile
    private var command: Command? = null

    @Transient
    private val pendingLaunch = java.util.concurrent.atomic.AtomicReference<Vector3d?>(null)

    // Physics-thread scratch. Never pass one to an apply* call: VS2 keeps the reference.
    @Transient private val bodyForceScratch = Vector3d()
    @Transient private val bodyTorqueScratch = Vector3d()
    @Transient private val plateWorld = Vector3d()
    @Transient private val up = Vector3d()
    @Transient private val tmp = Vector3d()
    @Transient private val accel = Vector3d()
    /** World velocity of the deck point under the plate; zero off a deck. */
    @Transient private val deckVel = Vector3d()
    @Transient private var deck: PhysShip? = null
    /** The deck's motion since the brain wrote: identity off a deck. */
    @Transient private val deckDelta = Matrix4d()
    @Transient private val deckRotDelta = Quaterniond()
    // The command's world values, carried along with the deck to this frame.
    @Transient private val cmdHover = Vector3d()
    @Transient private val cmdUp = Vector3d()
    @Transient private val cmdMove = Vector3d()
    @Transient private val cmdRotation = Quaterniond()
    @Transient private val anchorX = DoubleArray(4)
    @Transient private val anchorY = DoubleArray(4)
    @Transient private val anchorZ = DoubleArray(4)
    @Transient private val kneeWX = DoubleArray(4)
    @Transient private val kneeWY = DoubleArray(4)
    @Transient private val kneeWZ = DoubleArray(4)
    @Transient private val relVel = Vector3d()
    @Transient private val deckPoint = Vector3d()
    @Transient private val comWorld = Vector3d()
    @Transient private val hipWorld = Vector3d()
    @Transient private val velHip = Vector3d()
    @Transient private val hipArm = Vector3d()
    @Transient private val shakeAlpha = Vector3d()
    @Transient private val shakeArm = Vector3d()
    @Transient private val shakeSpin = Vector3d()
    /** Zero until the first shake, so the first compare recomputes the inverse. */
    @Transient private val inertiaSeen = Matrix3d().zero()
    @Transient private val inertiaInverse = Matrix3d()
    @Transient private val rotationPd = RotationPd()
    /** Physics thread: the rate-capped rotation the PD aims at; it starts at the body's own rotation. */
    @Transient private val attitudeRef = Quaterniond()
    @Transient private var referenceValid = false
    @Transient private val refPrev = Quaterniond()
    @Transient private val refScratch = Quaterniond()
    /** World spin (rad/s) of [attitudeRef] this frame. */
    @Transient private val refSpin = Vector3d()
    @Transient private val residual = Vector3d()
    @Transient private val feet = FootForceSolver()
    @Transient private val bumpDir = Vector3d()
    @Transient private val bumpLateral = Vector3d()
    @Transient private val bumpNormal = Vector3d()
    /** The last contact set pushed off: a set arrives once per physics frame while it lasts. */
    @Transient private var bumpSeen: ConstriliarContacts.Snapshot? = null
    /** Physics frames the ride target stays lowered after the last top contact. */
    @Transient private var topHoldFrames = 0
    /** Physics-thread load ramp per leg, 0..1. */
    @Transient private val footLoad = DoubleArray(4)
    @Transient private val limited = Vector3d()
    // Physics thread only: the dimension gravity, read at the start of each physics tick.
    @Transient private var gravity = 0.0
    @Transient private var upset = false
    /** Load observer: the force each frame applied, and the velocity it acted on. */
    @Transient private val applied = Vector3d()
    @Transient private val prevVel = Vector3d()
    @Transient private var observed = false
    @Transient private var observedMass = 0.0
    /** Filtered outside force on the body (joints, ropes, pushes), world frame. */
    @Transient private val external = Vector3d()
    @Transient private val load = Vector3d()
    /** Seconds of exertion left; a limb past its normal hold spends it. */
    @Transient private var exertLeft = EXERT_SECONDS
    @Transient private var overLimit = false

    /** The feet hold past their normal limit this frame; the brain shows the strain. */
    @Transient
    @Volatile
    var exerting = false
        private set

    /** Outside force (N) the legs push against; the brain shows the strain. */
    @Transient
    @Volatile
    var outsideLoad = 0.0
        private set

    /** Diagnostics only: written on the physics thread, so it may be a frame old. */
    fun debugUpset(): Boolean = upset

    fun setPlatform(centre: Vector3dc) {
        platformX = centre.x(); platformY = centre.y(); platformZ = centre.z()
    }

    fun setUp(x: Double, y: Double, z: Double) {
        upX = x; upY = y; upZ = z
    }

    fun setMoveVel(v: Vector3dc) {
        moveVelX = v.x(); moveVelY = v.y(); moveVelZ = v.z()
    }

    /** Game thread: the deck under the body, or null, with its pose this tick. */
    fun setDeck(deck: Ship?) {
        if (deck == null) {
            deckShipId = -1L
            deckWorldToShip.identity()
            deckRotation.identity()
        } else {
            deckShipId = deck.id
            deckWorldToShip.set(deck.transform.worldToShip)
            deckRotation.set(deck.transform.shipToWorldRotation)
        }
    }

    fun setHover(x: Double, y: Double, z: Double) {
        hoverX = x; hoverY = y; hoverZ = z
        hoverActive = true
    }

    /** Game thread: makes the values written since the last call visible to the physics
     *  thread as one object, and counts as the heartbeat. */
    fun publish() {
        if (command?.let { publishedMatches(it) } != true) command = newCommand()
        active = true
        silentFrames = 0
    }

    private fun publishedMatches(c: Command): Boolean =
        c.platformX == platformX && c.platformY == platformY && c.platformZ == platformZ &&
            c.hoverX == hoverX && c.hoverY == hoverY && c.hoverZ == hoverZ && c.hoverActive == hoverActive &&
            c.upX == upX && c.upY == upY && c.upZ == upZ &&
            c.moveVelX == moveVelX && c.moveVelY == moveVelY && c.moveVelZ == moveVelZ &&
            c.idealRotation.x == idealRotation.x && c.idealRotation.y == idealRotation.y &&
            c.idealRotation.z == idealRotation.z && c.idealRotation.w == idealRotation.w &&
            c.strengthScale == strengthScale && c.strutsActive == strutsActive &&
            c.strutAnchorX.contentEquals(strutAnchorX) && c.strutAnchorY.contentEquals(strutAnchorY) &&
            c.strutAnchorZ.contentEquals(strutAnchorZ) && c.strutHipX.contentEquals(strutHipX) &&
            c.strutHipY.contentEquals(strutHipY) && c.strutHipZ.contentEquals(strutHipZ) &&
            c.strutStance.contentEquals(strutStance) && c.legOnly == legOnly && c.selfMass == selfMass &&
            c.legSystem == legSystem && c.legSwing.x() == legSwing.x() && c.legSwing.y() == legSwing.y() &&
            c.legSwing.z() == legSwing.z() && c.kneeX.contentEquals(kneeX) && c.kneeY.contentEquals(kneeY) &&
            c.kneeZ.contentEquals(kneeZ) && c.kneeContact.contentEquals(kneeContact) &&
            c.plannedTurn == plannedTurn && c.shaking == shaking && c.feelBumps == feelBumps &&
            c.deckShipId == deckShipId && c.deckWorldToShip == deckWorldToShip && c.deckRotation == deckRotation

    private fun newCommand(): Command =
        Command(
            platformX, platformY, platformZ, hoverX, hoverY, hoverZ, hoverActive,
            upX, upY, upZ, moveVelX, moveVelY, moveVelZ, Quaterniond(idealRotation),
            strengthScale, strutsActive,
            strutAnchorX.clone(), strutAnchorY.clone(), strutAnchorZ.clone(),
            strutHipX.clone(), strutHipY.clone(), strutHipZ.clone(), strutStance.clone(), legOnly, selfMass,
            legSystem, Vector3d(legSwing), kneeX.clone(), kneeY.clone(), kneeZ.clone(), kneeContact.clone(),
            plannedTurn, shaking, feelBumps, deckShipId, Matrix4d(deckWorldToShip), Quaterniond(deckRotation),
        )

    /** Game thread: one jump toward this world velocity (m/s); capped by JUMP_IMPULSE,
     *  so overloaded bodies jump weakly. */
    fun queueLaunch(x: Double, y: Double, z: Double) {
        pendingLaunch.set(Vector3d(x, y, z))
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) =
        physTick(physShip, physLevel, DEFAULT_STEP_SECONDS)

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel, delta: Double) {
        gravity = physLevel.gravity()
        val c = command
        if (physShip.mass <= 0.0) {
            observed = false
            referenceValid = false
            return
        }
        if (!active || silentFrames > HEARTBEAT_FRAMES || c == null) {
            observed = false
            referenceValid = false
            holdBrainless(physShip, physLevel, c)
            return
        }
        silentFrames++
        observeLoad(physShip, physicsStepSeconds(delta))

        physShip.transform.shipToWorld.transformPosition(plateWorld.set(c.platformX, c.platformY, c.platformZ))
        followDeck(physLevel, c)
        applyPendingLaunch(physShip, c, delta)
        bumpAway(physShip, c, physicsStepSeconds(delta))

        val force = bodyForce(physShip, c, bodyForceScratch)
        val torque = bodyTorque(physShip, c, physicsStepSeconds(delta), bodyTorqueScratch)
        if (c.shaking) pivotOnPlate(physShip, torque)
        val stance = c.hoverActive && c.strutsActive
        if (c.legSystem) {
            legDrive(physShip, c, force, torque, physicsStepSeconds(delta))
            return
        }
        if (LEGS_CARRY_WEIGHT && stance && !updateUpset(physShip, c)) {
            carry(physShip, c, force, torque, physicsStepSeconds(delta))
            return
        }
        footLoad.fill(0.0)
        if (stance) applyStruts(physShip, c, physShip.mass, physShip.velocity)
        push(physShip, Vector3d(force))
        physShip.applyWorldTorque(Vector3d(torque))
    }

    /** No brain drives the body (after a load the ship simulates before the entity
     *  ticks; a brain can stop while the ship stays loaded): hold it where it is. Limp,
     *  it sank until the thin plate's collision met the ground, a block deep, and was
     *  shoved out at speed, or fell through a crack. A released body is deleted. */
    private fun holdBrainless(physShip: PhysShip, physLevel: PhysLevel, c: Command?) {
        val mass = physShip.mass
        // On a deck it rests with the deck: damp toward the deck's motion at the centre of mass, not world rest.
        val d = if (c == null || c.deckShipId == -1L) null else physLevel.getShipById(c.deckShipId)
        val rest = if (d == null) deckPoint.zero() else d.pointVelocity(physShip.transform.positionInWorld, deckPoint)
        val v = relVel.set(physShip.velocity).sub(rest)
        physShip.applyWorldForceToBodyPos(
            Vector3d(-v.x * HOLD_DAMPING, gravity - v.y * HOLD_DAMPING, -v.z * HOLD_DAMPING).mul(mass),
        )
        val rotation = physShip.transform.shipToWorldRotation
        val spin = tmp.set(physShip.angularVelocity)
        if (d != null) spin.sub(d.angularVelocity)
        spin.mul(-HOLD_DAMPING)
        physShip.applyWorldTorque(physShip.momentOfInertia.transformInWorld(rotation, spin, Vector3d()))
    }

    /** Outside force = m·a − what this controller applied − gravity. VS2 integrates the
     *  last frame's forces into this frame's velocity. Contacts that push the body
     *  along up hold it, so they are not a load. The estimate leaks to zero: a
     *  blocked body reads its own push back as load, and without the leak that
     *  loop builds up without limit. A mass change (a split or a weld) starts over. */
    private fun observeLoad(physShip: PhysShip, dt: Double) {
        val vel = physShip.velocity
        val mass = physShip.mass
        if (observed && Math.abs(mass - observedMass) <= observedMass * MASS_CHANGE_SHARE) {
            tmp.set(vel).sub(prevVel).mul(mass / dt).sub(applied)
            tmp.y += mass * gravity
            tmp.sub(external).mul(Math.min(1.0, dt / LOAD_FILTER_SECONDS))
            external.add(tmp).mul(Math.max(0.0, 1.0 - dt / LOAD_LEAK_SECONDS))
        } else {
            external.zero()
        }
        prevVel.set(vel)
        applied.zero()
        observedMass = mass
        observed = true
    }

    /** A fall shake turns about the plate, not the centre of mass: the load swings above
     *  a body that stays put. The centre of mass gets the acceleration that holds the
     *  plate still under the turn: α × r + ω × (ω × r), r from the plate to it. */
    private fun pivotOnPlate(physShip: PhysShip, torque: Vector3d) {
        val rotation = physShip.transform.shipToWorldRotation
        val alpha = rotation.transformInverse(shakeAlpha.set(torque))
        val inertia = physShip.momentOfInertia
        if (!sameMatrix(inertia, inertiaSeen)) {
            inertiaSeen.set(inertia)
            inertiaInverse.set(inertia).invert()
        }
        inertiaInverse.transform(alpha)
        rotation.transform(alpha)
        val r = shakeArm.set(physShip.transform.positionInWorld).sub(plateWorld)
        val omega = physShip.angularVelocity
        val spin = omega.cross(omega.cross(r, shakeSpin), shakeSpin)
        val accel = alpha.cross(r).add(spin)
        val max = SHAKE_PIVOT_MAX_ACCEL
        if (accel.lengthSquared() > max * max) accel.normalize(max)
        push(physShip, Vector3d(accel).mul(physShip.mass))
    }

    /** The body feels each new set of VS2 contacts once and pushes off them in the surface
     *  plane: it rebounds by a share of the hit speed and resists the approach still left and
     *  the overlap. keepClear only steers the walk target, so the two do not add up. A top
     *  contact pushes down the same way and lowers the ride target while it lasts. */
    private fun bumpAway(physShip: PhysShip, c: Command, dt: Double) {
        if (!c.feelBumps) {
            topHoldFrames = 0
            return
        }
        if (topHoldFrames > 0) topHoldFrames--
        val snap = ConstriliarContacts.recent(physShip.id, BUMP_MAX_AGE_NANOS) ?: return
        if (snap === bumpSeen) return
        bumpSeen = snap
        up.set(cmdUp)
        val vel = relVel.set(physShip.velocity).sub(deckVel)
        val data = snap.data
        bumpDir.zero()
        var strongest = 0.0
        var top = 0.0
        for (k in 0 until snap.count) {
            if (snap.separation[k] > ConstriliarContacts.TOUCH_SEPARATION) continue
            val i = k * ConstriliarContacts.STRIDE
            val depth = Math.max(0.0, -snap.separation[k].toDouble())
            // A lasting contact repeats its speed in each PERSIST frame: only a new hit rebounds.
            val hit = if (data[i + ConstriliarContacts.START] > 0.0) data[i + ConstriliarContacts.SPEED] else 0.0
            val rebound = if (hit < BUMP_MIN_HIT) 0.0 else BUMP_REBOUND * hit / dt
            if (ConstriliarContacts.awayFromContact(data, i, plateWorld, up, bumpLateral, bumpNormal)) {
                val a = rebound + BUMP_DAMPING * Math.max(0.0, -vel.dot(bumpNormal)) + BUMP_STIFFNESS * depth
                if (a <= 0.0) continue
                bumpDir.fma(a, bumpNormal)
                strongest = Math.max(strongest, a)
            } else if (hit >= BUMP_MIN_HIT && ConstriliarContacts.isTopContact(data, i, plateWorld, up) &&
                !ConstriliarContacts.isWalkerBody(snap.other(k, physShip.id))
            ) {
                // A walker that lands on this one or rests on it is not a ceiling.
                topHoldFrames = TOP_HOLD_FRAMES
                top = Math.max(top, rebound + BUMP_DAMPING * Math.max(0.0, vel.dot(up)) + BUMP_STIFFNESS * depth)
            }
        }
        val cap = BUMP_FORCE_SHARE * LEG_STRENGTH * c.strengthScale
        val mass = physShip.mass
        val force = Vector3d()
        if (strongest > 0.0 && bumpDir.lengthSquared() >= 1.0e-8) {
            force.set(bumpDir).normalize(Math.min(mass * Math.min(strongest, BUMP_MAX_ACCEL), cap))
        }
        if (top > 0.0) force.fma(-Math.min(mass * Math.min(top, BUMP_MAX_ACCEL), cap), up)
        if (force.lengthSquared() > 0.0) push(physShip, force)
    }

    private fun sameMatrix(a: Matrix3dc, b: Matrix3dc): Boolean =
        a.m00() == b.m00() && a.m01() == b.m01() && a.m02() == b.m02() &&
            a.m10() == b.m10() && a.m11() == b.m11() && a.m12() == b.m12() &&
            a.m20() == b.m20() && a.m21() == b.m21() && a.m22() == b.m22()

    private fun push(physShip: PhysShip, f: Vector3d) {
        applied.add(f)
        physShip.applyWorldForceToBodyPos(f)
    }

    private fun pushAt(physShip: PhysShip, f: Vector3d, pos: Vector3d) {
        applied.add(f)
        physShip.applyWorldForce(f, pos)
    }

    /** Every body force comes from a contact: stance feet when upright, knees on the
     *  ground when upset. With neither, only the limbs' swing turns the body in the air. */
    private fun legDrive(physShip: PhysShip, c: Command, force: Vector3d, torque: Vector3d, dt: Double) {
        // Mid planned turn the feet that stand carry on: dropping the carry there let a
        // body with three feet planted fall off a rim.
        // Three planted feet inside the upset angle stand the body back up: in the band
        // the flag's hysteresis leaves, a body leant on a lip lay there for good.
        // On a wall or ceiling the knees have no ground to right it on: a dropped carry is a fall.
        val upright = !updateUpset(physShip, c) || (c.plannedTurn && standingFeet(c) >= 2) ||
            (standingFeet(c) >= 3 && upAgree >= UPSET_DOT) || (cmdUp.y < CONE_MIN_UP_Y && standingFeet(c) >= 2)
        if (upright && c.hoverActive && c.strutsActive) {
            carry(physShip, c, force, torque, dt)
            return
        }
        footLoad.fill(0.0)
        overLimit = false
        spendExertion(c, dt)
        if (c.legSwing.lengthSquared() > 0.0) pushAt(physShip, Vector3d(c.legSwing), Vector3d(plateWorld))
        if (upright || !kneeRight(physShip, c, torque)) airTurn(physShip, c, torque)
    }

    private fun standingFeet(c: Command): Int = c.strutStance.count { it }

    /** Swinging limbs give a falling body a little turn, as a cat rights itself. */
    private fun airTurn(physShip: PhysShip, c: Command, torque: Vector3d) {
        val cap = LEG_TORQUE * AIR_TORQUE_SHARE * c.strengthScale
        val mag = torque.length()
        physShip.applyWorldTorque(Vector3d(torque).mul(if (mag > cap) cap / mag else 1.0))
    }

    /** Upset: the knees that touch a surface push it away, through the same solver as
     *  the feet, to give the righting torque and part of the weight. */
    private fun kneeRight(physShip: PhysShip, c: Command, torque: Vector3d): Boolean {
        physShip.transform.shipToWorld.transformPosition(comWorld.set(physShip.transform.positionInShip))
        var count = 0
        for (i in 0..3) {
            val w = if (c.kneeContact[i]) 1.0 else 0.0
            feet.setFoot(i, kneeWX[i] - comWorld.x, kneeWY[i] - comWorld.y, kneeWZ[i] - comWorld.z, w)
            if (c.kneeContact[i]) count++
        }
        if (count == 0) return false
        val lift = physShip.mass * gravity * KNEE_LIFT_SHARE
        feet.solve(0.0, lift, 0.0, torque.x, torque.y, torque.z, 0.0, 1.0, 0.0, true, FOOT_FRICTION)
        for (i in 0..3) {
            val fx = feet.force[i * 3]
            val fy = feet.force[i * 3 + 1]
            val fz = feet.force[i * 3 + 2]
            if (fx == 0.0 && fy == 0.0 && fz == 0.0) continue
            pushAt(physShip, Vector3d(fx, fy, fz), Vector3d(kneeWX[i], kneeWY[i], kneeWZ[i]))
        }
        return true
    }

    /** Feet under a body on its side or back cannot roll it upright, so an upset body
     *  rights itself on the hover and the full rotation PD. */
    private fun updateUpset(physShip: PhysShip, c: Command): Boolean {
        physShip.transform.shipToWorldRotation.transform(tmp.set(0.0, 1.0, 0.0))
        val agree = tmp.dot(cmdUp)
        upAgree = agree
        upset = if (upset) agree < UPRIGHT_DOT else agree < UPSET_DOT
        return upset
    }

    private fun applyPendingLaunch(physShip: PhysShip, c: Command, delta: Double) {
        val launch = pendingLaunch.getAndSet(null) ?: return
        val impulse = launch.add(deckVel).sub(physShip.velocity).mul(physShip.mass)
        val mag = impulse.length()
        val impulseCap = JUMP_IMPULSE * c.strengthScale
        if (mag > impulseCap) impulse.mul(impulseCap / mag)
        push(physShip, impulse.mul(1.0 / physicsStepSeconds(delta)))
    }

    /** Reads the deck from VS2 and moves the command's world values with it since the brain wrote them, so the
     *  hover, feet, heading and drive hold to the deck between game ticks. */
    private fun followDeck(physLevel: PhysLevel, c: Command) {
        val d = if (c.deckShipId == -1L) null else physLevel.getShipById(c.deckShipId)
        deck = d
        if (d == null) {
            deckVel.zero()
            deckDelta.identity()
            deckRotDelta.identity()
        } else {
            d.pointVelocity(plateWorld, deckVel)
            deckMotion(d.transform.shipToWorld, c.deckWorldToShip, deckDelta)
            deckTurn(d.transform.shipToWorldRotation, c.deckRotation, deckRotDelta)
        }
        deckDelta.transformPosition(cmdHover.set(c.hoverX, c.hoverY, c.hoverZ))
        deckRotDelta.transform(cmdUp.set(c.upX, c.upY, c.upZ))
        deckRotDelta.transform(cmdMove.set(c.moveVelX, c.moveVelY, c.moveVelZ))
        deckRotDelta.mul(c.idealRotation, cmdRotation)
        for (i in 0..3) {
            deckDelta.transformPosition(deckPoint.set(c.strutAnchorX[i], c.strutAnchorY[i], c.strutAnchorZ[i]))
            anchorX[i] = deckPoint.x; anchorY[i] = deckPoint.y; anchorZ[i] = deckPoint.z
            deckDelta.transformPosition(deckPoint.set(c.kneeX[i], c.kneeY[i], c.kneeZ[i]))
            kneeWX[i] = deckPoint.x; kneeWY[i] = deckPoint.y; kneeWZ[i] = deckPoint.z
        }
    }

    /** World velocity of the deck at [point] into [out]; zero off a deck. */
    private fun deckVelocityAt(point: Vector3dc, out: Vector3d): Vector3d =
        deck?.pointVelocity(point, out) ?: out.zero()

    /** Hover PD + weight + in-plane velocity PD. Sets [up] for the leg code. */
    private fun bodyForce(physShip: PhysShip, c: Command, force: Vector3d): Vector3d {
        val mass = physShip.mass
        val vel = physShip.velocity
        force.zero()
        if (c.hoverActive) {
            up.set(cmdUp)
            // Under a top contact the ride target drops, so the hover gives way instead of pressing up.
            val drop = if (topHoldFrames > 0) TOP_DROP else 0.0
            val heightErr = tmp.set(cmdHover).sub(plateWorld).dot(up) - drop
            val hoverAccel = HOVER_STIFFNESS * heightErr - HOVER_DAMPING * (vel.dot(up) - deckVel.dot(up))
            force.set(up).mul(mass * hoverAccel)
            // Weight relief only while attached — airborne, gravity owns the ship.
            force.y += mass * gravity
            // Velocity PD in the surface plane; the along-normal component is stripped
            // so the drive never fights the hover spring.
            accel.set(cmdMove).add(deckVel).sub(vel).mul(MOVE_GAIN)
            accel.sub(tmp.set(up).mul(accel.dot(up)))
            val mag = accel.length()
            if (mag > MOVE_MAX_ACCEL) accel.mul(MOVE_MAX_ACCEL / mag)
            force.add(accel.mul(mass))
            if (c.legSystem) {
                // On its legs, the body pushes back against outside pulls as against its weight.
                load.set(external)
                val lift = load.dot(up)
                if (lift > 0.0 || topHoldFrames > 0) load.sub(tmp.set(up).mul(lift))
                force.sub(load)
            }
        }
        outsideLoad = if (c.hoverActive && c.legSystem) load.length() else 0.0
        // Legs have finite strength: the PD asks in accelerations (mass-proportional),
        // but delivery is capped in NEWTONS. A light load leaves huge headroom (snappy);
        // a heavy one eats the budget into weight-holding — it sags, accelerates
        // sluggishly, and past ~LEG_STRENGTH/g kg simply can't be carried.
        val legStrength = LEG_STRENGTH * c.strengthScale
        val forceMag = force.length()
        if (forceMag > legStrength) force.mul(legStrength / forceMag)
        return force
    }

    private fun bodyTorque(physShip: PhysShip, c: Command, dt: Double, out: Vector3d): Vector3d {
        val rotation = physShip.transform.shipToWorldRotation
        // A fall shake swings faster than the walking spring can follow (under 1 Hz): it
        // stiffens for it, the damping kept in proportion.
        val gain = if (c.shaking) SHAKE_STIFFNESS_GAIN else 1.0
        return rotationPd.torque(
            followReference(rotation, c, dt), rotation, physShip.angularVelocity, refSpin, physShip.momentOfInertia,
            ROTATION_STIFFNESS * gain, ROTATION_DAMPING * Math.sqrt(gain), LEG_TORQUE * c.strengthScale, out,
        )
    }

    /** The PD aims at a reference that turns toward the brain's target at a capped rate, and damps the spin
     *  relative to the reference's own spin ([refSpin]): a jump in the target cannot flip the body. */
    private fun followReference(rotation: Quaterniondc, c: Command, dt: Double): Quaterniondc {
        if (!referenceValid) {
            attitudeRef.set(rotation)
            referenceValid = true
        }
        if (c.shaking) {
            refSpin.zero()
            return attitudeRef.set(cmdRotation)
        }
        refPrev.set(attitudeRef)
        stepRotationToward(attitudeRef, cmdRotation, ROTATION_REF_MAX_RATE * dt)
        spinBetween(refPrev, attitudeRef, dt, refScratch, refSpin)
        return attitudeRef
    }

    /** The stance feet deliver the one body wrench; no second controller fights it.
     *  With [Command.legOnly] nothing else holds the body: no feet, no force. Else the
     *  hover holds the share under one foot's worth of load. Uses [up] as set by [bodyForce]. */
    private fun carry(physShip: PhysShip, c: Command, force: Vector3d, torque: Vector3d, dt: Double) {
        physShip.transform.shipToWorld.transformPosition(comWorld.set(physShip.transform.positionInShip))
        val total = updateFootLoads(physShip, c, dt)
        val share = if (c.legOnly) 1.0 else total.coerceAtMost(1.0)
        // Each foot pushes or pulls as the motion needs; [limitFoot] then holds it to
        // what a clawed foot can do.
        feet.solve(
            force.x * share, force.y * share, force.z * share,
            torque.x * share, torque.y * share, torque.z * share,
            up.x, up.y, up.z, false, FOOT_FRICTION,
        )
        // A foot holds well over the walker's own weight, so it crawls any face on two or
        // three feet; cargo is what can outweigh the grip.
        val grip = Math.max(FOOT_GRIP * c.strengthScale, c.selfMass * gravity * GRIP_SELF_WEIGHTS)
        val boost = if (exertLeft > 0.0 || c.shaking) EXERT_BOOST else 1.0
        overLimit = false
        residual.set(torque)
        for (i in 0..3) {
            if (feet.force[i * 3] == 0.0 && feet.force[i * 3 + 1] == 0.0 && feet.force[i * 3 + 2] == 0.0) continue
            val f = limitFoot(i, grip, boost)
            val foot = Vector3d(anchorX[i], anchorY[i], anchorZ[i])
            residual.sub(tmp.set(foot).sub(comWorld).cross(f))
            pushAt(physShip, Vector3d(f), foot)
        }
        if (share < 1.0) push(physShip, Vector3d(force).mul(1.0 - share))
        // The torque the stance cannot make (about a trot diagonal): leg-only, each
        // loaded foot's contact patch gives a little; else it is balance muscle.
        // A fall shake is the body's own twist, not a push through the feet: their small
        // moment cut it to a wiggle.
        val cap = if (c.shaking) {
            LEG_TORQUE * c.strengthScale
        } else if (c.legOnly) {
            FOOT_MOMENT * c.strengthScale * total
        } else {
            LEG_TORQUE * c.strengthScale * (1.0 - share * (1.0 - BALANCE_TORQUE_SHARE))
        }
        val mag = residual.length()
        if (c.legOnly && mag > cap) overLimit = true
        val held = if (c.legOnly) cap * boost else cap
        if (mag > held) residual.mul(held / mag)
        if (held > 0.0) physShip.applyWorldTorque(Vector3d(residual))
        spendExertion(c, dt)
    }

    /** Past the normal limits the feet give [EXERT_BOOST] for [EXERT_SECONDS] in all;
     *  the budget refills while the limits hold. */
    private fun spendExertion(c: Command, dt: Double) {
        if (c.shaking) {
            exerting = false
            return
        }
        if (overLimit) {
            exerting = exertLeft > 0.0
            exertLeft = Math.max(0.0, exertLeft - dt)
        } else {
            exerting = false
            exertLeft = Math.min(EXERT_SECONDS, exertLeft + dt * EXERT_SECONDS / EXERT_REFILL_SECONDS)
        }
    }

    /** Foot [leg]'s solved force, held to a clawed foot: push along [up] freely, pull
     *  off the surface up to [grip], slide sideways up to friction on the push plus
     *  [grip], each times [boost]. Sets [overLimit] when the plain limits clip. Into [limited]. */
    private fun limitFoot(leg: Int, grip: Double, boost: Double): Vector3d {
        val f = limited.set(feet.force[leg * 3], feet.force[leg * 3 + 1], feet.force[leg * 3 + 2])
        var normal = f.dot(up)
        val tx = f.x - up.x * normal
        val ty = f.y - up.y * normal
        val tz = f.z - up.z * normal
        val side = Math.sqrt(tx * tx + ty * ty + tz * tz)
        if (normal < -grip || side > FOOT_FRICTION * Math.max(normal, 0.0) + grip) overLimit = true
        val held = grip * boost
        if (normal < -held) normal = -held
        val sideCap = FOOT_FRICTION * Math.max(normal, 0.0) + held
        val k = if (side > sideCap) sideCap / side else 1.0
        return f.set(up).mul(normal).add(tx * k, ty * k, tz * k)
    }

    /** Ramps each foot's load toward its stance, and feeds the solver. A foot further
     *  than [LEG_REACH] from its hip cannot hold the body. Returns the summed load. */
    private fun updateFootLoads(physShip: PhysShip, c: Command, dt: Double): Double {
        val ramp = dt / LOAD_RAMP_SECONDS
        var total = 0.0
        for (i in 0..3) {
            physShip.transform.shipToWorld.transformPosition(hipWorld.set(c.strutHipX[i], c.strutHipY[i], c.strutHipZ[i]))
            val reach = hipWorld.distanceSquared(anchorX[i], anchorY[i], anchorZ[i])
            val target = if (c.strutStance[i] && reach <= LEG_REACH * LEG_REACH) 1.0 else 0.0
            footLoad[i] = if (footLoad[i] < target) {
                (footLoad[i] + ramp).coerceAtMost(target)
            } else {
                (footLoad[i] - ramp).coerceAtLeast(target)
            }
            feet.setFoot(
                i, anchorX[i] - comWorld.x, anchorY[i] - comWorld.y,
                anchorZ[i] - comWorld.z, footLoad[i],
            )
            total += footLoad[i]
        }
        return total
    }

    /** Uses [up] as set by [bodyForce]. */
    private fun applyStruts(physShip: PhysShip, c: Command, mass: Double, vel: Vector3dc) {
        val shipToWorld = physShip.transform.shipToWorld
        shipToWorld.transformPosition(comWorld.set(physShip.transform.positionInShip))
        val suppCap = SUPP_FORCE_CAP * c.strengthScale
        for (i in 0..3) {
            if (!c.strutStance[i]) continue
            shipToWorld.transformPosition(hipWorld.set(c.strutHipX[i], c.strutHipY[i], c.strutHipZ[i]))
            val r = hipArm.set(hipWorld).sub(comWorld)
            velHip.set(physShip.angularVelocity).cross(r).add(vel).sub(deckVelocityAt(hipWorld, deckPoint))
            val lenUp = tmp.set(hipWorld)
                .sub(anchorX[i], anchorY[i], anchorZ[i])
                .dot(up)
            // Bidirectional (a supplement may pull); rest matches the hover's
            // ride height so the layers agree at equilibrium.
            val springMag = ((mass / 4.0) *
                (SUPP_STIFFNESS * (STRUT_REST - lenUp) - SUPP_DAMPING * velHip.dot(up)))
                .coerceIn(-suppCap, suppCap)
            pushAt(physShip, Vector3d(up).mul(springMag), Vector3d(hipWorld))
        }
    }

    /**
     * Saves no data: a loaded body gets a controller with no command, so [holdBrainless]
     * holds it until the entity publishes. A transient controller left the body limp.
     */
    @JsonValue
    fun saveBytes(): ByteArray = AttachmentIo.encode { Serializer.serialize(this, it) }

    object Serializer {
        private const val FORMAT = 1

        fun serialize(attachment: ConstriliarShipController, output: java.io.DataOutput) {
            output.writeInt(FORMAT)
        }

        @Suppress("UNUSED_PARAMETER") // Marker only: there is nothing to read.
        fun deserialize(input: java.io.DataInput): ConstriliarShipController = ConstriliarShipController()
    }

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromSave(node: JsonNode): ConstriliarShipController =
            AttachmentIo.decode(node, ConstriliarShipController::class.java, { ConstriliarShipController() }, Serializer::deserialize)


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

        /** The stance feet carry the body wrench in place of the hover and the struts. */
        const val LEGS_CARRY_WEIGHT = true
        /** A foot's load moves 0↔1 over this time, shorter than a 2–4 tick swing. */
        const val LOAD_RAMP_SECONDS = 0.08
        const val FOOT_FRICTION = 1.4
        /** Steeper than about 45°, the feet grip: they may pull and do not slip. */
        const val CONE_MIN_UP_Y = 0.7
        /** Share of LEG_TORQUE left for torque the full stance cannot make. */
        const val BALANCE_TORQUE_SHARE = 0.25
        /** Leg-only: turning moment one loaded foot's contact patch can give (N·m). */
        const val FOOT_MOMENT = 4_000.0
        /** Hip to foot, short of the drawn leg's 1.79-block reach from its thigh pivot,
         *  so a planted foot is never drawn stretched short of the ground. */
        const val LEG_REACH = 1.65
        /** Least grip of one foot (N), scaled by body strength. */
        const val FOOT_GRIP = 12_000.0
        /** One foot's grip, in the walker's own weights. */
        const val GRIP_SELF_WEIGHTS = 1.5
        /** Exertion: grip and foot moment times this, for this many seconds in all. */
        const val EXERT_BOOST = 1.5
        /** How much stiffer the rotation spring is while a fall shake plays. */
        const val SHAKE_STIFFNESS_GAIN = 10.0
        /** Cap on the shake's pivot acceleration (m/s²). */
        const val SHAKE_PIVOT_MAX_ACCEL = 30.0
        const val EXERT_SECONDS = 1.0
        const val EXERT_REFILL_SECONDS = 3.0
        /** Load observer smoothing: slow enough to pass over collision impulses. */
        const val LOAD_FILTER_SECONDS = 0.3
        /** The estimate decays over this time, so it cannot build up against a contact. */
        const val LOAD_LEAK_SECONDS = 2.0
        /** A mass change past this share resets the load estimate. */
        const val MASS_CHANGE_SHARE = 0.05
        /** Share of LEG_TORQUE limb swing gives a body with nothing to push on. */
        const val AIR_TORQUE_SHARE = 0.1
        /** Share of the weight the knees lift while they roll an upset body. */
        const val KNEE_LIFT_SHARE = 0.5
        /** Upset past 60° from the target up; upright again inside 30°. */
        const val UPSET_DOT = 0.5
        const val UPRIGHT_DOT = 0.87

        /** Force budget in Newtons — the strain model. The 400 kg body alone needs
         *  ~4 kN against gravity; an iron block (~7 t) is carried without issue
         *  (~26 kN of headroom left for locomotion), and the budget dead-lifts 10 t. */
        const val LEG_STRENGTH = 100_000.0
        const val LEG_TORQUE = 60_000.0
        /** Jump impulse budget (N·s): a bare body launches crisply; near the carry
         *  limit the same spring barely hops. */
        const val JUMP_IMPULSE = 25_000.0
        /** Bump push: the rebound share of the VS2 hit speed (a 3 m/s walk bump backs off at
         *  0.45 m/s), from this hit speed up (below it is a rest contact); then the push per m/s
         *  of approach left (1/s) and per m of overlap (1/s²); one frame's cap (m/s²). */
        const val BUMP_REBOUND = 0.15
        const val BUMP_MIN_HIT = 1.0
        const val BUMP_DAMPING = 4.0
        const val BUMP_STIFFNESS = 20.0
        const val BUMP_MAX_ACCEL = 60.0
        /** Under a top contact the ride target drops this far (m), for this many frames after the last one. */
        const val TOP_DROP = 0.15
        const val TOP_HOLD_FRAMES = 30
        /** Share of LEG_STRENGTH the push may use, so a heavy load is not flung. */
        const val BUMP_FORCE_SHARE = 0.3
        private const val BUMP_MAX_AGE_NANOS = 50_000_000L
        private const val DEFAULT_STEP_SECONDS = 1.0 / 60.0
        private const val HEARTBEAT_FRAMES = 60
        /** Velocity and spin damping (1/s) of a body held with no brain. */
        private const val HOLD_DAMPING = 8.0

    }
}
