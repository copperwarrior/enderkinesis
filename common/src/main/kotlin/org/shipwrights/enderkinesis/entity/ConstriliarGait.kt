package org.shipwrights.enderkinesis.entity

import net.minecraft.util.Mth
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import org.valkyrienskies.mod.common.world.clipIncludeShips
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Client-side procedural gait + 2-bone IK for the Constriliar's legs. World-space foot
 * anchors stay planted while the body moves; a foot steps (diagonal-trot pairing) once it
 * drifts past [STEP_TRIGGER] from its home. The renderer calls [solveRenderAngles] each
 * frame; the model reads [renderAngles].
 *
 * Leg order matches the model: 0 = front-right, 1 = front-left, 2 = back-left,
 * 3 = back-right. Bone constants derive from the Blockbench export (px units): the flat
 * thigh/calf siblings were re-rooted into a hip→thigh→calf chain, so bind pose reproduces
 * at hipYaw=±45°/±135°, thighX=-0.6545, calfX=2.0944.
 */
class ConstriliarGait {

    val footPrev = Array(4) { Vec3.ZERO }
    val footCur = Array(4) { Vec3.ZERO }
    private val stepFrom = Array(4) { Vec3.ZERO }
    private val stepTo = Array(4) { Vec3.ZERO }
    private val stepProgress = FloatArray(4) { 1f }
    private var initialized = false
    private val scratch = Vector3f()

    // Airborne flailing: no surface to plant on, so the legs scrabble instead of IK.
    private var flailing = false
    private var flailPhase = 0f

    // Sitting leg tuck, blended so the legs fold away rather than vanish.
    private var sitBlendPrev = 0f
    private var sitBlendCur = 0f

    // Spawn-intro leg placement (ticks on the client intro clock; -1 = not playing).
    private var introTicks = -1f

    // Overload tremor: legs shake when the load exceeds comfortable carry mass.
    private var strained = false
    private var tremorPhase = 0f

    fun sitBlend(partialTick: Float): Float = Mth.lerp(partialTick, sitBlendPrev, sitBlendCur)

    /** [hipYaw, thighX, calfX] per leg, consumed by the model's setupAnim. */
    val renderAngles = FloatArray(12).also { a ->
        for (i in 0..3) {
            a[i * 3] = BIND_HIP_YAW[i]
            a[i * 3 + 1] = BIND_THIGH_X
            a[i * 3 + 2] = BIND_CALF_X
        }
    }

    fun tick(entity: ConstriliarEntity) {
        flailing = entity.isFlailing()
        flailPhase = if (flailing) flailPhase + 1f else 0f
        introTicks = entity.spawnAnimTime(0f)
        strained = entity.isStrained()
        // A Float phase that only grows loses its fraction after some hours.
        tremorPhase = if (strained && tremorPhase < PHASE_WRAP) tremorPhase + 1f else 0f
        sitBlendPrev = sitBlendCur
        sitBlendCur = (sitBlendCur + if (entity.isSitting()) SIT_TUCK_RATE else -SIT_TUCK_RATE)
            .coerceIn(0f, 1f)
        // Homes and step decisions live in the frame the body is DRAWN in — the ship's
        // render transform; the entity pose is only the shipless fallback.
        val shipPose = entity.shipRenderPose()
        val origin = shipPose?.first ?: entity.bodyOrigin(1f)
        val q = shipPose?.second ?: entity.bodyQuat(1f)
        if (!initialized) {
            for (i in 0..3) {
                footCur[i] = groundedHome(entity, origin, q, i)
                footPrev[i] = footCur[i]
            }
            initialized = true
            return
        }
        for (i in 0..3) {
            footPrev[i] = footCur[i]
            if (stepProgress[i] < 1f) {
                stepProgress[i] = (stepProgress[i] + STEP_RATE).coerceAtMost(1f)
                if (stepProgress[i] >= 1f) playFootstep(entity, i)
                val p = stepProgress[i].toDouble()
                // Re-aim mid-step at the CURRENT home so the foot lands where the home
                // is at touchdown, not where it was at lift-off — the old fixed target
                // left every step landing short of a moving body.
                val lead = entity.deltaMovement.scale(STEP_LEAD_TICKS)
                stepTo[i] = groundedHome(entity, origin, q, i).add(lead.x, 0.0, lead.z)
                val up = entity.upVector(1f)
                val lift = sin(Math.PI * p) * STEP_LIFT
                footCur[i] = stepFrom[i].lerp(stepTo[i], p)
                    .add(up.x * lift, up.y * lift, up.z * lift)
            }
        }
        for (i in 0..3) {
            if (stepProgress[i] < 1f) continue
            val home = groundedHome(entity, origin, q, i)
            val errSq = footCur[i].distanceToSqr(home)
            // Failsafe: a foot stranded far past its home (fast movement, ledges) steps
            // immediately, gait pairing be damned — a stretched leg looks worse than a
            // broken trot beat.
            if (errSq > FORCE_STEP_SQ || (errSq > STEP_TRIGGER_SQ && pairFree(i))) {
                val lead = entity.deltaMovement.scale(STEP_LEAD_TICKS)
                stepFrom[i] = footCur[i]
                stepTo[i] = home.add(lead.x, 0.0, lead.z)
                stepProgress[i] = 0f
            }
        }
    }

