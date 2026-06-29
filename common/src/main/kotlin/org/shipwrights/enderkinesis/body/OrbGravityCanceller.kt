package org.shipwrights.enderkinesis.body

import net.minecraft.server.level.ServerLevel
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.dimensionId
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side registry of orb body IDs + each body's anchor world
 * position. Backed by per-level [OrbWorldData] for save-file
 * persistence; an in-memory mirror keyed by `Level.dimensionId` is
 * kept alongside so the phys-tick read path doesn't have to touch
 * MC SavedData from outside the game thread.
 *
 *  Server-main thread calls [add] / [remove] (via the block placement
 *  hook). Phys thread calls [forEachIn] (read-only). The mirror is a
 *  [ConcurrentHashMap]; reads tolerate a one-tick stale view.
 */
object OrbBodyRegistry {

    private val byDimension: MutableMap<String, MutableMap<Long, Vector3d>> = ConcurrentHashMap()

    fun add(level: ServerLevel, bodyId: Long, anchor: Vector3dc) {
        if (bodyId == 0L) return
        OrbWorldData.get(level).put(bodyId, anchor)
        memMirror(level.dimensionId)[bodyId] = Vector3d(anchor)
        OrbBodyNetwork.broadcastAdded(level, bodyId, anchor)
    }

    fun remove(level: ServerLevel, bodyId: Long) {
        if (bodyId == 0L) return
        OrbWorldData.get(level).remove(bodyId)
        byDimension[level.dimensionId]?.remove(bodyId)
        OrbBodyNetwork.broadcastRemoved(level, bodyId)
    }

    fun rehydrate(level: ServerLevel) {
        val data = OrbWorldData.get(level)
        val mirror = memMirror(level.dimensionId)
        mirror.clear()
        for ((id, anchor) in data.snapshot()) {
            mirror[id] = Vector3d(anchor)
        }
    }

    fun forEachIn(dimensionId: String, action: (Long, Vector3dc) -> Unit) {
        byDimension[dimensionId]?.forEach { (id, anchor) -> action(id, anchor) }
    }

    fun contains(level: ServerLevel, bodyId: Long): Boolean =
        OrbWorldData.get(level).contains(bodyId)

    private fun memMirror(dimensionId: String): MutableMap<Long, Vector3d> =
        byDimension.computeIfAbsent(dimensionId) { ConcurrentHashMap() }
}

/** Hooks the global phys-tick event for each orb body:
 *    1. Cancels gravity (+mass·g straight up).
 *    2. Gently springs the body back to its anchor pos if within
 *       [ANCHOR_PULL_RANGE] blocks. Critically-damped spring so the
 *       body returns smoothly without oscillation.
 *
 *  Tuning: spring is intentionally soft so the orb doesn't snap back
 *  the instant a player nudges it — they should be able to push it
 *  around for a couple seconds before it drifts home. Outside the
 *  range the orb is "free", no pull at all. */
object OrbGravityCanceller {

    /** VS2 world gravity magnitude (m/s²). Matches [VoidGravityController]. */
    private const val GRAVITY: Double = 10.0
    /** Outside this radius (blocks = m) we don't pull at all. */
    private const val ANCHOR_PULL_RANGE: Double = 32.0
    /** Anchor spring stiffness `k` (N / m / kg). Acceleration toward
     *  anchor is `k · distance`, so at the 32-block edge the pull is
     *  `0.5 · 32 = 16 m/s²` — about 1.6× gravity, enough to recover
     *  without slamming. */
    private const val SPRING_K: Double = 0.5
    /** Anchor damping `c` (1 / s). Critically damped against
     *  `SPRING_K`: `c ≈ 2·sqrt(k) ≈ 1.4`. Tuned a hair under so the
     *  return has a faint coast at the end. */
    private const val DAMPING: Double = 1.2

    /** Wylland Tome grab spring stiffness (per kg). Matches the
     *  existing [WyllandTomeForceInducer.POSITION_STIFFNESS] so the
     *  tome's pull feels the same on orbs as it does on ships. */
    private const val TOME_SPRING_K: Double = 20.0
    /** Wylland Tome grab damping. Matches
     *  [WyllandTomeForceInducer.POSITION_DAMPING]. */
    private const val TOME_DAMPING: Double = 10.0
    /** Wylland Tome rotation stiffness. Matches
     *  [WyllandTomeForceInducer.ROTATION_STIFFNESS]. */
    private const val TOME_ROT_K: Double = 40.0
    /** Wylland Tome rotation damping. Matches
     *  [WyllandTomeForceInducer.ROTATION_DAMPING]. */
    private const val TOME_ROT_DAMPING: Double = 12.0

