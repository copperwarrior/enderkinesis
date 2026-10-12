package org.shipwrights.enderkinesis.body

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.events.CollisionEvent
import org.valkyrienskies.mod.api.vsApi

/**
 * Hooks the VS2 phys-tick to drive every cage body's forces. Three
 * states layered by priority:
 *
 * 1. **Wylland Tome grab** — when a player has grabbed this body via
 *    the tome ([OrbGrabState]), counter-gravity + spring toward the
 *    tome target + rotation PD toward the grab's ideal rotation. Wins
 *    over the fade lock so the player can yank the cage out of its
 *    hover spot the instant it spawns.
 * 2. **Fade hover lock** — `!fadeComplete` and no tome grab:
 *    counter-gravity + stiff critically-damped spring toward
 *    [GamylanCageData.hoverPos]. Treats the body as effectively static
 *    without requiring `isStatic = true`.
 * 3. **Free fall** — `fadeComplete` and no tome grab: skip entirely;
 *    VS2's gravity application drops the cage.
 *
 * Tuning is borrowed wholesale from [OrbGravityCanceller]: the cage
 * body uses the same force inducers VS2 bodies have available in this
 * codebase, so the tome feels identical on a cage and on an Orb of
 * Potential. Any future force applier the mod adds for VS bodies (as
 * opposed to ships, which use attachment-based ForceInducers like
 * [org.shipwrights.enderkinesis.physics.WyllandTomeForceInducer]) can
 * be hooked into this loop the same way the tome branch is.
 */
object GamylanCageBodyForce {

    /** VS2 world gravity magnitude (m/s²). Matches [OrbGravityCanceller]. */
    private const val GRAVITY: Double = 10.0

    /** Spring stiffness (N / m / kg) used to pin the body to its
     *  hover position during the fade. High enough to keep the body
     *  effectively immovable under any plausible external nudge. */
    private const val LOCK_SPRING_K: Double = 40.0

    /** Critical damping for [LOCK_SPRING_K] — `c ≈ 2·sqrt(k)`. */
    private const val LOCK_DAMPING: Double = 12.0

    /** Tome-grab tuning — copied from [OrbGravityCanceller] so the
     *  tome feels identical on a cage and on an orb. */
    private const val TOME_SPRING_K: Double = 20.0
    private const val TOME_DAMPING: Double = 10.0
    private const val TOME_ROT_K: Double = 40.0
    private const val TOME_ROT_DAMPING: Double = 12.0

    /** Pedestal-alignment tuning — softer than the fade lock so the
     *  crystal drifts visibly into place rather than snapping. Rotation
     *  PD steers the body toward identity (upright); the position spring
     *  reuses the tome branch's numbers because they feel right for
     *  "come home" motion. */
    private const val PEDESTAL_SPRING_K: Double = 20.0
    private const val PEDESTAL_DAMPING: Double = 10.0
    private const val PEDESTAL_ROT_K: Double = 20.0
    private const val PEDESTAL_ROT_DAMPING: Double = 8.0

    /** Whole-body speed gate (m/s) — at least one of the two bodies in
     *  the contact must be moving this fast for any damage to fire.
     *  Blocks the "gently-touched cage on a dock" case and rejects
     *  spinner-tip contacts where neither COM is actually moving.
     *  Lower than the crystal-explosive's 20 m/s because the cage is
     *  meant to react to more modest impacts. */
    private const val IMPACT_SAFETY_SPEED_MPS: Double = 3.0

    /** Minimum contact-normal-component speed (m/s) for a contact to
     *  count as an approach. Tangential slides read as `|vN| ≈ 0` and
     *  are ignored below this floor. */
    private const val IMPACT_APPROACH_MIN_MPS: Double = 1.5

    /** Tolerated positive separation before a contact is treated as
     *  "already apart" — same value the crystal-explosive uses. Drops
     *  stale-manifold restart events without rejecting real glances. */
    private const val IMPACT_SEPARATION_EPSILON: Float = 0.05f