    /** Touchdown: the stepped-on block's step sound — or, on a liquid, a splash burst
     *  with a pointed-dripstone drip (water/lava variants). Client-local. */
    private fun playFootstep(entity: ConstriliarEntity, leg: Int) {
        val foot = stepTo[leg]
        val up = entity.upVector(1f)
        val level = entity.level()
        val support = net.minecraft.core.BlockPos.containing(
            foot.x - up.x * 0.2, foot.y - up.y * 0.2, foot.z - up.z * 0.2,
        )
        val fluid = level.getFluidState(support)
        if (!fluid.isEmpty) {
            val water = fluid.`is`(net.minecraft.tags.FluidTags.WATER)
            val particle = if (water) {
                net.minecraft.core.particles.ParticleTypes.SPLASH
            } else {
                net.minecraft.core.particles.ParticleTypes.LAVA
            }
            val rng = entity.random
            repeat(if (water) 6 else 3) {
                level.addParticle(
                    particle,
                    foot.x + (rng.nextDouble() - 0.5) * 0.4,
                    foot.y + 0.05,
                    foot.z + (rng.nextDouble() - 0.5) * 0.4,
                    0.0, 0.15, 0.0,
                )
            }
            val drip = if (water) {
                net.minecraft.sounds.SoundEvents.POINTED_DRIPSTONE_DRIP_WATER
            } else {
                net.minecraft.sounds.SoundEvents.POINTED_DRIPSTONE_DRIP_LAVA
            }
            level.playLocalSound(
                foot.x, foot.y, foot.z,
                drip, net.minecraft.sounds.SoundSource.NEUTRAL,
                0.5f, 0.85f + rng.nextFloat() * 0.3f, false,
            )
            return
        }
        var state = level.getBlockState(support)
        if (state.isAir) state = level.getBlockState(support.below())
        if (state.isAir) return
        val sound = state.soundType
        level.playLocalSound(
            foot.x, foot.y, foot.z,
            sound.stepSound, net.minecraft.sounds.SoundSource.NEUTRAL,
            sound.volume * FOOTSTEP_VOLUME, sound.pitch, false,
        )
    }

    /** Diagonal trot: a leg may lift only while the *other* diagonal pair is planted. */
    private fun pairFree(leg: Int): Boolean {
        val a = if (leg == 0 || leg == 2) 1 else 0
        val b = if (leg == 0 || leg == 2) 3 else 2
        return stepProgress[a] >= 1f && stepProgress[b] >= 1f
    }

