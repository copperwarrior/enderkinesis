package org.shipwrights.enderkinesis.entity

import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3f
import org.valkyrienskies.mod.common.world.clipIncludeShips
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Client-side procedural gait + 2-bone IK for the Constriliar's legs. World-space foot
 * anchors stay planted while the body moves; a foot steps in its [ConstriliarGaitClock]
 * window, or past [STEP_TRIGGER_SQ] when the clock stops. The renderer calls [solveRenderAngles] each
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
    private val stepPrev = FloatArray(4) { 1f }
    private val holdTicks = IntArray(4)
    /** Least swing lift that clears the ground along each leg's swing. */
    private val clearance = DoubleArray(4)
    private var entityRef: ConstriliarEntity? = null
    private var ownShip: Long? = null
    private val curveUp = Vector3d(0.0, 1.0, 0.0)
    private val curveOut = Vector3d()
    private val drawnFoot = Array(4) { Vector3d() }
    private val drawnNanos = LongArray(4)
    private val drawnLive = BooleanArray(4)
    private val jointNanos = LongArray(4)
    private val jointLive = BooleanArray(4)

    // Hanging calf pendulum per leg: swing from world down in the leg plane (rad, + =
    // foot ahead of the knee), its rate, and its frame clock. Not live while standing.
    private val calfSwing = FloatArray(4)
    private val calfSwingRate = FloatArray(4)
    private val calfNanos = LongArray(4)
    private val calfLive = BooleanArray(4)
    /** Legs with nothing under them, drawn hanging. */
    private val limp = BooleanArray(4)
    private val lastYawDelta = FloatArray(4)
    private var solvedThigh = 0f
    private var solvedCalf = 0f
    private var lastHomeOrigin = Vec3.ZERO
    private val lastHomeQ = Quaternionf()
    private val homes = Array(4) { Vec3.ZERO }
    private var initialized = false
    private val scratch = Vector3f()
    private val upScratch = Vector3d()
    private val lead = Vector3d()
    private var gait = ConstriliarGaitClock.STOPPED

    // Airborne: no surface to plant on, so every leg hangs limp.
    private var flailing = false

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

    /** The joint filter's own state. The tremor goes on [renderAngles] only: fed back
     *  into the filter, a slow limp filter summed it into a large wobble. */
    private val jointAngles = renderAngles.copyOf()

    // Hanging-leg expression: ticks airborne (panic, then relax), dance, and a clock.
    private var airTicks = 0
    private var dancing = false
    private var clockTicks = 0
    /** Level world direction the legs paddle toward in the air, zero for none. */
    private val airAim = Vector3f()
    private val aimLocal = Vector3f()
    // Game time, which client and server share, so the legs keep the body's dance.
    private var danceTicks = 0f

    fun tick(entity: ConstriliarEntity) {
        flailing = entity.isFlailing()
        for (i in 0..3) limp[i] = flailing || (entity.legSyncLive() && entity.legLimp(i))
        introTicks = entity.spawnAnimTime(0f)
        strained = entity.isStrained()
        airTicks = if (flailing) Math.min(airTicks + 1, PANIC_TICKS + PANIC_FADE_TICKS) else 0
        dancing = entity.showsDance()
        airAim.set(entity.airAim())
        clockTicks = entity.tickCount
        danceTicks = (entity.level().gameTime % ConstriliarEntity.DANCE_CYCLE_TICKS).toFloat()
        // A Float phase that only grows loses its fraction after some hours.
        tremorPhase = if (strained && tremorPhase < PHASE_WRAP) tremorPhase + 1f else 0f
        sitBlendPrev = sitBlendCur
        sitBlendCur = (sitBlendCur + if (entity.isSitting()) SIT_TUCK_RATE else -SIT_TUCK_RATE)
            .coerceIn(0f, 1f)
        // Homes and step decisions live in the frame the body is DRAWN in.
        val (origin, q) = entity.bodyRenderPose(1f)
        val up = entity.upVector(1f)
        curveUp.set(up.x.toDouble(), up.y.toDouble(), up.z.toDouble())
        entityRef = entity
        ownShip = entity.clientOwnShipId()
        gait = entity.gaitClock()
        // Synced legs come from the server; the client casts no foot rays of its own.
        if (entity.legSyncLive()) {
            if (initialized) {
                tickSynced(entity, swingRate(entity.deltaMovement.length()))
                return
            }
            homesFromServer(entity)
        } else {
            updateHomes(entity, origin, q, up)
        }
        // A teleport moves the drawn body far from the planted feet; swinging them
        // across would stretch every leg over the gap for a whole step.
        if (!initialized || anyFootStranded()) {
            for (i in 0..3) {
                footCur[i] = homes[i]
                footPrev[i] = homes[i]
                stepProgress[i] = 1f
                stepPrev[i] = 1f
            }
            initialized = true
            return
        }
        tickLocal(entity, swingRate(entity.deltaMovement.length()))
    }

    /** The client's own step machine, for the modes the server gait does not carry. */
    private fun tickLocal(entity: ConstriliarEntity, rate: Float) {
        for (i in 0..3) {
            footPrev[i] = footCur[i]
            stepPrev[i] = stepProgress[i]
            if (stepProgress[i] < 1f) {
                stepProgress[i] = (stepProgress[i] + rate).coerceAtMost(1f)
                // Re-aim at the CURRENT home, so the foot lands under the moving body.
                stepTo[i] = homes[i]
                footCur[i] = swingFoot(i, stepProgress[i].toDouble(), 0f)
                if (stepProgress[i] >= 1f) playFootstep(entity, stepTo[i])
            }
        }
        for (i in 0..3) {
            if (stepProgress[i] < 1f) continue
            val errSq = footCur[i].distanceToSqr(homes[i])
            if (ConstriliarGaitClock.mayStep(i, errSq, gait) { stepProgress[it] < 1f }) startSwing(i, homes[i])
        }
    }

    /** Carry mode: the feet follow the server gait, so a planted foot stands where its
     *  force acts. A swing holds short of its target until the server plants it, so a
     *  late sync never lands a foot early; a held foot taps for its foothold. */
    private fun tickSynced(entity: ConstriliarEntity, rate: Float) {
        for (i in 0..3) {
            footPrev[i] = footCur[i]
            stepPrev[i] = stepProgress[i]
            val a = entity.legAnchor(i)
            val target = Vec3(a.x.toDouble(), a.y.toDouble(), a.z.toDouble())
            if (entity.legSwinging(i)) {
                if (stepProgress[i] >= 1f) startSwing(i, target)
                retarget(i, target)
                val next = stepProgress[i] + rate
                if (next > SYNC_SWING_HOLD) holdTicks[i]++
                stepProgress[i] = next.coerceAtMost(SYNC_SWING_HOLD)
                footCur[i] = swingFoot(i, stepProgress[i].toDouble(), 0f)
            } else if (stepProgress[i] < 1f) {
                // Planted on the server: finish the place phase onto the anchor.
                retarget(i, target)
                holdTicks[i] = 0
                stepProgress[i] = (stepProgress[i] + rate).coerceAtMost(1f)
                footCur[i] = swingFoot(i, stepProgress[i].toDouble(), 0f)
                if (stepProgress[i] >= 1f) playFootstep(entity, target)
            } else if (footCur[i].distanceToSqr(target) > REPLANT_SQ) {
                // The server moved a planted foot (a replant after the legs switch back
                // on): step it over rather than snap it.
                startSwing(i, target)
            } else {
                footCur[i] = target
            }
        }
    }

    /** A swing's target moved: re-measure the ground it must clear when it moved far. */
    private fun retarget(leg: Int, to: Vec3) {
        if (to.distanceToSqr(stepTo[leg]) > RETARGET_SQ) clearance[leg] = swingClearance(stepFrom[leg], to)
        stepTo[leg] = to
    }

    private fun startSwing(leg: Int, to: Vec3) {
        stepFrom[leg] = footCur[leg]
        stepTo[leg] = to
        clearance[leg] = swingClearance(footCur[leg], to)
        stepProgress[leg] = 0f
        stepPrev[leg] = 0f
        holdTicks[leg] = 0
    }

    /** The foot must not pass through blocks between lift-off and touchdown: sample the
     *  ground along the straight swing and return the lift that clears its highest point. */
    private fun swingClearance(from: Vec3, to: Vec3): Double {
        val owner = entityRef ?: return 0.0
        val lvl = owner.level()
        var need = 0.0
        for (t in CLEAR_SAMPLES) {
            val px = from.x + (to.x - from.x) * t
            val py = from.y + (to.y - from.y) * t
            val pz = from.z + (to.z - from.z) * t
            val top = Vec3(px + curveUp.x * CLEAR_PROBE_UP, py + curveUp.y * CLEAR_PROBE_UP, pz + curveUp.z * CLEAR_PROBE_UP)
            val hit = castLegRay(lvl, owner, top, Vec3(px, py, pz), ownShip)
            if (hit.type == HitResult.Type.MISS) continue
            val rise = (hit.location.x - px) * curveUp.x + (hit.location.y - py) * curveUp.y +
                (hit.location.z - pz) * curveUp.z
            need = Math.max(need, rise + FOOT_CLEARANCE)
        }
        // A probe that starts in an overhang reads its top: never lift a foot into it.
        return need.coerceAtMost(CLEAR_MAX)
    }

    /** The foot on its swing curve at progress [p]; [partialTick] times the hold tap. */
    private fun swingFoot(leg: Int, p: Double, partialTick: Float): Vec3 {
        val out = swingCurve(leg, p, partialTick)
        return Vec3(out.x, out.y, out.z)
    }

    /** [swingFoot] into the shared [curveOut], valid until the next call. */
    private fun swingCurve(leg: Int, p: Double, partialTick: Float): Vector3d {
        swingPoint(stepFrom[leg], stepTo[leg], curveUp, p, curveOut, clearance[leg])
        // Sync lag holds every swing a tick or two; only a longer hold is a search.
        if (holdTicks[leg] > TAP_DELAY_TICKS) {
            val tap = Math.abs(sin((holdTicks[leg] - TAP_DELAY_TICKS + partialTick) * TAP_RATE)) * TAP_LIFT
            curveOut.fma(tap, curveUp)
        }
        return curveOut
    }

    /** Lead in the surface plane, cast with the home: a world-XZ lead dropped climb
     *  motion on walls and left the foot off the ground on slopes. */
    private fun updateHomes(entity: ConstriliarEntity, origin: Vec3, q: Quaternionf, up: Vector3f) {
        if (homesStill(entity, origin, q)) return
        upScratch.set(up.x.toDouble(), up.y.toDouble(), up.z.toDouble())
        ConstriliarGaitClock.footLead(gait, entity.deltaMovement, null, upScratch, lead)
        val leadX = lead.x
        val leadY = lead.y
        val leadZ = lead.z
        for (i in 0..3) {
            // Only a swinging foot aims ahead; planted feet measure drift from the true home.
            homes[i] = if (stepProgress[i] < 1f) {
                groundedHome(entity, origin, q, i, leadX, leadY, leadZ)
            } else {
                groundedHome(entity, origin, q, i, 0.0, 0.0, 0.0)
            }
        }
    }

    private fun homesFromServer(entity: ConstriliarEntity) {
        for (i in 0..3) {
            val a = entity.legAnchor(i)
            homes[i] = Vec3(a.x.toDouble(), a.y.toDouble(), a.z.toDouble())
        }
    }

    /** A still body with planted feet has the homes of the last tick. A periodic
     *  refresh catches blocks that change under the feet. */
    private fun homesStill(entity: ConstriliarEntity, origin: Vec3, q: Quaternionf): Boolean {
        val still = initialized && stepProgress.all { it >= 1f } && sitBlendCur == sitBlendPrev &&
            origin.distanceToSqr(lastHomeOrigin) < STILL_EPS_SQ && Math.abs(q.dot(lastHomeQ)) > STILL_DOT &&
            entity.tickCount % HOME_REFRESH_TICKS != 0
        if (!still) {
            lastHomeOrigin = origin
            lastHomeQ.set(q)
        }
        return still
    }

    private fun anyFootStranded(): Boolean {
        for (i in 0..3) {
            if (footCur[i].distanceToSqr(homes[i]) > RESNAP_SQ) return true
        }
        return false
    }

    /** Touchdown: the stepped-on block's step sound — or, on a liquid, a splash burst
     *  with a pointed-dripstone drip (water/lava variants). Client-local. */
    internal fun playFootstep(entity: ConstriliarEntity, foot: Vec3) {
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

    /** The leg's resting foot spot: diagonal out from the hip, dropped onto the surface.
     *  The down-ray is short on purpose — a foot must dangle over a drop rather than
     *  stretch to ground beyond the leg's reach. Sitting pulls the homes in tight under
     *  the plate, so the feet step inward and the knees fold up close to the body. */
    private fun groundedHome(
        entity: ConstriliarEntity,
        origin: Vec3,
        q: Quaternionf,
        leg: Int,
        leadX: Double,
        leadY: Double,
        leadZ: Double,
    ): Vec3 {
        val tuck = tuckScale(sitBlendCur)
        val local = scratch.set(HOME_LOCAL_X[leg] * tuck, 0f, HOME_LOCAL_Z[leg] * tuck).rotate(q)
        val home = origin.add(local.x + leadX, local.y + leadY, local.z + leadZ)
        // Ray along -bodyUp, not world-down, so wall-climbing feet plant on the wall.
        val up = curveUp
        val hit = castFootRay(entity.level(), entity, home, up.x, up.y, up.z, ownShip)
        if (hit.type != HitResult.Type.MISS) {
            // Feet wade into liquid surfaces rather than standing on the very top.
            return ConstriliarEntity.fluidAdjustedSurface(entity.level(), hit.location)
        }
        // Body-up ray missed — on a pitched body (skyward aim) the raised hips' ray
        // tilts away from the actual ground. Search nearby: a WORLD-down ray from the
        // home, then one nudged inward under the body, before giving up to a droop.
        // Floor-like surfaces only: on a wall the world-down ray finds the floor below.
        if (up.y < FOOT_FALLBACK_MIN_UP_Y) return home.add(0.0, -DROOP, 0.0)
        val originF = entity.bodyOrigin(1f)
        for (attempt in 0..1) {
            val inX = (originF.x - home.x) * (attempt * FOOT_SEARCH_INWARD)
            val inZ = (originF.z - home.z) * (attempt * FOOT_SEARCH_INWARD)
            val from = Vec3(home.x + inX, home.y + FOOT_RAY_UP, home.z + inZ)
            val fallback = clipFoot(
                entity.level(), entity, from,
                // Longer than the body-frame ray: a reared body's front hips sit
                // well above the ground the world-down fallback is hunting for.
                from.add(0.0, -(FOOT_RAY_UP + FOOT_RAY_DOWN * FOOT_FALLBACK_REACH), 0.0),
                ownShip,
            )
            if (fallback.type != HitResult.Type.MISS) {
                return ConstriliarEntity.fluidAdjustedSurface(entity.level(), fallback.location)
            }
        }
        // Nothing in reach: droop WORLD-down — a local-down droop on a pitched body
        // pointed the dangling feet sideways.
        return home.add(0.0, -DROOP, 0.0)
    }

    /** Per-frame IK: world feet → model-space targets → hip yaw + planar 2-bone solve. */

    /** Body-frame override for ship-space rendering: [origin] is the world position of
     *  the model ground plane, [bodyQuat] the model→world rotation (tilt ∘ 180°-yaw).
     *  [bodyQuat] is consumed (conjugated in place). */
    fun solveRenderAngles(origin: Vec3, bodyQuat: Quaternionf, partialTick: Float) {
        if (!initialized) return
        val introT = if (introTicks >= 0f) introTicks + partialTick else -1f
        val bodyRot = if (introT >= 0f) Quaternionf(bodyQuat) else null
        val pos = origin
        val qInv = bodyQuat.conjugate()
        for (i in 0..3) {
            if (limp[i] && introT < 0f) {
                hangLeg(i, qInv, partialTick)
                continue
            }
            calfLive[i] = false
            if (introT >= 0f && bodyRot != null) {
                val foot = introFoot(i, introT, pos, bodyRot)
                scratch.set((foot.x - pos.x).toFloat(), (foot.y - pos.y).toFloat(), (foot.z - pos.z).toFloat())
            } else {
                val target = if (stepPrev[i] < 1f || stepProgress[i] < 1f) {
                    // Swings are drawn on the curve every frame; a tick-sampled lerp cut a
                    // short swing into a few straight segments.
                    swingCurve(i, Mth.lerp(partialTick, stepPrev[i], stepProgress[i]).toDouble(), partialTick)
                } else {
                    val t = partialTick.toDouble()
                    val a = footPrev[i]
                    val b = footCur[i]
                    curveOut.set(Mth.lerp(t, a.x, b.x), Mth.lerp(t, a.y, b.y), Mth.lerp(t, a.z, b.z))
                }
                val foot = smoothFoot(i, target)
                scratch.set((foot.x - pos.x).toFloat(), (foot.y - pos.y).toFloat(), (foot.z - pos.z).toFloat())
            }
            val local = scratch.rotate(qInv)
            // Model px: renderer applies scale(-1,-1,1) + translate(0,-1.5,0) after the
            // body quat, so model = (-16lx, 24-16ly, 16lz).
            val tx = -16f * local.x - ROOT_X[i]
            val ty = (24f - 16f * local.y) - ROOT_Y
            val tz = 16f * local.z - ROOT_Z[i]
            solveLeg(i, tx, ty, tz)
        }
    }

    /** The drawn foot follows its target through a short per-frame filter, so no source
     *  of foot motion (a server replant, a resync, a late plant) can jump on screen. */
    private fun smoothFoot(leg: Int, target: Vector3d): Vector3d {
        val now = System.nanoTime()
        val d = drawnFoot[leg]
        if (!drawnLive[leg] || d.distanceSquared(target.x, target.y, target.z) > RESNAP_SQ) {
            d.set(target.x, target.y, target.z)
            drawnLive[leg] = true
        } else {
            val dt = ((now - drawnNanos[leg]) / 1.0e9).coerceIn(0.0, MAX_FRAME_SECONDS)
            val k = 1.0 - Math.exp(-dt / FOOT_SMOOTH_SECONDS)
            d.add((target.x - d.x) * k, (target.y - d.y) * k, (target.z - d.z) * k)
        }
        drawnNanos[leg] = now
        return d
    }

    /**
     * A leg with nothing under it hangs from its knee: the thigh points out and down to
     * a knee spot, and the calf hangs from the knee along world down, a little bent.
     * The knee leads every motion, and the slow joint filter lets the calf trail it as
     * a pendulum. A hanging leg still shows the mood: a fall starts with a short panic
     * paddle that then relaxes, a dance swings all knees gently to the beat, and a
     * strain trembles.
     */
    private fun hangLeg(leg: Int, qInv: Quaternionf, partialTick: Float) {
        val down = scratch.set(0f, -1f, 0f).rotate(qInv)
        // Model px axes: (-16x, -16y, 16z) of the body-local direction.
        val downX = -down.x
        val downY = -down.y
        val downZ = down.z
        var out = KNEE_OUT_PX
        var dy = downY * KNEE_DOWN_PX
        var swing = 0f
        val time = clockTicks + partialTick
        // A tilted fall rights itself with the legs; the panic paddle gives way to it.
        val right = if (flailing) rightingWeight(down.y) else 0f
        val paddle = paddleWeight(right)
        val panic = panicWeight(partialTick) * (1f - right) * (1f - paddle)
        // Diagonal pairs move together, the other pair half a cycle behind.
        val pair = (leg % 2) * Math.PI.toFloat()
        // The knee's planned acceleration (px per tick²): along the leg's diagonal from
        // the panic paddle, along the model's z from the dance.
        var accelOut = 0f
        var accelZ = 0f
        if (panic > 0f) {
            val phase = time * PANIC_RATE + pair
            out += cos(phase) * PANIC_REACH_PX * panic
            dy -= (sin(phase) * 0.5f + 0.5f) * PANIC_LIFT_PX * panic
            accelOut = -cos(phase) * PANIC_REACH_PX * panic * PANIC_RATE * PANIC_RATE
        }
        if (dancing) {
            val t = danceTicks + partialTick
            swing = danceSwingPx(leg, t)
            accelZ = danceSwingPx(leg, t + 1f) - 2f * swing + danceSwingPx(leg, t - 1f)
        }
        dy += rightingStrokePx(leg, down.x, down.z, time, right)
        val dirX = -sin(BIND_HIP_YAW[leg])
        val dirZ = -cos(BIND_HIP_YAW[leg])
        val reach = paddleReach(qInv, time, pair, paddle)
        var kneeX = dirX * out + downX * KNEE_DOWN_PX - aimLocal.x * reach
        var kneeZ = dirZ * out + swing + downZ * KNEE_DOWN_PX + aimLocal.z * reach
        // The knee stays out from its hip along the leg's diagonal. A swing or a tilt
        // that took it under and behind the hip turned the hip half round: a flip.
        val along = kneeX * dirX + kneeZ * dirZ
        if (along < HANG_MIN_OUT_PX) {
            kneeX += dirX * (HANG_MIN_OUT_PX - along)
            kneeZ += dirZ * (HANG_MIN_OUT_PX - along)
        }
        val tau = hangSmoothSeconds(Math.max(panic, right))
        val yawDelta = swivelToward(leg, kneeX, kneeZ)
        lastYawDelta[leg] = yawDelta
        val hipYaw = BIND_HIP_YAW[leg] + yawDelta
        val fx = -sin(hipYaw)
        val fz = -cos(hipYaw)
        val thigh = atan2(dy - 0.25f, kneeX * fx + kneeZ * fz - PIVOT_FORWARD)
        // World down in the leg plane, as an angle below the plane's forward; the calf
        // swings about it as a pendulum the knee's motion drives.
        val hangAngle = atan2(downY, downX * fx + downZ * fz)
        val kneeAccel = (dirX * accelOut) * fx + (dirZ * accelOut + accelZ) * fz
        val pendulum = swingCalf(leg, kneeAccel)
        val calf = (wrapRad(hangAngle - pendulum - thigh) + KNEE_HANG_BEND).coerceIn(KNEE_MIN_BEND, KNEE_MAX_BEND)
        smoothJoints(leg, hipYaw, thigh, calf, tau)
        tremble(leg)
    }

    /**
     * Steps leg [leg]'s calf pendulum to now and returns its swing (rad). [kneeAccel]
     * is the knee's acceleration along the leg plane's forward (px per tick²): a knee
     * that speeds forward leaves the calf behind, and at the end of a swing the calf
     * swings through. Lightly damped, so it settles when the knee stops.
     */
    private fun swingCalf(leg: Int, kneeAccel: Float): Float {
        val now = System.nanoTime()
        if (!calfLive[leg]) {
            calfSwing[leg] = 0f
            calfSwingRate[leg] = 0f
            calfNanos[leg] = now
            calfLive[leg] = true
            return 0f
        }
        val dt = ((now - calfNanos[leg]) / 1.0e9).coerceIn(0.0, MAX_FRAME_SECONDS).toFloat()
        calfNanos[leg] = now
        val accel = (kneeAccel * PX_TICK2_TO_MS2 * CALF_DRIVE_SHARE).coerceIn(-CALF_MAX_DRIVE, CALF_MAX_DRIVE)
        val steps = Math.max(1, Math.ceil((dt / CALF_STEP_SECONDS).toDouble()).toInt())
        val h = dt / steps
        var theta = calfSwing[leg]
        var rate = calfSwingRate[leg]
        repeat(steps) {
            val alpha = -(GRAVITY_MS2 * sin(theta) + accel * cos(theta)) / CALF_LENGTH_M - CALF_DAMPING * rate
            rate += alpha * h
            theta = (theta + rate * h).coerceIn(-CALF_MAX_SWING, CALF_MAX_SWING)
        }
        calfSwing[leg] = theta
        calfSwingRate[leg] = rate
        return theta
    }

    private fun hangSmoothSeconds(lively: Float): Double = when {
        lively > 0f -> LIMP_SMOOTH_SECONDS + (LIVELY_SMOOTH_SECONDS - LIMP_SMOOTH_SECONDS) * lively.toDouble()
        dancing -> DANCE_SMOOTH_SECONDS
        else -> LIMP_SMOOTH_SECONDS
    }

    /** 0 upright, up to 1 at [RIGHT_FULL_DROP] of world up lost; [downY] is world down's
     *  body-local y. */
    private fun rightingWeight(downY: Float): Float = ((1f + downY) / RIGHT_FULL_DROP).coerceIn(0f, 1f)

    /**
     * Knee drop (model px, y down) of leg [leg]'s righting stroke. The body must roll
     * its up toward world up; the legs sweep the other way round it, so their reaction
     * rolls the body. A roll about a level axis moves each knee only up or down in the
     * body. The sweep is quick and the return slow, so the stroke reads as a push.
     * [downX], [downZ] are world down in body-local axes.
     */
    private fun rightingStrokePx(leg: Int, downX: Float, downZ: Float, time: Float, weight: Float): Float {
        if (weight <= 0f) return 0f
        val ux = -downX
        val uz = -downZ
        val tilt = sqrt(ux * ux + uz * uz)
        // Upside down the roll has no axis of its own: roll about the body's x.
        val side = if (tilt > RIGHT_MIN_TILT) {
            (ux * ROOT_X[leg] - uz * ROOT_Z[leg]) / (tilt * ROOT_RADIUS_PX)
        } else {
            -ROOT_Z[leg] / ROOT_RADIUS_PX
        }
        val f = (time / RIGHT_CYCLE_TICKS) % 1f
        val stroke = if (f < RIGHT_POWER_SHARE) {
            -cos(Math.PI.toFloat() * f / RIGHT_POWER_SHARE)
        } else {
            cos(Math.PI.toFloat() * (f - RIGHT_POWER_SHARE) / (1f - RIGHT_POWER_SHARE))
        }
        return side * stroke * RIGHT_REACH_PX * weight
    }

    /** With somewhere to go the legs paddle that way instead of in panic; a tilted body
     *  still rights itself first ([right]). */
    private fun paddleWeight(right: Float): Float =
        if (flailing && airAim.lengthSquared() > 0f) 1f - right else 0f

    /** The knee's paddle offset (px) along [aimLocal], set here to the aim in body axes. */
    private fun paddleReach(qInv: Quaternionf, time: Float, pair: Float, paddle: Float): Float {
        if (paddle <= 0f) {
            aimLocal.zero()
            return 0f
        }
        aimLocal.set(airAim).rotate(qInv)
        return paddleStroke(time, pair) * PADDLE_REACH_PX * paddle
    }

    /** Knee reach along the paddle aim (−1 back … 1 forward): a quick sweep back, the push,
     *  then a slow reach forward. Diagonal pairs ([pair]) stroke half a cycle apart. */
    private fun paddleStroke(time: Float, pair: Float): Float {
        val f = ((time / PADDLE_CYCLE_TICKS + pair / (2f * Math.PI.toFloat())) % 1f + 1f) % 1f
        return if (f < PADDLE_POWER_SHARE) {
            cos(Math.PI.toFloat() * f / PADDLE_POWER_SHARE)
        } else {
            -cos(Math.PI.toFloat() * (f - PADDLE_POWER_SHARE) / (1f - PADDLE_POWER_SHARE))
        }
    }

    /** 1 for the first [PANIC_TICKS] of a fall, then down to 0 over [PANIC_FADE_TICKS]. */
    private fun panicWeight(partialTick: Float): Float {
        if (!flailing) return 0f
        val t = airTicks + partialTick
        return (1f - (t - PANIC_TICKS) / PANIC_FADE_TICKS).coerceIn(0f, 1f)
    }

    /**
     * Two-bone solve in the sagittal plane of hip swivel [yawDelta], into [solvedThigh]
     * and [solvedCalf]. The plane distance is the target PROJECTED into the plane: the
     * raw off-plane distance made a turned leg reach for length it cannot have. With
     * [foldIn], a foot inward of the hip folds under by at most [HIP_INWARD_PX]. Returns
     * false when the thigh or calf passes through the plate.
     */
    private fun planeSolve(leg: Int, yawDelta: Float, tx: Float, ty: Float, tz: Float, foldIn: Boolean): Boolean {
        val hipYaw = BIND_HIP_YAW[leg] + yawDelta
        val fx = -sin(hipYaw)
        val fz = -cos(hipYaw)
        val horiz = tx * fx + tz * fz
        // Relative to the thigh pivot (y 0.25, z -1.2517 along the leg).
        val vy = ty - 0.25f
        val vz = -(if (foldIn) horiz.coerceAtLeast(-HIP_INWARD_PX) else horiz) + 1.2517f
        val d = Mth.clamp(hypot(vy, vz), 2f, L1 + L2 - 0.05f)
        val base = atan2(vy, -vz)
        val cosA = ((L1 * L1 + d * d - L2 * L2) / (2f * L1 * d)).coerceIn(-1f, 1f)
        val cosB = ((L1 * L1 + L2 * L2 - d * d) / (2f * L1 * L2)).coerceIn(-1f, 1f)
        solvedThigh = base - acos(cosA)
        solvedCalf = Math.PI.toFloat() - acos(cosB)
        // Model px, y down: pivot, knee, and the foot the solve reaches.
        val px = ROOT_X[leg] + fx * PIVOT_FORWARD
        val py = ROOT_Y + 0.25f
        val pz = ROOT_Z[leg] + fz * PIVOT_FORWARD
        val kf = L1 * cos(solvedThigh)
        val kx = px + fx * kf
        val ky = py + L1 * sin(solvedThigh)
        val kz = pz + fz * kf
        val ff = d * cos(base)
        val footX = px + fx * ff
        val footY = py + d * sin(base)
        val footZ = pz + fz * ff
        for (k in 1..LIMB_SAMPLES) {
            val t = k.toFloat() / LIMB_SAMPLES
            val u = 0.5f + 0.5f * t
            if (inPlate(px + (kx - px) * u, py + (ky - py) * u, pz + (kz - pz) * u)) return false
            if (inPlate(kx + (footX - kx) * t, ky + (footY - ky) * t, kz + (footZ - kz) * t)) return false
        }
        return true
    }

    /** The near half of the thigh sits in the plate at its socket, so the test starts
     *  past it. */
    private fun inPlate(x: Float, y: Float, z: Float): Boolean =
        Math.abs(x) < PLATE_HALF_PX + LIMB_RADIUS_PX && Math.abs(z) < PLATE_HALF_PX + LIMB_RADIUS_PX &&
            y > PLATE_TOP_PX - LIMB_RADIUS_PX && y < PLATE_BOTTOM_PX + LIMB_RADIUS_PX

    /** Joint angles follow their solve through the same per-frame filter as the feet, so
     *  a jump in the two-bone solution never shows as a jump of the knee. */
    private fun smoothJoints(leg: Int, yaw: Float, thigh: Float, calf: Float, tau: Double) {
        val now = System.nanoTime()
        val i = leg * 3
        if (!jointLive[leg]) {
            jointAngles[i] = yaw
            jointAngles[i + 1] = thigh
            jointAngles[i + 2] = calf
            jointLive[leg] = true
        } else {
            val dt = ((now - jointNanos[leg]) / 1.0e9).coerceIn(0.0, MAX_FRAME_SECONDS)
            val k = (1.0 - Math.exp(-dt / tau)).toFloat()
            jointAngles[i] += wrapRad(yaw - jointAngles[i]) * k
            jointAngles[i + 1] += wrapRad(thigh - jointAngles[i + 1]) * k
            jointAngles[i + 2] += wrapRad(calf - jointAngles[i + 2]) * k
        }
        renderAngles[i] = jointAngles[i]
        renderAngles[i + 1] = jointAngles[i + 1]
        renderAngles[i + 2] = jointAngles[i + 2]
        jointNanos[leg] = now
    }

    private fun solveLeg(leg: Int, tx: Float, ty: Float, tz: Float, tau: Double = FOOT_SMOOTH_SECONDS) {
        // A foot almost under the hip has no stable direction: the swivel holds. Else it
        // turns from the last swivel the short way, toward the foot.
        val last = lastYawDelta[leg]
        val want = swivelToward(leg, tx, tz)
        // Any swivel is valid while the leg stays out of the plate. Else step back toward
        // the last swivel; with none clear, the last one folds the foot in as before.
        var yawDelta = last
        var clear = false
        for (step in 0 until SWIVEL_STEPS) {
            val candidate = want + (last - want) * step / SWIVEL_STEPS
            if (planeSolve(leg, candidate, tx, ty, tz, false)) {
                yawDelta = candidate
                clear = true
                break
            }
        }
        if (!clear) planeSolve(leg, yawDelta, tx, ty, tz, true)
        lastYawDelta[leg] = yawDelta
        smoothJoints(leg, BIND_HIP_YAW[leg] + yawDelta, solvedThigh, solvedCalf, tau)
        tremble(leg)
    }

    /** The hip swivel that faces a target at ([tx], [tz]) px from the root: from the last
     *  swivel the short way, held while the target is almost under the hip. */
    private fun swivelToward(leg: Int, tx: Float, tz: Float): Float {
        val last = lastYawDelta[leg]
        val toward = wrapRad(atan2(-tx, -tz) - BIND_HIP_YAW[leg])
        return when {
            !jointLive[leg] -> toward.coerceIn(-MAX_SWIVEL, MAX_SWIVEL)
            hypot(tx, tz) < YAW_HOLD_PX -> last
            else -> (last + wrapRad(toward - last)).coerceIn(-MAX_SWIVEL, MAX_SWIVEL)
        }
    }

    /** Overloaded: a fine fast tremor on every joint, on the drawn angles only. */
    private fun tremble(leg: Int) {
        if (!strained) return
        val t = tremorPhase
        renderAngles[leg * 3] += sin(t * 2.6f + leg * 1.93f) * 0.03f
        renderAngles[leg * 3 + 1] += sin(t * 3.1f + leg * 2.41f) * 0.04f
        renderAngles[leg * 3 + 2] += sin(t * 2.8f + leg * 1.31f) * 0.045f
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

        /** Diagonal trot: a leg may lift only while the *other* diagonal pair is planted. */
        internal inline fun otherPairPlanted(leg: Int, planted: (Int) -> Boolean): Boolean {
            val diagonalA = leg == 0 || leg == 2
            return planted(if (diagonalA) 1 else 0) && planted(if (diagonalA) 3 else 2)
        }

        /** Swing progress per tick: [STEP_RATE] up to walk speed, then faster with speed
         *  so a fast body's feet keep up instead of tripping the force-step. */
        internal fun swingRate(speedPerTick: Double): Float =
            (STEP_RATE * (speedPerTick / SWING_REF_SPEED).toFloat()).coerceIn(STEP_RATE, MAX_STEP_RATE)

        /**
         * Swing curve from [from] to [to], into [out]. The foot lifts in place with a
         * small pull-back, carries forward at clearance, then places from above. A
         * step up rises before the foot moves; a step down drops after it arrives.
         */
        internal fun swingPoint(
            from: Vec3,
            to: Vec3,
            up: Vector3dc,
            p: Double,
            out: Vector3d,
            minLift: Double = 0.0,
        ): Vector3d {
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dz = to.z - from.z
            val rise = dx * up.x() + dy * up.y() + dz * up.z()
            val px = dx - up.x() * rise
            val py = dy - up.y() * rise
            val pz = dz - up.z() * rise
            val reach = Math.sqrt(px * px + py * py + pz * pz)
            val lift = Math.max((SWING_LIFT_BASE + SWING_LIFT_PER_BLOCK * reach).coerceAtMost(SWING_LIFT_MAX), minLift)
            val carry = smootherstep((p - SWING_LIFT_SHARE) / (1.0 - SWING_LIFT_SHARE - SWING_PLACE_SHARE))
            val climb = if (rise > 0.0) smoothstep(p * 2.0) else smoothstep(p * 2.0 - 1.0)
            val arc = smoothstep(p / SWING_UP_SHARE) * (1.0 - smoothstep((p - SWING_DOWN_START) / (1.0 - SWING_DOWN_START)))
            val pull = sin(Math.PI * (p / SWING_UP_SHARE).coerceIn(0.0, 1.0)) * SWING_PULL_BACK
            return out.set(from.x, from.y, from.z)
                .add(px * (carry - pull), py * (carry - pull), pz * (carry - pull))
                .fma(rise * climb + lift * arc, up)
        }

        private fun smoothstep(t: Double): Double {
            val c = t.coerceIn(0.0, 1.0)
            return c * c * (3.0 - 2.0 * c)
        }

        private fun smootherstep(t: Double): Double {
            val c = t.coerceIn(0.0, 1.0)
            return c * c * c * (c * (c * 6.0 - 15.0) + 10.0)
        }

        /** Home-radius scale for a sit blend: sitting pulls the feet in under the plate. */
        internal fun tuckScale(sitBlend: Float): Float =
            1f - SIT_TUCK_AMOUNT * (sitBlend * sitBlend * (3f - 2f * sitBlend))

        /** Foot ray along -bodyUp from [home], skipping [ownShipId] — feet never plant on
         *  the walker's own cargo. Full anatomical reach (~1.8 from the hip): on a tilted
         *  body the real ground can sit well below the rotated home plane. */
        internal fun castFootRay(
            level: Level,
            entity: Entity,
            home: Vec3,
            upX: Double,
            upY: Double,
            upZ: Double,
            ownShipId: Long?,
        ): BlockHitResult = clipFoot(
            level, entity,
            home.add(upX * FOOT_RAY_UP, upY * FOOT_RAY_UP, upZ * FOOT_RAY_UP),
            home.add(upX * -FOOT_RAY_DOWN, upY * -FOOT_RAY_DOWN, upZ * -FOOT_RAY_DOWN),
            ownShipId,
        )

        /** A leg-length ray from [from] to [to], with the foot ray's start-inside retry. */
        internal fun castLegRay(level: Level, entity: Entity, from: Vec3, to: Vec3, ownShipId: Long?): BlockHitResult =
            clipFoot(level, entity, from, to, ownShipId)

        /** Vanilla clip from inside a shape reports a hit AT the start point, which sank
         *  feet into an upslope step: cast once more from further back, keep a clean hit. */
        private fun clipFoot(level: Level, entity: Entity, from: Vec3, to: Vec3, ownShipId: Long?): BlockHitResult {
            val hit = clipShips(level, entity, from, to, ownShipId)
            if (!hit.isInside) return hit
            val back = from.subtract(to).normalize().scale(FOOT_RAY_RETRY_BACK)
            val retry = clipShips(level, entity, from.add(back), to, ownShipId)
            return if (retry.type == HitResult.Type.MISS || retry.isInside) hit else retry
        }

        private fun clipShips(level: Level, entity: Entity, from: Vec3, to: Vec3, ownShipId: Long?): BlockHitResult =
            level.clipIncludeShips(
                ConstriliarCollision.Clip(from, to, entity),
                true,
                ownShipId,
            )

        internal const val STEP_RATE = 0.18f
        /** Speed (blocks/tick) above which swings shorten, 2.4 m/s: a longer stride let
         *  stance feet trail past [FORCE_STEP_SQ] and break the trot pairs. */
        private const val SWING_REF_SPEED = 0.12
        /** Fastest swing: two and a half ticks. */
        private const val MAX_STEP_RATE = 0.4f
        private const val PHASE_WRAP = 72_000f
        private const val FOOTSTEP_VOLUME = 0.3f
        internal const val SIT_TUCK_RATE = 0.12f
        /** Foot ray extents along the body normal from the home plane: upslope legs
         *  plant up to 0.75 above it, downslope legs stretch to 1.7 below. */
        internal const val FOOT_RAY_UP = 0.75
        /** Extra start offset for the re-cast when a foot ray starts inside a block. */
        private const val FOOT_RAY_RETRY_BACK = 0.5
        /** Fraction of hip→body-centre the miss-fallback foot ray steps inward. */
        internal const val FOOT_SEARCH_INWARD = 0.35
        /** World-down fallback reach multiplier over the body-frame ray. */
        internal const val FOOT_FALLBACK_REACH = 1.6
        /** Surface-normal Y below which the world-down fallback ray is skipped (~45°). */
        private const val FOOT_FALLBACK_MIN_UP_Y = 0.7f
        /** Dangling-foot drop below the home when no surface is in reach. */
        internal const val DROOP = 0.35
        /** A planted foot this far from its home snaps instead of stepping (teleport). */
        private const val RESNAP_SQ = 4.0 * 4.0
        internal const val FOOT_RAY_DOWN = 1.7
        /** How far the sitting homes pull in toward the body (fraction of home radius). */
        internal const val SIT_TUCK_AMOUNT = 0.3f
        /** Synced swing progress cap while the server still holds the leg up. */
        private const val SYNC_SWING_HOLD = 0.9f
        private const val TAP_DELAY_TICKS = 2
        /** A planted foot moved further than this by the server swings to its new spot. */
        private const val REPLANT_SQ = 0.05 * 0.05
        private const val RETARGET_SQ = 0.25 * 0.25
        /** Swing ground samples along the swing, how high above the line they start, and
         *  the gap kept over the highest one. */
        private val CLEAR_SAMPLES = doubleArrayOf(0.25, 0.5, 0.75)
        private const val CLEAR_PROBE_UP = 1.5
        private const val FOOT_CLEARANCE = 0.15
        private const val CLEAR_MAX = 0.9
        /** Drawn-foot filter time constant, and the longest frame it integrates. */
        private const val FOOT_SMOOTH_SECONDS = 0.04
        private const val MAX_FRAME_SECONDS = 0.1
        private const val TAP_RATE = 0.9f
        private const val TAP_LIFT = 0.06
        private const val SWING_LIFT_BASE = 0.12
        private const val SWING_LIFT_PER_BLOCK = 0.2
        private const val SWING_LIFT_MAX = 0.4
        /** Swing shares: lift in place, then carry, then place from above. */
        private const val SWING_LIFT_SHARE = 0.15
        private const val SWING_PLACE_SHARE = 0.15
        private const val SWING_UP_SHARE = 0.3
        private const val SWING_DOWN_START = 0.65
        /** Pull-back at lift-off, as a share of the stride. */
        private const val SWING_PULL_BACK = 0.08
        internal const val HOME_REFRESH_TICKS = 20
        internal const val STILL_EPS_SQ = 1.0e-6
        internal const val STILL_DOT = 1f - 1.0e-6f
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
        /** Swivel bound from the bind diagonal: past a quarter turn the knee points in
         *  toward the body. */
        private const val MAX_SWIVEL = (Math.PI / 2.0).toFloat()
        private const val SWIVEL_STEPS = 6
        private const val PIVOT_FORWARD = 1.2517f
        /** The plate box in model px (y down), and the limb half-thickness. */
        private const val PLATE_HALF_PX = 8f
        private const val PLATE_TOP_PX = 16f
        private const val PLATE_BOTTOM_PX = 20f
        private const val LIMB_RADIUS_PX = 0.5f
        private const val LIMB_SAMPLES = 4
        private const val HIP_INWARD_PX = 8f
        /** Foot this close to the hip axis (px): its direction is noise, the swivel holds. */
        private const val YAW_HOLD_PX = 3f
        /** Hanging knee spot from the root: out along the leg's diagonal, and down (px). */
        private const val KNEE_OUT_PX = 9f
        private const val KNEE_DOWN_PX = 9f
        /** A hanging calf bends this much past world down, held within these bends (rad). */
        private const val KNEE_HANG_BEND = 0.15f
        private const val KNEE_MIN_BEND = 0.1f
        private const val KNEE_MAX_BEND = 2.6f
        /** Calf pendulum: length (the calf, L2 px), damping (1/s), largest swing (rad),
         *  and the sim step (s). The knee joint is stiff: only [CALF_DRIVE_SHARE] of the
         *  knee's push reaches the calf, capped at [CALF_MAX_DRIVE] (m/s²). The dance
         *  swing is near the calf's own period; at full drive it swung to its limit. */
        private const val CALF_LENGTH_M = 14.3053f / 16f
        private const val CALF_DAMPING = 1.5f
        private const val CALF_DRIVE_SHARE = 0.3f
        private const val CALF_MAX_DRIVE = 30f
        private const val CALF_MAX_SWING = 1.2f
        private const val CALF_STEP_SECONDS = 1f / 120f
        private const val GRAVITY_MS2 = 9.8f
        /** Model px per tick² in m/s²: 1/16 block, 20 ticks a second. */
        private const val PX_TICK2_TO_MS2 = 400f / 16f
        /** Least reach of a hanging foot out along its leg's diagonal (px). */
        private const val HANG_MIN_OUT_PX = 4f
        /** Joint filter time of a hanging leg (s), slow so it swings loose. */
        private const val LIMP_SMOOTH_SECONDS = 0.18
        /** Joint filter time of a panicking hanging leg: quick enough to show. */
        private const val LIVELY_SMOOTH_SECONDS = 0.05
        /** Fall panic: full for 1.5 s, then relaxes over 0.5 s. Paddle rate (rad/tick) and size (px). */
        private const val PANIC_TICKS = 30
        private const val PANIC_FADE_TICKS = 10
        private const val PANIC_RATE = 1.0f
        /** Righting stroke: full at this much of world up lost (1 = on its side), the
         *  knee's sweep each way (px), the cycle (ticks) and its quick share. */
        private const val RIGHT_FULL_DROP = 0.7f
        private const val RIGHT_REACH_PX = 8f
        /** Air paddle: knee reach along the aim (px), stroke length (ticks), and the share
         *  of it that sweeps back. */
        private const val PADDLE_REACH_PX = 7f
        private const val PADDLE_CYCLE_TICKS = 36f
        private const val PADDLE_POWER_SHARE = 0.35f
        private const val RIGHT_CYCLE_TICKS = 10f
        private const val RIGHT_POWER_SHARE = 0.3f
        private const val RIGHT_MIN_TILT = 0.2f
        private val ROOT_RADIUS_PX = sqrt(7f * 7f + 7f * 7f)
        private const val PANIC_REACH_PX = 7f
        private const val PANIC_LIFT_PX = 10f
        /** Dance pendulum of a hanging knee: swing to the front and to the back (px). */
        private const val DANCE_SWING_PX = 8f
        /** Mid-swing lead (-) or lag (+) of each leg, as a share of a half swing. */
        private val DANCE_LAG = floatArrayOf(-0.12f, -0.04f, 0.04f, 0.12f)

        /**
         * A hanging knee's dance swing (model px) along the model's +z (back) at
         * [danceTime] ticks of game time. All legs swing as pendulums in time with the
         * body: each apex falls on a side bob, two beats apart. Mid-swing each leg runs
         * a little ahead or behind, but all meet at the apex. The server uses it too,
         * for the legs' push on the body.
         */
        internal fun danceSwingPx(leg: Int, danceTime: Float): Float {
            val beat = danceTime / ConstriliarEntity.DANCE_BEAT.toFloat()
            val halfSwing = (beat - 0.5f) / 2f
            val apex = Math.floor(halfSwing.toDouble()).toFloat()
            val f = halfSwing - apex
            val warped = f + DANCE_LAG[leg] * 4f * f * (1f - f)
            return cos(Math.PI.toFloat() * (apex + warped)) * DANCE_SWING_PX
        }
        private const val DANCE_SMOOTH_SECONDS = 0.1
        private const val TAU = (2.0 * Math.PI).toFloat()

        // Home foot spots in body-local blocks (+Y up): diagonally out from each hip.
        // Diagonal radius = hip |(0.4375, 0.4375)| + bind reach 14.586px/16 ≈ 1.53;
        // per-axis = 1.53 · cos45°.
        internal const val HOME_R = 1.0821f
        internal val HOME_LOCAL_X = floatArrayOf(HOME_R, -HOME_R, -HOME_R, HOME_R)
        internal val HOME_LOCAL_Z = floatArrayOf(-HOME_R, -HOME_R, HOME_R, HOME_R)
    }
}