    /** Impulse (`effMass · |vN|`) below which the contact deals no
     *  damage. Roughly a 65 kg player at 5 m/s = 325 impulse — a
     *  running shoulder-check doesn't chip the crystal. */
    private const val IMPACT_IMPULSE_THRESHOLD: Double = 300.0

    /** Damage per impulse unit above [IMPACT_IMPULSE_THRESHOLD]. 1 dmg
     *  per 400 impulse units → a 15 m/s horse-charge (~5 000 impulse)
     *  ≈ 12 dmg; a full-mass 20 m/s ship-ram breaks the cage. */
    private const val IMPACT_DMG_PER_IMPULSE: Double = 1.0 / 400.0

    @Volatile
    private var registered: Boolean = false

    /** Pending impact damage per cage body, accumulated on the phys
     *  thread from [org.valkyrienskies.core.api.events.CollisionEvent]
     *  and drained by [GamylanCageManager] on the server thread via
     *  [drainImpactDamage] so NBT mutation + network broadcast stay
     *  MC-thread-safe. */
    private val pendingImpactDamage: ConcurrentHashMap<Long, AtomicInteger> = ConcurrentHashMap()

    /** Consumed from the server tick — returns and clears the impact
     *  damage that landed on [bodyId] since the last drain. */
    fun drainImpactDamage(bodyId: Long): Int {
        val counter = pendingImpactDamage[bodyId] ?: return 0
        return counter.getAndSet(0)
    }

    /** Purge per-body state when a cage is removed (break, stale sweep)
     *  — otherwise the maps would grow with every capture. */
    fun forgetBody(bodyId: Long) {
        pendingImpactDamage.remove(bodyId)
    }