    /** The leg's resting foot spot: diagonal out from the hip, dropped onto the surface.
     *  The down-ray is short on purpose — a foot must dangle over a drop rather than
     *  stretch to ground beyond the leg's reach. Sitting pulls the homes in tight under
     *  the plate, so the feet step inward and the knees fold up close to the body. */
    private fun groundedHome(entity: ConstriliarEntity, origin: Vec3, q: Quaternionf, leg: Int): Vec3 {
        val tuck = 1f - SIT_TUCK_AMOUNT * (sitBlendCur * sitBlendCur * (3f - 2f * sitBlendCur))
        val local = scratch.set(HOME_LOCAL_X[leg] * tuck, 0f, HOME_LOCAL_Z[leg] * tuck).rotate(q)
        val home = origin.add(local.x.toDouble(), local.y.toDouble(), local.z.toDouble())
        // Ray along -bodyUp, not world-down, so wall-climbing feet plant on the wall.
        // Skips the walker's own back ship — feet must never plant on its own cargo.
        val up = entity.upVector(1f)
        val hit = entity.level().clipIncludeShips(
            ClipContext(
                home.add(up.x * FOOT_RAY_UP, up.y * FOOT_RAY_UP, up.z * FOOT_RAY_UP),
                // Full anatomical reach (~1.8 from the hip): on a tilted body the real
                // ground can sit well below the rotated home plane — a short ray pinned
                // every miss to a floating plane instead of stretching to the block.
                // The IK's own reach clamp handles the last few pixels of overstretch.
                home.add(up.x * -FOOT_RAY_DOWN, up.y * -FOOT_RAY_DOWN, up.z * -FOOT_RAY_DOWN),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY,
                entity,
            ),
            true,
            entity.clientOwnShipId(),
        )
        if (hit.type != HitResult.Type.MISS) {
            // Feet wade into liquid surfaces rather than standing on the very top.
            return ConstriliarEntity.fluidAdjustedSurface(entity.level(), hit.location)
        }
        // Body-up ray missed — on a pitched body (skyward aim) the raised hips' ray
        // tilts away from the actual ground. Search nearby: a WORLD-down ray from the
        // home, then one nudged inward under the body, before giving up to a droop.
        val originF = entity.bodyOrigin(1f)
        for (attempt in 0..1) {
            val inX = (originF.x - home.x) * (attempt * FOOT_SEARCH_INWARD)
            val inZ = (originF.z - home.z) * (attempt * FOOT_SEARCH_INWARD)
            val from = Vec3(home.x + inX, home.y + FOOT_RAY_UP, home.z + inZ)
            val fallback = entity.level().clipIncludeShips(
                ClipContext(
                    from,
                    // Longer than the body-frame ray: a reared body's front hips sit
                    // well above the ground the world-down fallback is hunting for.
                    from.add(0.0, -(FOOT_RAY_UP + FOOT_RAY_DOWN * FOOT_FALLBACK_REACH), 0.0),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.ANY,
                    entity,
                ),
                true,
                entity.clientOwnShipId(),
            )
            if (fallback.type != HitResult.Type.MISS) {
                return ConstriliarEntity.fluidAdjustedSurface(entity.level(), fallback.location)
            }
        }
        // Nothing in reach: droop WORLD-down — a local-down droop on a pitched body
        // pointed the dangling feet sideways.
        return home.add(0.0, -0.35, 0.0)
    }

    /** Per-frame IK: world feet → model-space targets → hip yaw + planar 2-bone solve. */
    fun solveRenderAngles(entity: ConstriliarEntity, partialTick: Float) {
        solveRenderAngles(entity.bodyOrigin(partialTick), entity.bodyQuat(partialTick), partialTick)
    }

