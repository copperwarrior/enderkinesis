package org.shipwrights.enderkinesis.entity

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d

/**
 * Physics-side shadow of the client gait: the same diagonal-trot step machine, run on
 * the server so its stance anchors can feed SUPPLEMENTAL per-leg springs (the hover
 * still owns weight and primary height). Never synced — the client gait keeps its own
 * planting for visuals; small divergence is fine at muscle-layer force scale.
 */
class ConstriliarServerGait {

    val anchors = Array(4) { Vector3d() }
    private val homes = Array(4) { Vector3d() }
    private val homeGrounded = BooleanArray(4)
    private val swingProgress = FloatArray(4) { PLANTED }
    private val grounded = BooleanArray(4)
    private val scratch = Vector3d()
    private var sitBlend = 0f
    private var initialized = false

    /** Re-plant every foot at its home on the next tick. Anchors go stale while the
     *  struts are off, and a stale stance anchor pulls the body to an old height. */
    fun reset() {
        initialized = false
        swingProgress.fill(PLANTED)
    }

    fun tick(entity: ConstriliarEntity, level: ServerLevel) {
        sitBlend = (sitBlend + if (entity.isSitting()) ConstriliarGait.SIT_TUCK_RATE else -ConstriliarGait.SIT_TUCK_RATE)
            .coerceIn(0f, 1f)
        val up = entity.serverUp()
        // Same frame as the client's shipRenderPose (platform centre, ACTUAL ship
        // rotation), so the strut anchors sit where the drawn feet stand.
        val pose = entity.serverShipPose(level)
        // MODEL-frame convention: the client gait's homes live in bodyQuat = shipRot ∘
        // rotY(180°); without the flip each ±symmetric home index lands on the
        // diagonally opposite corner (legs crossed, forces at the wrong hips).
        val rotation = (pose?.second ?: entity.serverShipRotation()).rotateY(Math.PI)
        val origin = pose?.first ?: entity.currentLift().let { lift ->
            Vec3(entity.x + up.x * lift, entity.y + up.y * lift, entity.z + up.z * lift)
        }
        // Touchdown aims at the CURRENT home plus a surface-plane lead, cast with the
        // home so the foot lands on the surface under the moving body (client twin).
        val move = entity.deltaMovement
        val along = move.x * up.x + move.y * up.y + move.z * up.z
        val leadX = (move.x - up.x * along) * ConstriliarGait.STEP_LEAD_TICKS
        val leadY = (move.y - up.y * along) * ConstriliarGait.STEP_LEAD_TICKS
        val leadZ = (move.z - up.z * along) * ConstriliarGait.STEP_LEAD_TICKS
        for (i in 0..3) {
            if (swingProgress[i] >= PLANTED) {
                updateHome(entity, level, origin, rotation, up, i, 0.0, 0.0, 0.0)
            } else {
                updateHome(entity, level, origin, rotation, up, i, leadX, leadY, leadZ)
            }
        }
        if (!initialized) {
            for (i in 0..3) {
                anchors[i].set(homes[i])
                grounded[i] = homeGrounded[i]
            }
            initialized = true
            return
        }
        val rate = ConstriliarGait.swingRate(entity.deltaMovement.length())
        for (i in 0..3) {
            if (swingProgress[i] >= PLANTED) continue
            swingProgress[i] += rate
            if (swingProgress[i] >= PLANTED) {
                anchors[i].set(homes[i])
                grounded[i] = homeGrounded[i]
                swingProgress[i] = PLANTED
            }
        }
        for (i in 0..3) {
            if (swingProgress[i] < PLANTED) continue
            val errSq = anchors[i].distanceSquared(homes[i])
            if (errSq > ConstriliarGait.FORCE_STEP_SQ ||
                (errSq > ConstriliarGait.STEP_TRIGGER_SQ && ConstriliarGait.otherPairPlanted(i) { swingProgress[it] >= PLANTED })
            ) {
                swingProgress[i] = 0f
            }
        }
    }

    /** A stance leg: planted on real support — a supplemental-spring force point. */
    fun isStance(leg: Int): Boolean = swingProgress[leg] >= PLANTED && grounded[leg]

    /** Server twin of the client gait's groundedHome — same offsets, same ray. */
    private fun updateHome(
        entity: ConstriliarEntity,
        level: ServerLevel,
        origin: Vec3,
        rotation: Quaterniond,
        up: Vector3d,
        leg: Int,
        leadX: Double,
        leadY: Double,
        leadZ: Double,
    ) {
        val tuck = ConstriliarGait.tuckScale(sitBlend).toDouble()
        val local = rotation.transform(
            scratch.set(ConstriliarGait.HOME_LOCAL_X[leg] * tuck, 0.0, ConstriliarGait.HOME_LOCAL_Z[leg] * tuck),
        )
        val homePos = origin.add(local.x + leadX, local.y + leadY, local.z + leadZ)
        val hit = ConstriliarGait.castFootRay(level, entity, homePos, up.x, up.y, up.z, entity.ownShipIdForCollision())
        homeGrounded[leg] = hit.type != HitResult.Type.MISS
        val foot = if (homeGrounded[leg]) {
            ConstriliarEntity.fluidAdjustedSurface(level, hit.location)
        } else {
            homePos.add(0.0, -ConstriliarGait.DROOP, 0.0)
        }
        homes[leg].set(foot.x, foot.y, foot.z)
    }

    companion object {
        /** Swing progress of a planted foot (client gait's stepProgress convention). */
        private const val PLANTED = 1f
    }
}