    fun register() {
        if (registered) return
        registered = true
        @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
        vsApi.collisionStartEvent.on(::onCollisionStart)
        vsApi.physTickEvent.on { event, _ ->
            val physLevel = event.world
            val vsDim = physLevel.dimension
            val force = Vector3d()
            val torque = Vector3d()
            GamylanCageRegistry.forEachInVsDim(vsDim) { data ->
                val body = physLevel.getBodyById(data.bodyId) ?: return@forEachInVsDim
                val mass = body.inertiaData.mass
                if (mass <= 0.0) return@forEachInVsDim

                val pos = body.kinematics.position
                val vel = body.kinematics.velocity

                val tomeTarget = OrbGrabState.targetFor(data.bodyId)
                val jointed = data.jointId != null
                val pedestalActive = data.homePedestalPos != null
                when {
                    tomeTarget != null -> {
                        // Counter-gravity + spring to the tome target.
                        force.set(0.0, mass * GRAVITY, 0.0)
                        force.x += mass * (TOME_SPRING_K * (tomeTarget.x() - pos.x()) - TOME_DAMPING * vel.x())
                        force.y += mass * (TOME_SPRING_K * (tomeTarget.y() - pos.y()) - TOME_DAMPING * vel.y())
                        force.z += mass * (TOME_SPRING_K * (tomeTarget.z() - pos.z()) - TOME_DAMPING * vel.z())
                    }
                    jointed -> {
                        // Joint holds the pose; only cancel gravity so the
                        // solver doesn't spend budget fighting free-fall.
                        force.set(0.0, mass * GRAVITY, 0.0)
                    }
                    pedestalActive -> {
                        // Drift toward the pedestal home + apply rotation PD
                        // toward identity. Server tick sees rotation-in-range
                        // and issues the weld.
                        force.set(0.0, mass * GRAVITY, 0.0)
                        force.x += mass * (PEDESTAL_SPRING_K * (data.hoverPos.x - pos.x()) - PEDESTAL_DAMPING * vel.x())
                        force.y += mass * (PEDESTAL_SPRING_K * (data.hoverPos.y - pos.y()) - PEDESTAL_DAMPING * vel.y())
                        force.z += mass * (PEDESTAL_SPRING_K * (data.hoverPos.z - pos.z()) - PEDESTAL_DAMPING * vel.z())
                    }
                    !data.fadeComplete -> {
                        // Hover lock + counter-gravity during fade.
                        force.set(0.0, mass * GRAVITY, 0.0)
                        force.x += mass * (LOCK_SPRING_K * (data.hoverPos.x - pos.x()) - LOCK_DAMPING * vel.x())
                        force.y += mass * (LOCK_SPRING_K * (data.hoverPos.y - pos.y()) - LOCK_DAMPING * vel.y())
                        force.z += mass * (LOCK_SPRING_K * (data.hoverPos.z - pos.z()) - LOCK_DAMPING * vel.z())
                    }
                    else -> return@forEachInVsDim   // free fall
                }

                // `applyBodyForce` expects a body-frame vector.
                body.kinematics.rotation.transformInverse(force)
                body.applyBodyForce(force, Vector3d())

                // Rotation PD. Two flavours:
                //   • **Tome grab** — full-3D PD toward the tome's
                //     ideal quaternion, same as [OrbGravityCanceller].
                //   • **Pedestal drift** — up-vector PD only. Corrects
                //     tilt (pitch + roll around the pedestal's up axis)
                //     but leaves yaw untouched, so players can spin the
                //     crystal to face whichever way before it welds.
                // Inertia tensor is `diag(mass)` (see
                // GamylanCageManager's `tryCreateDynamicBody`), so the
                // `R · I · R⁻¹` sandwich collapses to a scalar multiply.
                val idealRot = OrbGrabState.idealRotationFor(data.bodyId)
                val curRot = body.kinematics.rotation
                val angVel = body.kinematics.angularVelocity
                when {
                    tomeTarget != null && idealRot != null -> {
                        val curRotInv = Quaterniond(curRot).invert()
                        val rotDiff = Quaterniond(idealRot).mul(curRotInv).normalize().invert()
                        torque.set(rotDiff.x * 2.0, rotDiff.y * 2.0, rotDiff.z * 2.0).mul(TOME_ROT_K)
                        if (rotDiff.w > 0.0) torque.mul(-1.0)
                        torque.x -= TOME_ROT_DAMPING * angVel.x()
                        torque.y -= TOME_ROT_DAMPING * angVel.y()
                        torque.z -= TOME_ROT_DAMPING * angVel.z()
                        torque.mul(mass)
                        curRot.transformInverse(torque)
                        body.applyBodyTorque(torque)
                    }
                    pedestalActive && !jointed -> {
                        // Target's world-up = target.rotation * (0,1,0).
                        val targetUp = Vector3d(0.0, 1.0, 0.0)
                        data.homeRotTarget.transform(targetUp)
                        // Body's world-up = curRot * (0,1,0).
                        val bodyUp = Vector3d(0.0, 1.0, 0.0)
                        curRot.transform(bodyUp)
                        // World-frame swing axis · sin(tilt) = bodyUp × targetUp.
                        // Rotating body about this axis by (tilt) sends bodyUp onto targetUp.
                        torque.set(
                            bodyUp.y * targetUp.z - bodyUp.z * targetUp.y,
                            bodyUp.z * targetUp.x - bodyUp.x * targetUp.z,
                            bodyUp.x * targetUp.y - bodyUp.y * targetUp.x,
                        ).mul(PEDESTAL_ROT_K)
                        // Damp only the tilt-rate component of angular
                        // velocity — leave the yaw-rate (angVel along
                        // targetUp) untouched so player-imparted spin
                        // decays only in tilt.
                        val yawRate = angVel.x() * targetUp.x + angVel.y() * targetUp.y + angVel.z() * targetUp.z
                        val swingX = angVel.x() - yawRate * targetUp.x
                        val swingY = angVel.y() - yawRate * targetUp.y
                        val swingZ = angVel.z() - yawRate * targetUp.z
                        torque.x -= PEDESTAL_ROT_DAMPING * swingX
                        torque.y -= PEDESTAL_ROT_DAMPING * swingY
                        torque.z -= PEDESTAL_ROT_DAMPING * swingZ
                        torque.mul(mass)
                        curRot.transformInverse(torque)
                        body.applyBodyTorque(torque)
                    }
                }
            }
        }
    }