    /** Body-frame override for ship-space rendering: [origin] is the world position of
     *  the model ground plane, [bodyQuat] the model→world rotation (tilt ∘ 180°-yaw).
     *  [bodyQuat] is consumed (conjugated in place). */
    fun solveRenderAngles(origin: Vec3, bodyQuat: Quaternionf, partialTick: Float) {
        if (!initialized) return
        if (flailing) {
            // Scrabbling in the air: cyclic waves around the bind pose, each leg on its
            // own phase, plus a hip wiggle — reads as panicked paddling.
            val t = flailPhase + partialTick
            for (i in 0..3) {
                renderAngles[i * 3] = BIND_HIP_YAW[i] + sin(t * 0.9f + i * 1.7f) * 0.35f
                renderAngles[i * 3 + 1] = BIND_THIGH_X + sin(t * 1.15f + i * 2.1f) * 0.5f
                renderAngles[i * 3 + 2] = BIND_CALF_X + cos(t * 1.3f + i * 1.3f) * 0.55f
            }
            return
        }
        val introT = if (introTicks >= 0f) introTicks + partialTick else -1f
        val bodyRot = if (introT >= 0f) Quaternionf(bodyQuat) else null
        val pos = origin
        val qInv = bodyQuat.conjugate()
        for (i in 0..3) {
            val foot = if (introT >= 0f && bodyRot != null) {
                introFoot(i, introT, pos, bodyRot)
            } else {
                footPrev[i].lerp(footCur[i], partialTick.toDouble())
            }
            val local = scratch.set(
                (foot.x - pos.x).toFloat(),
                (foot.y - pos.y).toFloat(),
                (foot.z - pos.z).toFloat(),
            ).rotate(qInv)
            // Model px: renderer applies scale(-1,-1,1) + translate(0,-1.5,0) after the
            // body quat, so model = (-16lx, 24-16ly, 16lz).
            val tx = -16f * local.x - ROOT_X[i]
            val ty = (24f - 16f * local.y) - ROOT_Y
            val tz = 16f * local.z - ROOT_Z[i]
            solveLeg(i, tx, ty, tz)
        }
    }

    private fun solveLeg(leg: Int, tx: Float, ty: Float, tz: Float) {
        // Hard hip-yaw limit: legs may swivel only ±HIP_YAW_LIMIT around their bind
        // diagonal, so a planted foot can never fold a leg across the body while the
        // body rotates — the step triggers catch up instead.
        val rawYaw = atan2(-tx, -tz)
        val yawDelta = wrapRad(rawYaw - BIND_HIP_YAW[leg]).coerceIn(-HIP_YAW_LIMIT, HIP_YAW_LIMIT)
        val hipYaw = BIND_HIP_YAW[leg] + yawDelta
        // Steered sagittal plane, relative to the thigh pivot (y 0.25, z -1.2517). The
        // horizontal distance is the target PROJECTED into the (possibly yaw-clamped)
        // plane — solving against the raw off-plane distance made clamped legs reach
        // for length they couldn't have and stop visibly short.
        val horiz = -(tx * sin(hipYaw) + tz * cos(hipYaw))
        val vy = ty - 0.25f
        val vz = -horiz.coerceAtLeast(0f) + 1.2517f
        val d = Mth.clamp(hypot(vy, vz), 2f, L1 + L2 - 0.05f)
        val base = atan2(vy, -vz)
        val cosA = ((L1 * L1 + d * d - L2 * L2) / (2f * L1 * d)).coerceIn(-1f, 1f)
        val cosB = ((L1 * L1 + L2 * L2 - d * d) / (2f * L1 * L2)).coerceIn(-1f, 1f)
        renderAngles[leg * 3] = hipYaw
        renderAngles[leg * 3 + 1] = base - acos(cosA)
        renderAngles[leg * 3 + 2] = Math.PI.toFloat() - acos(cosB)
        if (strained) {
            // Overloaded: a fine fast tremor on every joint — legs straining under mass.
            val t = tremorPhase
            renderAngles[leg * 3] += sin(t * 2.6f + leg * 1.93f) * 0.03f
            renderAngles[leg * 3 + 1] += sin(t * 3.1f + leg * 2.41f) * 0.04f
            renderAngles[leg * 3 + 2] += sin(t * 2.8f + leg * 1.31f) * 0.045f
        }
    }