    @Volatile private var registered: Boolean = false

    fun register() {
        if (registered) return
        registered = true
        vsApi.physTickEvent.on { event, _ ->
            val physLevel = event.world
            val dim = physLevel.dimension
            val forceScratch = Vector3d()
            val toAnchor = Vector3d()
            val torqueScratch = Vector3d()
            OrbBodyRegistry.forEachIn(dim) { bodyId, anchor ->
                val body = physLevel.getBodyById(bodyId) ?: return@forEachIn
                val mass = body.inertiaData.mass
                if (mass <= 0.0) return@forEachIn

                // Counter-gravity.
                forceScratch.set(0.0, mass * GRAVITY, 0.0)

                val pos = body.kinematics.position
                val vel = body.kinematics.velocity

                // Wylland Tome grab takes priority over the home spring
                // — the player is actively positioning the orb, so the
                // home-spring shouldn't fight them. When no tome grab
                // is active, fall back to the anchor pull-back.
                val tomeTarget = OrbGrabState.targetFor(bodyId)
                if (tomeTarget != null) {
                    toAnchor.set(tomeTarget).sub(pos)
                    forceScratch.x += mass * (TOME_SPRING_K * toAnchor.x - TOME_DAMPING * vel.x())
                    forceScratch.y += mass * (TOME_SPRING_K * toAnchor.y - TOME_DAMPING * vel.y())
                    forceScratch.z += mass * (TOME_SPRING_K * toAnchor.z - TOME_DAMPING * vel.z())
                } else {
                    // Spring pull-back when within range. `toAnchor` is
                    // anchor - current position. Acceleration = `k·delta -
                    // c·velocity`, scaled to force by mass. Skip the pull
                    // entirely once `|delta| > ANCHOR_PULL_RANGE`.
                    toAnchor.set(anchor).sub(pos)
                    val dist = toAnchor.length()
                    if (dist > 0.0 && dist <= ANCHOR_PULL_RANGE) {
                        forceScratch.x += mass * (SPRING_K * toAnchor.x - DAMPING * vel.x())
                        forceScratch.y += mass * (SPRING_K * toAnchor.y - DAMPING * vel.y())
                        forceScratch.z += mass * (SPRING_K * toAnchor.z - DAMPING * vel.z())
                    }
                }

                // Only the *Body variants of the force API actually
                // move our sphere bodies in this VS2 build, so we
                // rotate world-frame force into body frame before
                // applying.
                body.kinematics.rotation.transformInverse(forceScratch)
                body.applyBodyForce(forceScratch, Vector3d())

                // Rotational PD control while the Tome holds the orb.
                // Same algorithm as [WyllandTomeForceInducer]'s
                // rotation branch but adapted to body API: compute the
                // shortest-arc small-angle vector between the body's
                // current rotation and the grab's ideal rotation, add
                // PD damping against angular velocity, rotate the
                // result into body frame, and apply via
                // `applyBodyTorque`.
                val idealRot = OrbGrabState.idealRotationFor(bodyId)
                if (idealRot != null) {
                    val curRot = body.kinematics.rotation
                    val curRotInv = Quaterniond(curRot).invert()
                    val rotDiff = Quaterniond(idealRot).mul(curRotInv).normalize().invert()
                    torqueScratch.set(rotDiff.x * 2.0, rotDiff.y * 2.0, rotDiff.z * 2.0)
                        .mul(TOME_ROT_K)
                    if (rotDiff.w > 0.0) torqueScratch.mul(-1.0)
                    val angVel = body.kinematics.angularVelocity
                    torqueScratch.x -= TOME_ROT_DAMPING * angVel.x()
                    torqueScratch.y -= TOME_ROT_DAMPING * angVel.y()
                    torqueScratch.z -= TOME_ROT_DAMPING * angVel.z()
                    // Multiply by mass (the inertia tensor is diag(mass)
                    // by construction in `OrbOfPotentialManager`, so
                    // the `R · I · R⁻¹` sandwich the ship inducer uses
                    // collapses to a scalar multiply).
                    torqueScratch.mul(mass)
                    curRot.transformInverse(torqueScratch)
                    body.applyBodyTorque(torqueScratch)
                }
            }
        }
    }
}
