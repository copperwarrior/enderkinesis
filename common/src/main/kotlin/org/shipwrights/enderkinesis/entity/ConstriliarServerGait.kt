package org.shipwrights.enderkinesis.entity

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.mod.common.world.clipIncludeShips

/**
 * Physics-side shadow of the client gait: the same diagonal-trot step machine, run on
 * the server so its stance anchors can feed SUPPLEMENTAL per-leg springs (the hover
 * still owns weight and primary height). Never synced — the client gait keeps its own
 * planting for visuals; small divergence is fine at muscle-layer force scale.
 */
class ConstriliarServerGait {

    val anchors = Array(4) { Vector3d() }
    private val swingTarget = Array(4) { Vector3d() }
    private val swingTicks = IntArray(4) { PLANTED }
    private val grounded = BooleanArray(4)
    private val pendingGrounded = BooleanArray(4)
    /** Set by [home]: whether the last home ray actually hit support. */
    private var homeGrounded = false
    private var sitBlend = 0f
    private var initialized = false

    fun tick(entity: ConstriliarEntity, level: ServerLevel) {
        sitBlend = (sitBlend + if (entity.isSitting()) ConstriliarGait.SIT_TUCK_RATE else -ConstriliarGait.SIT_TUCK_RATE)
            .coerceIn(0f, 1f)
        val up = entity.serverUp()
        // MODEL-frame convention: the client gait's homes live in bodyQuat = shipRot ∘
        // rotY(180°); without the flip each ±symmetric home index lands on the
        // diagonally opposite corner (legs crossed, forces at the wrong hips).
        val rotation = entity.serverShipRotation().rotateY(Math.PI)
        val origin = Vec3(entity.x, entity.y, entity.z).add(
            up.x * entity.currentLift(), up.y * entity.currentLift(), up.z * entity.currentLift(),
        )
        if (!initialized) {
            for (i in 0..3) {
                val h = home(entity, level, origin, rotation, up, i)
                anchors[i].set(h.x, h.y, h.z)
                grounded[i] = homeGrounded
            }
            initialized = true
            return
        }
        for (i in 0..3) {
            if (swingTicks[i] == PLANTED) continue
            // Re-aim mid-swing at the CURRENT home so touchdown lands under the moving
            // body (mirrors the client gait's re-aim).
            val lead = entity.deltaMovement.scale(ConstriliarGait.STEP_LEAD_TICKS)
            val h = home(entity, level, origin, rotation, up, i)
            swingTarget[i].set(h.x + lead.x, h.y, h.z + lead.z)
            pendingGrounded[i] = homeGrounded
            if (++swingTicks[i] >= STEP_TICKS) {
                anchors[i].set(swingTarget[i])
                grounded[i] = pendingGrounded[i]
                swingTicks[i] = PLANTED
            }
        }
        for (i in 0..3) {
            if (swingTicks[i] != PLANTED) continue
            val h = home(entity, level, origin, rotation, up, i)
            val errSq = anchors[i].distanceSquared(h.x, h.y, h.z)
            if (errSq > ConstriliarGait.FORCE_STEP_SQ ||
                (errSq > ConstriliarGait.STEP_TRIGGER_SQ && pairPlanted(i))
            ) {
                swingTicks[i] = 0
            }
        }
    }

    /** A stance leg: planted on real support — a supplemental-spring force point. */
    fun isStance(leg: Int): Boolean = swingTicks[leg] == PLANTED && grounded[leg]

    /** Diagonal trot: a leg may lift only while the other diagonal pair is planted. */
    private fun pairPlanted(leg: Int): Boolean {
        val a = if (leg == 0 || leg == 2) 1 else 0
        val b = if (leg == 0 || leg == 2) 3 else 2
        return swingTicks[a] == PLANTED && swingTicks[b] == PLANTED
    }

    /** Server twin of the client gait's groundedHome — same offsets, same ray. */
    private fun home(
        entity: ConstriliarEntity,
        level: ServerLevel,
        origin: Vec3,
        rotation: Quaterniond,
        up: Vector3d,
        leg: Int,
    ): Vec3 {
        val tuck = 1f - ConstriliarGait.SIT_TUCK_AMOUNT *
            (sitBlend * sitBlend * (3f - 2f * sitBlend))
        val local = rotation.transform(
            Vector3d(
                (ConstriliarGait.HOME_LOCAL_X[leg] * tuck).toDouble(),
                0.0,
                (ConstriliarGait.HOME_LOCAL_Z[leg] * tuck).toDouble(),
            ),
        )
        val homePos = origin.add(local.x, local.y, local.z)
        val hit = level.clipIncludeShips(
            ClipContext(
                homePos.add(up.x * ConstriliarGait.FOOT_RAY_UP, up.y * ConstriliarGait.FOOT_RAY_UP, up.z * ConstriliarGait.FOOT_RAY_UP),
                homePos.add(up.x * -ConstriliarGait.FOOT_RAY_DOWN, up.y * -ConstriliarGait.FOOT_RAY_DOWN, up.z * -ConstriliarGait.FOOT_RAY_DOWN),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY,
                entity,
            ),
            true,
            entity.ownShipIdForCollision(),
        )
        homeGrounded = hit.type != HitResult.Type.MISS
        return if (!homeGrounded) {
            homePos.add(up.x * -DROOP, up.y * -DROOP, up.z * -DROOP)
        } else {
            ConstriliarEntity.fluidAdjustedSurface(level, hit.location)
        }
    }

    companion object {
        private const val PLANTED = -1
        private val STEP_TICKS = Math.ceil(1.0 / ConstriliarGait.STEP_RATE).toInt()
        private const val DROOP = 0.35
    }
}
