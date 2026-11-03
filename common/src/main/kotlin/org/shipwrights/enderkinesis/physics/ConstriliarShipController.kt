package org.shipwrights.enderkinesis.physics

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.JsonNode
import org.joml.Matrix3d
import org.joml.Matrix3dc
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel

/**
 * The Constriliar's body IS this ship; the entity is the brain riding it. One body
 * wrench (ride-height PD + weight, in-plane velocity PD, rotation PD with the R·I·R⁻¹
 * sandwich, see [WyllandTomeForceInducer]) is delivered only through contacts: the
 * stance feet, or the knees of an upset body. Swimming and the spawn intro keep the
 * hover, which applies the wrench to the body directly.
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
    @Transient private val hipArm = Vector3d()
    @Transient private val shakeAlpha = Vector3d()
    @Transient private val shakeArm = Vector3d()
    @Transient private val shakeSpin = Vector3d()
    /** Zero until the first shake, so the first compare recomputes the inverse. */
    @Transient private val inertiaSeen = Matrix3d().zero()
    @Transient private val inertiaInverse = Matrix3d()
    @Transient private val rotInverse = Quaterniond()
    @Transient private val rotDiff = Quaterniond()
    @Transient private val rotDiffVector = Vector3d()
    @Transient private val residual = Vector3d()
    @Transient private val feet = FootForceSolver()
    /** Physics-thread load ramp per leg, 0..1. */
    @Transient private val footLoad = DoubleArray(4)
    @Transient private val limited = Vector3d()
    @Transient private var upset = false
    /** Seconds of exertion left; a limb past its normal hold spends it. */
    /** Load observer: the force each frame applied, and the velocity it acted on. */
    @Transient private val applied = Vector3d()
    @Transient private val prevVel = Vector3d()
    @Transient private var observed = false
    @Transient private var observedMass = 0.0
    /** Filtered outside force on the body (joints, ropes, pushes), world frame. */
    @Transient private val external = Vector3d()
    @Transient private val load = Vector3d()
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
            strutHipX.clone(), strutHipY.clone(), strutHipZ.clone(), strutStance.clone(), legOnly, selfMass,
            legSystem, Vector3d(legSwing), kneeX.clone(), kneeY.clone(), kneeZ.clone(), kneeContact.clone(),
            plannedTurn, shaking,
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
        val c = command
        if (physShip.mass <= 0.0) {
            observed = false
            return
        }
        if (!active || silentFrames > HEARTBEAT_FRAMES || c == null) {
            observed = false
            holdBrainless(physShip)
            return
        }
        silentFrames++
        observeLoad(physShip, physicsStepSeconds(delta))

        physShip.transform.shipToWorld.transformPosition(plateWorld.set(c.platformX, c.platformY, c.platformZ))
        applyPendingLaunch(physShip, c, delta)

        val force = bodyForce(physShip, c)
        val torque = bodyTorque(physShip, c)
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
        push(physShip, force)
        physShip.applyInvariantTorque(torque)
    }

    /** No brain drives the body (after a load the ship simulates before the entity
     *  ticks; a brain can stop while the ship stays loaded): hold it where it is. Limp,
     *  it sank until the thin plate's collision met the ground, a block deep, and was
     *  shoved out at speed, or fell through a crack. A released body is deleted. */
    private fun holdBrainless(physShip: PhysShip) {
        val mass = physShip.mass
        val v = physShip.velocity
        physShip.applyInvariantForce(
            Vector3d(-v.x() * HOLD_DAMPING, GRAVITY - v.y() * HOLD_DAMPING, -v.z() * HOLD_DAMPING).mul(mass),
        )
        val rotation = physShip.transform.shipToWorldRotation
        val spin = tmp.set(physShip.omega).mul(-HOLD_DAMPING)
        val torque = Vector3d()
        rotation.transformInverse(spin, torque)
        physShip.momentOfInertia.transform(torque)
        rotation.transform(torque)
        physShip.applyInvariantTorque(torque)
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
            tmp.y += mass * GRAVITY
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
        val omega = physShip.omega
        val spin = omega.cross(omega.cross(r, shakeSpin), shakeSpin)
        val accel = alpha.cross(r).add(spin)
        val max = SHAKE_PIVOT_MAX_ACCEL
        if (accel.lengthSquared() > max * max) accel.normalize(max)
        push(physShip, Vector3d(accel).mul(physShip.mass))
    }

    private fun sameMatrix(a: Matrix3dc, b: Matrix3dc): Boolean =
        a.m00() == b.m00() && a.m01() == b.m01() && a.m02() == b.m02() &&
            a.m10() == b.m10() && a.m11() == b.m11() && a.m12() == b.m12() &&
            a.m20() == b.m20() && a.m21() == b.m21() && a.m22() == b.m22()

    private fun push(physShip: PhysShip, f: Vector3d) {
        applied.add(f)
        physShip.applyInvariantForce(f)
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
        val upright = !updateUpset(physShip, c) || (c.plannedTurn && standingFeet(c) >= 2) ||
            (standingFeet(c) >= 3 && upAgree >= UPSET_DOT)
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
        physShip.applyInvariantTorque(Vector3d(torque).mul(if (mag > cap) cap / mag else 1.0))
    }

    /** Upset: the knees that touch a surface push it away, through the same solver as
     *  the feet, to give the righting torque and part of the weight. */
    private fun kneeRight(physShip: PhysShip, c: Command, torque: Vector3d): Boolean {
        physShip.transform.shipToWorld.transformPosition(comWorld.set(physShip.transform.positionInShip))
        var count = 0
        for (i in 0..3) {
            val w = if (c.kneeContact[i]) 1.0 else 0.0
            feet.setFoot(i, c.kneeX[i] - comWorld.x, c.kneeY[i] - comWorld.y, c.kneeZ[i] - comWorld.z, w)
            if (c.kneeContact[i]) count++
        }
        if (count == 0) return false
        val lift = physShip.mass * GRAVITY * KNEE_LIFT_SHARE
        feet.solve(0.0, lift, 0.0, torque.x, torque.y, torque.z, 0.0, 1.0, 0.0, true, FOOT_FRICTION)
        for (i in 0..3) {
            val fx = feet.force[i * 3]
            val fy = feet.force[i * 3 + 1]
            val fz = feet.force[i * 3 + 2]
            if (fx == 0.0 && fy == 0.0 && fz == 0.0) continue
            pushAt(physShip, Vector3d(fx, fy, fz), Vector3d(c.kneeX[i], c.kneeY[i], c.kneeZ[i]))
        }
        return true
    }

    /** Feet under a body on its side or back cannot roll it upright, so an upset body
     *  rights itself on the hover and the full rotation PD. */
    private fun updateUpset(physShip: PhysShip, c: Command): Boolean {
        physShip.transform.shipToWorldRotation.transform(tmp.set(0.0, 1.0, 0.0))
        val agree = tmp.x * c.upX + tmp.y * c.upY + tmp.z * c.upZ
        upAgree = agree
        upset = if (upset) agree < UPRIGHT_DOT else agree < UPSET_DOT
        return upset
    }

    private fun applyPendingLaunch(physShip: PhysShip, c: Command, delta: Double) {
        val launch = pendingLaunch.getAndSet(null) ?: return
        val impulse = launch.sub(physShip.velocity).mul(physShip.mass)
        val mag = impulse.length()
        val impulseCap = JUMP_IMPULSE * c.strengthScale
        if (mag > impulseCap) impulse.mul(impulseCap / mag)
        push(physShip, impulse.mul(1.0 / physicsStepSeconds(delta)))
    }

    /** Hover PD + weight + in-plane velocity PD. Sets [up] for the leg code. */
    private fun bodyForce(physShip: PhysShip, c: Command): Vector3d {
        val mass = physShip.mass
        val vel = physShip.velocity
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
            if (c.legSystem) {
                // On its legs, the body pushes back against outside pulls as against its weight.
                load.set(external)
                val lift = load.dot(up)
                if (lift > 0.0) load.sub(tmp.set(up).mul(lift))
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

    private fun bodyTorque(physShip: PhysShip, c: Command): Vector3d {
        val rotation = physShip.transform.shipToWorldRotation
        // Quaternion error → small-angle axis vector → PD; see WyllandTomeForceInducer
        // for the derivation and the shortest-arc sign flip.
        c.idealRotation.mul(rotation.invert(rotInverse), rotDiff).normalize().invert()
        rotDiffVector.set(rotDiff.x * 2.0, rotDiff.y * 2.0, rotDiff.z * 2.0)
        if (rotDiff.w > 0.0) rotDiffVector.negate()
        // A fall shake swings faster than the walking spring can follow (under 1 Hz): it
        // stiffens for it, the damping kept in proportion.
        val gain = if (c.shaking) SHAKE_STIFFNESS_GAIN else 1.0
        rotDiffVector.mul(ROTATION_STIFFNESS * gain).sub(tmp.set(physShip.omega).mul(ROTATION_DAMPING * Math.sqrt(gain)))

        val torque = Vector3d()
        rotation.transformInverse(rotDiffVector, torque)
        physShip.momentOfInertia.transform(torque)
        rotation.transform(torque)
        val legTorque = LEG_TORQUE * c.strengthScale
        val torqueMag = torque.length()
        if (torqueMag > legTorque) torque.mul(legTorque / torqueMag)
        return torque
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
        val grip = Math.max(FOOT_GRIP * c.strengthScale, c.selfMass * GRAVITY * GRIP_SELF_WEIGHTS)
        val boost = if (exertLeft > 0.0 || c.shaking) EXERT_BOOST else 1.0
        overLimit = false
        residual.set(torque)
        for (i in 0..3) {
            if (feet.force[i * 3] == 0.0 && feet.force[i * 3 + 1] == 0.0 && feet.force[i * 3 + 2] == 0.0) continue
            val f = limitFoot(i, grip, boost)
            val foot = Vector3d(c.strutAnchorX[i], c.strutAnchorY[i], c.strutAnchorZ[i])
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
        if (held > 0.0) physShip.applyInvariantTorque(Vector3d(residual))
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
            val reach = hipWorld.distanceSquared(c.strutAnchorX[i], c.strutAnchorY[i], c.strutAnchorZ[i])
            val target = if (c.strutStance[i] && reach <= LEG_REACH * LEG_REACH) 1.0 else 0.0
            footLoad[i] = if (footLoad[i] < target) {
                (footLoad[i] + ramp).coerceAtMost(target)
            } else {
                (footLoad[i] - ramp).coerceAtLeast(target)
            }
            feet.setFoot(
                i, c.strutAnchorX[i] - comWorld.x, c.strutAnchorY[i] - comWorld.y,
                c.strutAnchorZ[i] - comWorld.z, footLoad[i],
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
            velHip.set(physShip.omega).cross(r).add(vel)
            val lenUp = tmp.set(hipWorld)
                .sub(c.strutAnchorX[i], c.strutAnchorY[i], c.strutAnchorZ[i])
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
        const val GRAVITY = 10.0

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
        private const val DEFAULT_STEP_SECONDS = 1.0 / 60.0
        private const val HEARTBEAT_FRAMES = 60
        /** Velocity and spin damping (1/s) of a body held with no brain. */
        private const val HOLD_DAMPING = 8.0

    }
}
