package org.shipwrights.enderkinesis.body

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.util.gravity
import org.shipwrights.enderkinesis.util.movableMass
import org.shipwrights.enderkinesis.util.pointVelocity
import org.shipwrights.enderkinesis.util.reducedMass
import org.valkyrienskies.core.api.events.CollisionEvent
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.util.UnavailableInKrunch

/** The tome grab wins over the fade lock, so a player can pull a cage out of its hover spot at once. */
object GamylanCageBodyForce {

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

    /** The fastest START contact normal speed (m/s) must reach this for any damage, so a gentle touch on a
     *  dock does nothing. Lower than the crystal explosive's 10 m/s: the cage reacts to smaller impacts. */
    private const val IMPACT_SAFETY_SPEED_MPS: Double = 3.0

    /** Minimum contact-normal-component speed (m/s) for a contact to
     *  count as an approach. Tangential slides read as `|vN| ≈ 0` and
     *  are ignored below this floor. */
    private const val IMPACT_APPROACH_MIN_MPS: Double = 1.5

    /** Tolerated positive separation before a contact is treated as
     *  "already apart" — same value the crystal-explosive uses. Drops
     *  stale-manifold restart events without rejecting real glances. */
    private const val IMPACT_SEPARATION_EPSILON: Float = 0.05f

    /** Impulse (`effMass · |vN|`) below which the contact deals no damage. */
    private const val IMPACT_IMPULSE_THRESHOLD: Double = 850.0

    /** Damage per impulse unit above [IMPACT_IMPULSE_THRESHOLD]. */
    private const val IMPACT_DMG_PER_IMPULSE: Double = 1.0 / 1130.0

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

    // Subscribes on the game thread; the callbacks run on the physics thread.
    @OptIn(PhysTickOnly::class, VsBeta::class, UnavailableInKrunch::class)
    fun register() {
        if (registered) return
        registered = true
        vsApi.collisionStartEvent.on(::onCollision)
        // A cage already in contact (resting on a deck, pinned against terrain) takes later hits as PERSIST.
        vsApi.collisionPersistEvent.on(::onCollision)
        vsApi.physTickEvent.on { event, _ ->
            val physLevel = event.world
            val vsDim = physLevel.dimension
            val gravity = physLevel.gravity()
            val force = Vector3d()
            val torque = Vector3d()
            val lockTarget = Vector3d()
            val lockVel = Vector3d()
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
                        force.set(0.0, mass * gravity, 0.0)
                        force.x += mass * (TOME_SPRING_K * (tomeTarget.x() - pos.x()) - TOME_DAMPING * vel.x())
                        force.y += mass * (TOME_SPRING_K * (tomeTarget.y() - pos.y()) - TOME_DAMPING * vel.y())
                        force.z += mass * (TOME_SPRING_K * (tomeTarget.z() - pos.z()) - TOME_DAMPING * vel.z())
                    }
                    jointed -> {
                        // Joint holds the pose; only cancel gravity so the
                        // solver doesn't spend budget fighting free-fall.
                        force.set(0.0, mass * gravity, 0.0)
                    }
                    pedestalActive -> {
                        // Drift toward the pedestal home + apply rotation PD
                        // toward identity. Server tick sees rotation-in-range
                        // and issues the weld.
                        force.set(0.0, mass * gravity, 0.0)
                        force.x += mass * (PEDESTAL_SPRING_K * (data.hoverPos.x - pos.x()) - PEDESTAL_DAMPING * vel.x())
                        force.y += mass * (PEDESTAL_SPRING_K * (data.hoverPos.y - pos.y()) - PEDESTAL_DAMPING * vel.y())
                        force.z += mass * (PEDESTAL_SPRING_K * (data.hoverPos.z - pos.z()) - PEDESTAL_DAMPING * vel.z())
                    }
                    !data.fadeComplete -> {
                        // On a ship the lock point moves with it; damp toward the ship's velocity, not toward rest.
                        val carrier = data.carrierShipId?.let { physLevel.getShipById(it) }
                        val local = data.hoverInShip
                        if (carrier != null && local != null) {
                            carrier.transform.shipToWorld.transformPosition(local, lockTarget)
                            carrier.pointVelocity(lockTarget, lockVel)
                        } else {
                            lockTarget.set(data.hoverPos)
                            lockVel.zero()
                        }
                        force.set(0.0, mass * gravity, 0.0)
                        force.x += mass * (LOCK_SPRING_K * (lockTarget.x - pos.x()) - LOCK_DAMPING * (vel.x() - lockVel.x))
                        force.y += mass * (LOCK_SPRING_K * (lockTarget.y - pos.y()) - LOCK_DAMPING * (vel.y() - lockVel.y))
                        force.z += mass * (LOCK_SPRING_K * (lockTarget.z - pos.z()) - LOCK_DAMPING * (vel.z() - lockVel.z))
                    }
                    !data.terrainReady -> {
                        force.set(0.0, mass * gravity, 0.0)
                        force.x -= mass * LOCK_DAMPING * vel.x()
                        force.y -= mass * LOCK_DAMPING * vel.y()
                        force.z -= mass * LOCK_DAMPING * vel.z()
                    }
                    else -> return@forEachInVsDim   // free fall
                }

                // `applyBodyForce` expects a body-frame vector.
                body.kinematics.rotation.transformInverse(force)
                body.applyBodyForce(Vector3d(force), Vector3d())

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
                        body.applyBodyTorque(Vector3d(torque))
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
                        body.applyBodyTorque(Vector3d(torque))
                    }
                }
            }
        }
    }

    /** Physics thread: queue damage only; the server tick drains it, so NBT and broadcasts stay on the MC thread.
     *  Take `|v·n|`: VS2 does not fix which body is A. Speed is checked first, so resting contacts cost no lookups. */
    @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
    private fun onCollision(event: CollisionEvent) {
        var peakApproach = 0.0
        for (contact in event.contactPoints) {
            if (contact.separation > IMPACT_SEPARATION_EPSILON) continue
            val v = contact.velocity
            val n = contact.normal
            val approach = Math.abs(v.x() * n.x() + v.y() * n.y() + v.z() * n.z())
            if (approach < IMPACT_APPROACH_MIN_MPS) continue
            if (approach > peakApproach) peakApproach = approach
        }
        if (peakApproach < IMPACT_SAFETY_SPEED_MPS) return

        val a = event.shipIdA
        val b = event.shipIdB
        val aIsCage = GamylanCageRegistry.isCageIn(event.dimensionId, a)
        val bIsCage = GamylanCageRegistry.isCageIn(event.dimensionId, b)
        if (!aIsCage && !bIsCage) return

        val effMass = reducedMass(event.physLevel.movableMass(a), event.physLevel.movableMass(b))
        val peakImpulse = effMass * peakApproach
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