    /** Intro pick-and-place: each foot starts tucked against the body, then swings down
     *  in an eased arc onto its grounded home (already in footCur), with a decaying
     *  wobble so the four placements read as organic rather than mechanical. */
    private fun introFoot(leg: Int, introT: Float, origin: Vec3, bodyRot: Quaternionf): Vec3 {
        val p = ((introT - ConstriliarEntity.ANIM_LEG_POP[leg]) / ConstriliarEntity.ANIM_LEG_POP_LENGTH)
            .coerceIn(0f, 1f)
        val eased = 1f - (1f - p) * (1f - p) * (1f - p)
        val wobble = (1f - p) * 0.07f
        val local = scratch.set(
            HOME_LOCAL_X[leg] * 0.55f + sin(introT * 1.9f + leg * 2.3f) * wobble,
            0.5f + sin(introT * 1.6f + leg * 1.1f) * wobble,
            HOME_LOCAL_Z[leg] * 0.55f + cos(introT * 1.7f + leg * 2.9f) * wobble,
        ).rotate(bodyRot)
        val tucked = origin.add(local.x.toDouble(), local.y.toDouble(), local.z.toDouble())
        val arc = sin(Math.PI.toFloat() * p) * 0.12
        return tucked.lerp(footCur[leg], eased.toDouble())
            .add(0.0, arc.toDouble(), 0.0)
    }

    private fun wrapRad(angle: Float): Float {
        var r = angle % TAU
        if (r < -Math.PI.toFloat()) r += TAU
        if (r > Math.PI.toFloat()) r -= TAU
        return r
    }

    companion object {
        internal const val STEP_RATE = 0.25f
        private const val PHASE_WRAP = 72_000f
        private const val FOOTSTEP_VOLUME = 0.3f
        internal const val SIT_TUCK_RATE = 0.12f
        /** Foot ray extents along the body normal from the home plane: upslope legs
         *  plant up to 0.75 above it, downslope legs stretch to 1.7 below. */
        internal const val FOOT_RAY_UP = 0.75
        /** Fraction of hip→body-centre the miss-fallback foot ray steps inward. */
        internal const val FOOT_SEARCH_INWARD = 0.35
        /** World-down fallback reach multiplier over the body-frame ray. */
        internal const val FOOT_FALLBACK_REACH = 1.6
        internal const val FOOT_RAY_DOWN = 1.7
        /** How far the sitting homes pull in toward the body (fraction of home radius). */
        internal const val SIT_TUCK_AMOUNT = 0.3f
        private const val STEP_LIFT = 0.25
        internal const val STEP_TRIGGER_SQ = 0.55 * 0.55
        internal const val FORCE_STEP_SQ = 1.1 * 1.1
        internal const val STEP_LEAD_TICKS = 3.0

        // Bone lengths in px: hip→knee and knee→foot from the Blockbench export.
        private const val L1 = 14.4503f
        private const val L2 = 14.3053f

        // Leg roots in model px (front = -Z, right = -X in model space).
        private val ROOT_X = floatArrayOf(-7f, 7f, 7f, -7f)
        private const val ROOT_Y = 18f
        private val ROOT_Z = floatArrayOf(-7f, -7f, 7f, 7f)

        private val BIND_HIP_YAW = floatArrayOf(0.7854f, -0.7854f, -2.3562f, 2.3562f)
        private const val BIND_THIGH_X = -0.6545f
        private const val BIND_CALF_X = 2.0944f
        private const val HIP_YAW_LIMIT = 0.7f
        private const val TAU = (2.0 * Math.PI).toFloat()

        // Home foot spots in body-local blocks (+Y up): diagonally out from each hip.
        // Diagonal radius = hip |(0.4375, 0.4375)| + bind reach 14.586px/16 ≈ 1.53;
        // per-axis = 1.53 · cos45°.
        internal const val HOME_R = 1.0821f
        internal val HOME_LOCAL_X = floatArrayOf(HOME_R, -HOME_R, -HOME_R, HOME_R)
        internal val HOME_LOCAL_Z = floatArrayOf(-HOME_R, -HOME_R, HOME_R, HOME_R)
    }
}