    /** VS Core collision-start callback. Mirrors the filter shape used
     *  by [org.shipwrights.enderkinesis.blockentity.CrystalExplosiveCollisionRouter]:
     *
     *   1. **Whole-body safety-speed gate** — at least one body's COM
     *      must be moving faster than [IMPACT_SAFETY_SPEED_MPS]. Kills
     *      the "spinner tip whipping past a stationary cage" false hit.
     *   2. **Per-contact separation gate** — reject stale manifold-
     *      restart events whose `separation > EPSILON`.
     *   3. **Contact-normal projection** — take `|v·n|` so pure
     *      tangential slides (huge `|v|`, near-zero `|vN|`) don't
     *      register. Sign-agnostic because VS2 doesn't guarantee A-vs-B
     *      body ordering.
     *   4. **Reduced-mass impulse** — `effMass = mA·mB/(mA+mB)` scales
     *      the impact by both bodies' inertia; a chicken bumping the
     *      cage delivers a fraction of what a ship-ram does at the same
     *      speed. Damage is a linear ramp above
     *      [IMPACT_IMPULSE_THRESHOLD].
     *
     *  Queued to [pendingImpactDamage] for the server tick to drain
     *  (NBT mutation + broadcasts stay on the MC thread). */
    @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
    private fun onCollisionStart(event: CollisionEvent) {
        val a = event.shipIdA
        val b = event.shipIdB
        var aIsCage = false
        var bIsCage = false
        GamylanCageRegistry.forEachInVsDim(event.dimensionId) { data ->
            if (data.bodyId == a) aIsCage = true
            if (data.bodyId == b) bIsCage = true
        }
        if (!aIsCage && !bIsCage) return

        val bodyA = event.physLevel.getBodyById(a)
        val bodyB = event.physLevel.getBodyById(b)
        val mA = bodyA?.inertiaData?.mass ?: 0.0
        val mB = bodyB?.inertiaData?.mass ?: 0.0
        val effMass = when {
            mA > 0.0 && mB > 0.0 -> (mA * mB) / (mA + mB)
            else -> Math.max(mA, mB)
        }
        if (effMass <= 0.0) return

        val speedA = bodyA?.kinematics?.velocity?.let { sqrt(it.x()*it.x() + it.y()*it.y() + it.z()*it.z()) } ?: 0.0
        val speedB = bodyB?.kinematics?.velocity?.let { sqrt(it.x()*it.x() + it.y()*it.y() + it.z()*it.z()) } ?: 0.0
        if (Math.max(speedA, speedB) < IMPACT_SAFETY_SPEED_MPS) return

        var peakImpulse = 0.0
        for (contact in event.contactPoints) {
            if (contact.separation > IMPACT_SEPARATION_EPSILON) continue
            val v = contact.velocity
            val n = contact.normal
            val approach = Math.abs(v.x() * n.x() + v.y() * n.y() + v.z() * n.z())
            if (approach < IMPACT_APPROACH_MIN_MPS) continue
            val impulse = effMass * approach
            if (impulse > peakImpulse) peakImpulse = impulse
        }
        if (peakImpulse < IMPACT_IMPULSE_THRESHOLD) return

        val dmg = 1 + ((peakImpulse - IMPACT_IMPULSE_THRESHOLD) * IMPACT_DMG_PER_IMPULSE).toInt()
        if (aIsCage) pendingImpactDamage.computeIfAbsent(a) { AtomicInteger(0) }.addAndGet(dmg)
        if (bIsCage) pendingImpactDamage.computeIfAbsent(b) { AtomicInteger(0) }.addAndGet(dmg)
    }

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        pendingImpactDamage.clear()
    }
}
