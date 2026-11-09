package org.shipwrights.enderkinesis.blockentity

import org.valkyrienskies.mod.common.dimensionId
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.shipwrights.enderkinesis.compat.create.CreateCompat
import org.shipwrights.enderkinesis.mixin.ServerGamePacketListenerAccessor
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKSounds
import net.minecraft.sounds.SoundSource
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.core.api.util.GameTickOnly
import org.shipwrights.enderkinesis.util.EntitylessClip
import org.shipwrights.enderkinesis.util.dynamicBody
import org.shipwrights.enderkinesis.util.BodyHit
import org.shipwrights.enderkinesis.util.WorldBox
import org.shipwrights.enderkinesis.util.BoxSearchScratch
import org.shipwrights.enderkinesis.util.forEachShipBlockInBox
import org.shipwrights.enderkinesis.util.standaloneBodiesInBox

/**
 * Backing block entity for [AetherPadBlock]. Mirrored ship/world interaction model:
 *
 *  - **Ship-resident pad** (ship → world): per-pad trace in `FACING` direction; if world
 *    ground (or fluid) is in range, [physTick] applies a `-FACING` lift force on the host
 *    ship at the pad's shipyard centre, with mass-aware critical damping along the lift
 *    axis and lateral friction perpendicular to it. Hovercraft / Trackwork-wheel pattern.
 *
 *  - **World-placed pad** (world → ship): the same per-pad trace, but finds the closest
 *    *ship* whose AABB the beam enters. [physTick] applies a `+FACING` push on that target
 *    at the world hit point projected back into the target's shipyard, with the same
 *    proximity-falloff spring + critical damping + lateral friction. Same maths as the
 *    ship-resident path — only the sign of the push direction and the target ship differ.
 *
 *  - **Entity beam** (world → entity): forward 1-block column, quadratic distance falloff
 *    (stronger near the pad), Create-fan-style velocity lerp toward a per-tick target
 *    speed. Gravity is cancelled on airborne living entities while in the beam so the
 *    soft per-tick push isn't dominated by the −0.08 b/t gravity tax.
 *
 *  All three branches scale linearly with the pad's redstone `POWER` (0–15). POWER 0 = pad
 *  fully inert (no trace, no push, no particles). POWER 15 = the constants below in full.
 *  Intermediate values scale proportionally.
 */
@OptIn(VsBeta::class)
class AetherPadBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.AETHER_PAD.get(), pos, state),
    BlockEntityPhysicsListener {

    @Volatile override lateinit var dimension: DimensionId

    // Server thread only: the beam searches of this pad.
    private val boxScratch = BoxSearchScratch()

    @Volatile private var cachedFacing: Direction = state.getValue(BlockStateProperties.FACING)
    @Volatile private var cachedPower: Int = state.getValue(BlockStateProperties.POWER)

    // Ship-resident path: distance from pad centre to nearest world surface along FACING.
    // -1 = "not ship-resident this tick".
    @Volatile private var cachedGroundDistance: Double = -1.0

    // When the cached distances were traced (20 Hz); physTick extrapolates them to its own step.
    @Volatile private var tracedAtNanos: Long = System.nanoTime()

    // Server thread only.
    private var lastGroundSoundAt = Long.MIN_VALUE / 2

    // World-placed path: closest ship in beam (single target). -1 shipId = no target.
    @Volatile private var cachedTargetShipId: Long = -1L
    @Volatile private var cachedTargetBodyId: Long = -1L
    @Volatile private var cachedTargetDistance: Double = -1.0
    @Volatile private var cachedTargetModelX: Double = 0.0
    @Volatile private var cachedTargetModelY: Double = 0.0
    @Volatile private var cachedTargetModelZ: Double = 0.0

    // World pose cached for physTick — physTick can't safely call shipToWorld.
    @Volatile private var cachedPadWorldX: Double = 0.0
    @Volatile private var cachedPadWorldY: Double = 0.0
    @Volatile private var cachedPadWorldZ: Double = 0.0
    @Volatile private var cachedFacingWorldX: Double = 0.0
    @Volatile private var cachedFacingWorldY: Double = 0.0
    @Volatile private var cachedFacingWorldZ: Double = 0.0

    fun commonTick(level: Level, pos: BlockPos, state: BlockState) {
        cachedFacing = state.getValue(BlockStateProperties.FACING)
        cachedPower = state.getValue(BlockStateProperties.POWER)

        // POWER 0 → fully inert. Clear server-side caches so a stale stash from a
        // previous powered tick can't drive any physTick force.
        if (cachedPower <= 0) {
            cachedGroundDistance = -1.0
            cachedTargetShipId = -1L
            cachedTargetBodyId = -1L
            cachedTargetDistance = -1.0
            return
        }

        // World pose: derive on both sides. Client uses it to push LocalPlayer locally;
        // server uses it for its own entity push, the physTick branches, and particles.
        val facing = cachedFacing
        val ship = level.getLoadedShipManagingPos(pos)
        val padWorld = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        val facingWorld = Vector3d(
            facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble(),
        )
        if (ship != null) {
            ship.transform.shipToWorld.transformPosition(padWorld)
            ship.transform.shipToWorldRotation.transform(facingWorld)
        }
        cachedPadWorldX = padWorld.x; cachedPadWorldY = padWorld.y; cachedPadWorldZ = padWorld.z
        cachedFacingWorldX = facingWorld.x; cachedFacingWorldY = facingWorld.y; cachedFacingWorldZ = facingWorld.z

        // Entity beam runs on BOTH sides — client pushes its own LocalPlayer for smooth
        // local prediction, server pushes every entity (authoritative). This matches
        // Create's encased fan and is what makes the wind feel smooth: client doesn't
        // wait for a server packet to know about the push.
        pushEntitiesForward(level, padWorld, facingWorld, cachedPower)

        if (level is ServerLevel) {
            tracedAtNanos = System.nanoTime()
            spawnBeamParticles(level, padWorld, facingWorld, cachedPower)
            if (ship != null) {
                // SHIP-RESIDENT: trace WORLD ground for hover lift.
                val hadGround = hasGround(cachedGroundDistance)
                cachedGroundDistance = computeGroundDistance(level, padWorld, facingWorld)
                if (!hadGround && hasGround(cachedGroundDistance)) playGround(level, pos)
                cachedTargetShipId = -1L
                // A body nearer than the ground takes the push, and the host ship pushes off it.
                val column = beamColumn(padWorld, facingWorld, facing, ship.transform.shipToWorldRotation)
                val bodyHit = findClosestBodyInBeam(level, column, padWorld, facingWorld, cachedGroundDistance.coerceAtMost(GROUND_MAX_RANGE), boxScratch)
                cachedTargetBodyId = bodyHit?.bodyId ?: -1L
                cachedTargetDistance = bodyHit?.distance ?: -1.0
            } else {
                // WORLD-PLACED: trace for SHIP in beam (mirror of ship→world).
                cachedGroundDistance = -1.0
                val column = beamColumn(padWorld, facingWorld, facing, null)
                val hit = findClosestShipInBeam(level, column, padWorld, facingWorld, boxScratch)
                val bodyHit = findClosestBodyInBeam(level, column, padWorld, facingWorld, hit?.distance ?: GROUND_MAX_RANGE, boxScratch)
                cachedTargetBodyId = bodyHit?.bodyId ?: -1L
                if (bodyHit != null) {
                    cachedTargetShipId = -1L
                    cachedTargetDistance = bodyHit.distance
                } else if (hit == null) {
                    cachedTargetShipId = -1L
                    cachedTargetDistance = -1.0
                } else {
                    cachedTargetShipId = hit.shipId
                    cachedTargetDistance = hit.distance
                    cachedTargetModelX = hit.modelPos.x
                    cachedTargetModelY = hit.modelPos.y
                    cachedTargetModelZ = hit.modelPos.z
                }
            }
        }
    }

    /** The trace gives [GROUND_MAX_RANGE], not -1, when nothing is in range. */
    private fun hasGround(distance: Double): Boolean = distance >= 0.0 && distance < GROUND_MAX_RANGE

    private fun playGround(level: ServerLevel, pos: BlockPos) {
        if (level.gameTime - lastGroundSoundAt < GROUND_SOUND_GAP_TICKS) return
        lastGroundSoundAt = level.gameTime
        level.playSound(null, pos, EKSounds.AETHER_PAD_GROUND.get(), SoundSource.BLOCKS, GROUND_SOUND_VOLUME, 1f)
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (level.isClientSide) AetherPadClientRegistry.register(level, this)
    }

    override fun setRemoved() {
        level?.let { if (it.isClientSide) AetherPadClientRegistry.unregister(it, this) }
        super.setRemoved()
    }

    @OptIn(PhysTickOnly::class)
    override fun physTick(physShip: PhysShip?, physLevel: PhysLevel) {
        val power = cachedPower
        if (power <= 0) return                              // pad is unpowered

        if (physShip != null) {
            val bodyId = cachedTargetBodyId
            val body = if (bodyId >= 0L) physLevel.dynamicBody(bodyId) else null
            val groundDist = if (body != null) cachedTargetDistance else cachedGroundDistance
            if (groundDist < 0.0 || groundDist >= GROUND_MAX_RANGE) return
            val pushed = applyMirroredHover(
                target = physShip,
                distance = groundDist,
                modelX = blockPos.x + 0.5, modelY = blockPos.y + 0.5, modelZ = blockPos.z + 0.5,
                pushSign = -1.0,                            // host ship gets pushed *against* FACING
                power = power,
                // Pad block is on this same ship — its output scales with the ship's scale.
                padScale = physShip.transform.shipToWorldScaling.x(),
            )
            // Equal and opposite, as a new vector: VS2 keeps the one the ship got.
            body?.applyWorldForceToBodyPos(Vector3d(pushed).negate())
        } else {
            val targetDist = cachedTargetDistance
            if (targetDist < 0.0 || targetDist >= GROUND_MAX_RANGE) return
            val bodyId = cachedTargetBodyId
            if (bodyId >= 0L) {
                val body = physLevel.dynamicBody(bodyId) ?: return
                val v = body.velocity
                // Pad block is in world (no host ship) — no scale factor. At the COM: a body has no shipyard hit point.
                body.applyWorldForceToBodyPos(
                    hoverForce(body.inertiaData.mass, v.x(), v.y(), v.z(), targetDist, +1.0, power, 1.0),
                )
                return
            }
            val shipId = cachedTargetShipId
            if (shipId < 0L) return
            val target = physLevel.getShipById(shipId) ?: return
            applyMirroredHover(
                target = target,
                distance = targetDist,
                modelX = cachedTargetModelX,
                modelY = cachedTargetModelY,
                modelZ = cachedTargetModelZ,
                pushSign = +1.0,                            // target ship gets pushed *with* FACING
                power = power,
                // Pad block is in world (no host ship) — no scale factor.
                padScale = 1.0,
            )
        }
    }

    /**
     * Shared push routine for ship-resident hover and world-pad → ship mirror: a fixed spring with critical
     * damping along the world-frame `FACING` axis (× pushSign), and a fixed lateral friction, at the model position.
     * The forces do not scale with mass, so more pads lift more.
     */
    @OptIn(PhysTickOnly::class)
    private fun applyMirroredHover(
        target: PhysShip,
        distance: Double,
        modelX: Double, modelY: Double, modelZ: Double,
        pushSign: Double,
        power: Int,
        padScale: Double,
    ): Vector3dc {
        // Velocity at the application point in world frame: v_target_COM + ω × r_world.
        // r_world = (modelPos - targetCOMShip) rotated into world.
        val v = target.velocity
        val w = target.angularVelocity
        val comShip = target.transform.positionInShip
        val rWorld = Vector3d(modelX - comShip.x(), modelY - comShip.y(), modelZ - comShip.z())
        target.transform.shipToWorldRotation.transform(rWorld)
        val vpX: Double = v.x() + w.y() * rWorld.z - w.z() * rWorld.y
        val vpY: Double = v.y() + w.z() * rWorld.x - w.x() * rWorld.z
        val vpZ: Double = v.z() + w.x() * rWorld.y - w.y() * rWorld.x
        val force = hoverForce(target.mass, vpX, vpY, vpZ, distance, pushSign, power, padScale)
        target.applyWorldForceToModelPos(force, Vector3d(modelX, modelY, modelZ))
        return force
    }

    /** The hover force for a target of [mass] whose application point moves at velocity vp, as a fresh vector. */
    private fun hoverForce(
        mass: Double,
        vpX: Double, vpY: Double, vpZ: Double,
        distance: Double,
        pushSign: Double,
        power: Int,
        padScale: Double,
    ): Vector3d {
        val powerScale = power / 15.0
        val massShare = mass.coerceAtLeast(1.0) / ASSUMED_PAD_COUNT

        // Push direction in world frame.
        val pushX = cachedFacingWorldX * pushSign
        val pushY = cachedFacingWorldY * pushSign
        val pushZ = cachedFacingWorldZ * pushSign

        val velAlongPush = vpX * pushX + vpY * pushY + vpZ * pushZ
        // A trace from up to 50 ms ago makes the spring lag and adds negative damping.
        val age = ((System.nanoTime() - tracedAtNanos) * 1.0e-9).coerceIn(0.0, MAX_TRACE_AGE)
        val proximity = 1.0 - ((distance + velAlongPush * age).coerceIn(0.0, GROUND_MAX_RANGE) / GROUND_MAX_RANGE)
        val springMag = springForcePerPad(power) * proximity

        // Critical damping for the mass share of one pad: k = peak spring force / range.
        val k = springForcePerPad(power) / GROUND_MAX_RANGE
        val cCrit = 2.0 * Math.sqrt(k * massShare)
        val liftMag = (springMag - cCrit * velAlongPush).coerceAtLeast(0.0)

        val vPerpX = vpX - pushX * velAlongPush
        val vPerpY = vpY - pushY * velAlongPush
        val vPerpZ = vpZ - pushZ * velAlongPush
        val frictionCoef = PAD_FRICTION * proximity * powerScale

        val rawFx = pushX * liftMag - vPerpX * frictionCoef
        val rawFy = pushY * liftMag - vPerpY * frictionCoef
        val rawFz = pushZ * liftMag - vPerpZ * frictionCoef
        // Block-sourced scale³ multiplier (VS2 drag is cross-section ∝ scale²;
        // cubic force cancels that to give scale-invariant ship-lengths-per-second).
        val s3 = padScale * padScale * padScale
        val totalFx = rawFx * s3
        val totalFy = rawFy * s3
        val totalFz = rawFz * s3
        return Vector3d(totalFx, totalFy, totalFz)
    }

    /** Result of a successful ship-in-beam trace. Public so [CreateCompat] can call
     *  [findClosestShipInBeam] from the contraption-tick path. */
    data class ShipHit(
        val shipId: Long,
        val distance: Double,
        val modelPos: Vector3d,                            // shipyard coords on the target
    )

    companion object {
        private const val MAX_TRACE_AGE = 0.1

        /** Spring force of one pad (N) at proximity 1 and full power. */
        const val PAD_FORCE: Double = 100_000.0

        /** Maximum ground-effect detection range, blocks. */
        const val GROUND_MAX_RANGE: Double = 4.0

        private const val GROUND_SOUND_VOLUME = 0.6f
        private const val GROUND_SOUND_GAP_TICKS = 40L

        /** Lateral friction of one pad (N per m/s) at proximity 1 and full power. */
        const val PAD_FRICTION: Double = 16_000.0

        /** Spring force of one pad (N) at proximity 1 and [power] (0-15). The BE and the Create contraption path share it. */
        fun springForcePerPad(power: Int): Double = PAD_FORCE * power / 15.0

        /** Target beam velocity (b/t), applied uniformly across the plateau region of the
         *  beam (i.e. up to `effectiveRange − effectiveTaper`). Must clearly beat gravity:
         *  the ramp `clamp(target − v, ±5)/8` converges to `v = target − 0.64` when
         *  competing against the −0.08 b/t gravity tax (8·0.08 = 0.64), so any value below
         *  ~0.7 leaves the player slowly falling inside the beam. 1.5 b/t = 30 m/s gives
         *  an equilibrium of ~+0.86 b/t up — one pad lifts a player against gravity with
         *  clear margin. (Reach is power-scaled; force is not.) */
        const val BEAM_TARGET_VELOCITY: Double = 1.5

        /** Length (blocks) of the linear-taper zone at the far end of the beam. For
         *  `ENTITY_RANGE = 14` and `BEAM_TAPER_BLOCKS = 8`, the plateau covers
         *  along ∈ [0, 6) at full target velocity, then tapers linearly to 0 between
         *  along = 6 and along = 14. Wider taper = softer force gradient near the
         *  hover-equilibrium height (∂target/∂along = −1.5/8 ≈ −0.19 b/t per block,
         *  vs −0.375 at taper=4), which damps the bounce when an entity oscillates
         *  around the equilibrium point at the top of the beam. */
        const val BEAM_TAPER_BLOCKS: Double = 8.0

        /** Multiplier applied to acceleration when the entity is shift-held. Matches
         *  Create's `512 / 4096 = 1/8` sneak attenuation — gives players a way to
         *  resist the wind by sneaking. */
        const val SNEAK_SCALE_CROUCHING: Double = 1.0 / 8.0

        /** Create's per-tick per-axis acceleration clamp (blocks/tick). Prevents the
         *  velocity delta from going beyond this magnitude on any single axis in a
         *  single tick — caps how snappy the push can be at very close range where
         *  `target − prev` would otherwise be huge. */
        const val FAN_MAX_ACCEL_PER_TICK: Double = 5.0

        /** Create's `1/8` ramp scale — the bounded delta gets multiplied by this
         *  before being added to motion. Combined with the ±5 clamp this gives the
         *  smooth "wind carries you" feel: per-axis velocity approaches target by
         *  1/8 of the (clamped) gap each tick. */
        const val FAN_DELTA_SCALE: Double = 1.0 / 8.0

        /** Forward entity-push range, blocks. 14 blocks per spec — longer reach with a
         *  matched-lower [FAN_SPEED_AT_MAX_POWER] so the velocity gradient is spread
         *  across the larger volume rather than concentrated near the muzzle. */
        const val ENTITY_RANGE: Double = 14.0

        /** Cross-section half-width of the 1-block column for both entity beam and
         *  particle spawn AABB. */
        const val ENTITY_PUSH_HALFWIDTH: Double = 0.5

        /** The critical damping divides the target's mass by this pad count: more pads over-damp slightly, fewer under-damp. */
        const val ASSUMED_PAD_COUNT: Double = 4.0

        /** Beam-particle emission interval (game ticks). Higher = sparser. */
        private const val BEAM_PARTICLE_INTERVAL: Long = 10L

        /** Beam particles spawned per emission per pad. */
        private const val BEAM_PARTICLES_PER_EMIT: Int = 1

        /** Drift speed (blocks/tick) for spawned beam particles along the beam axis. */
        private const val BEAM_PARTICLE_DRIFT: Double = 0.04

        /**
         * Forward entity beam. Quadratic distance falloff (stronger near the pad,
         * markedly weaker far away — per spec) and per-tick velocity lerp toward a
         * cap-targeted speed scaled by [power]/15. Living-entity gravity compensation
         * makes the beam read as wind carrying entities at the cap.
         */
        fun pushEntitiesForward(
            level: Level,
            padWorld: Vector3d,
            facingWorld: Vector3d,
            power: Int,
        ): Int {
            if (power <= 0) return 0
            val powerScale = power / 15.0
            // Power scales the BEAM REACH linearly (along with target velocity).
            // POWER 15 → full 14-block beam; POWER 1 → ~0.93-block stub. The taper zone
            // scales proportionally so the plateau/taper ratio is constant across power
            // levels — a low-power beam is just a miniature version of the full one.
            val effectiveRange = ENTITY_RANGE * powerScale
            val effectiveTaper = BEAM_TAPER_BLOCKS * powerScale
            val taperStart = effectiveRange - effectiveTaper

            // AABB scan box is sized to the effective range too — at low power we don't
            // even iterate entities sitting past the shortened beam.
            val tipX = padWorld.x + facingWorld.x * effectiveRange
            val tipY = padWorld.y + facingWorld.y * effectiveRange
            val tipZ = padWorld.z + facingWorld.z * effectiveRange
            val box = AABB(
                Math.min(padWorld.x, tipX), Math.min(padWorld.y, tipY), Math.min(padWorld.z, tipZ),
                Math.max(padWorld.x, tipX), Math.max(padWorld.y, tipY), Math.max(padWorld.z, tipZ),
            ).inflate(ENTITY_PUSH_HALFWIDTH)

            var pushed = 0
            for (entity in level.getEntities(null as Entity?, box) { it.isAlive }) {
                if (entity.isPassenger) continue
                if (entity is Player && (entity.isSpectator || entity.abilities.flying)) continue
                if (CreateCompat.isContraption(entity)) continue
                val dx = entity.x - padWorld.x
                val dy = entity.y - padWorld.y
                val dz = entity.z - padWorld.z
                val along = dx * facingWorld.x + dy * facingWorld.y + dz * facingWorld.z
                if (along <= 0.0 || along > effectiveRange) continue

                // Block occlusion: a solid block between the pad face and the entity
                // breaks the beam. Without this the beam would pass through walls,
                // floors, and intervening ships — players standing safely behind cover
                // would still get yanked by an unseen pad. Ray starts at the same
                // `RAY_START_OFFSET` past the pad face the ground/ship raycasts use, so
                // the pad's own block doesn't self-occlude; ends at the entity's
                // bbox-centre (mid-body) so the check tracks a "line of sight through
                // the body" rather than feet/eye corner cases. `Fluid.NONE` so water
                // doesn't block wind; `Block.COLLIDER` so signs/torches/etc. don't
                // either — only actually-solid blocks break the beam.
                val rayStart = Vec3(
                    padWorld.x + facingWorld.x * RAY_START_OFFSET,
                    padWorld.y + facingWorld.y * RAY_START_OFFSET,
                    padWorld.z + facingWorld.z * RAY_START_OFFSET,
                )
                val rayEnd = Vec3(entity.x, entity.y + entity.bbHeight * 0.5, entity.z)
                val occlusion = level.clip(
                    EntitylessClip.of(
                        rayStart, rayEnd,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    )
                )
                if (occlusion.type == HitResult.Type.BLOCK) continue

                // Create-fan-style per-axis lerp toward a target velocity, with a
                // **plateau-then-taper** intensity curve along the beam axis:
                //   - For `along ∈ [0, ENTITY_RANGE − BEAM_TAPER_BLOCKS)`: intensity = 1.
                //     Constant full target velocity across most of the beam.
                //   - For `along ∈ [ENTITY_RANGE − BEAM_TAPER_BLOCKS, ENTITY_RANGE]`:
                //     intensity linearly tapers from 1 → 0 over the last
                //     [BEAM_TAPER_BLOCKS]. Smooth exit, no abrupt drop.
                //
                // Replaces Create's `1/distance` curve, which dropped to ~1/14 of muzzle
                // strength at the 14-block edge — past halfway the push was effectively
                // nothing. The plateau curve keeps the rise consistent through the bulk
                // of the beam and only softens within the final taper zone.
                //
                // The per-axis clamp (±5) + `/8` ramp scale stays — same "wind carries
                // you" feel: along-axis converges to `facing × accel`, perpendicular
                // axes damp toward 0 at 1/8 per tick. Runs on BOTH sides so the client
                // predicts its LocalPlayer push locally without needing a
                // SetEntityMotionPacket override.
                val sneakFactor = if (entity.isShiftKeyDown) SNEAK_SCALE_CROUCHING else 1.0
                // Plateau is `along < taperStart`; linear taper from 1 → 0 across the
                // last `effectiveTaper` blocks. Uses the power-scaled bounds so the
                // plateau/taper proportions stay the same at any power level.
                // Force itself is *not* power-scaled — only the reach is. A low-power
                // pad pushes just as hard within its shorter beam.
                val intensity = if (along < taperStart) 1.0
                else ((effectiveRange - along) / effectiveTaper).coerceIn(0.0, 1.0)
                val acceleration = BEAM_TARGET_VELOCITY * intensity * sneakFactor
                val prevMv = entity.deltaMovement
                val xIn = Mth.clamp(
                    facingWorld.x * acceleration - prevMv.x,
                    -FAN_MAX_ACCEL_PER_TICK, FAN_MAX_ACCEL_PER_TICK,
                )
                val yIn = Mth.clamp(
                    facingWorld.y * acceleration - prevMv.y,
                    -FAN_MAX_ACCEL_PER_TICK, FAN_MAX_ACCEL_PER_TICK,
                )
                val zIn = Mth.clamp(
                    facingWorld.z * acceleration - prevMv.z,
                    -FAN_MAX_ACCEL_PER_TICK, FAN_MAX_ACCEL_PER_TICK,
                )
                entity.deltaMovement = prevMv.add(
                    xIn * FAN_DELTA_SCALE,
                    yIn * FAN_DELTA_SCALE,
                    zIn * FAN_DELTA_SCALE,
                )
                if (entity is LivingEntity) entity.fallDistance = 0f
                // Same idea Create uses: reset the anti-cheat's "in air" counter each
                // tick so the vanilla server doesn't kick a player suspended in the beam
                // for "flying is not enabled". `aboveGroundTickCount` is package-private,
                // so we go through [ServerGamePacketListenerAccessor]'s mixin accessor.
                if (entity is ServerPlayer) {
                    (entity.connection as ServerGamePacketListenerAccessor)
                        .`enderkinesis$setAboveGroundTickCount`(0)
                }
                pushed++
            }
            return pushed
        }

        /**
         * Vanilla `Level.clip` in the pad's world `FACING` direction, with `Fluid.ANY` so
         * water/lava surfaces count as ground. Origin is offset past the pad's own block
         * face to prevent the ray from starting inside the world ground block (which made
         * the original implementation return distance 0 → max lift unconditionally).
         */
        fun computeGroundDistance(
            level: ServerLevel,
            padWorld: Vector3d,
            facingWorld: Vector3d,
        ): Double {
            val startX = padWorld.x + facingWorld.x * RAY_START_OFFSET
            val startY = padWorld.y + facingWorld.y * RAY_START_OFFSET
            val startZ = padWorld.z + facingWorld.z * RAY_START_OFFSET
            val from = Vec3(startX, startY, startZ)
            val to = Vec3(
                padWorld.x + facingWorld.x * GROUND_MAX_RANGE,
                padWorld.y + facingWorld.y * GROUND_MAX_RANGE,
                padWorld.z + facingWorld.z * GROUND_MAX_RANGE,
            )
            val ctx = EntitylessClip.of(
                from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY,
            )
            val hit = level.clip(ctx)
            return if (hit.type == HitResult.Type.BLOCK) {
                val padCentre = Vec3(padWorld.x, padWorld.y, padWorld.z)
                hit.location.distanceTo(padCentre).coerceAtMost(GROUND_MAX_RANGE)
            } else {
                GROUND_MAX_RANGE
            }
        }

        private const val RAY_START_OFFSET = 0.51

        /** The 1 by 1 push column from the pad face to [GROUND_MAX_RANGE]; [hostRotation] is null for a world pad. */
        fun beamColumn(padWorld: Vector3d, facingWorld: Vector3d, facing: Direction, hostRotation: Quaterniondc?): WorldBox {
            val half = (GROUND_MAX_RANGE - PAD_HALF) * 0.5
            val extents = Vector3d(PAD_HALF, PAD_HALF, PAD_HALF).setComponent(facing.axis.ordinal, half)
            val centre = Vector3d(facingWorld).mul(PAD_HALF + half).add(padWorld)
            return WorldBox(centre, hostRotation ?: Quaterniond(), extents)
        }

        /**
         * The nearest ship with a real block in [column], or null. The push lands where the beam axis meets the
         * near face, in the target's shipyard, so an off-COM hit tilts the target.
         */
        @OptIn(GameTickOnly::class)
        fun findClosestShipInBeam(
            level: ServerLevel, column: WorldBox, padWorld: Vector3d, facingWorld: Vector3d,
            scratch: BoxSearchScratch = BoxSearchScratch(),
        ): ShipHit? {
            var bestT = GROUND_MAX_RANGE
            var best: org.valkyrienskies.core.api.ships.LoadedServerShip? = null
            level.forEachShipBlockInBox(column, scratch) { ship, _, middle ->
                // A pad that is on this ship but was read as a world pad must not push its own ship.
                val sa = ship.worldAABB
                if (padWorld.x in sa.minX()..sa.maxX() && padWorld.y in sa.minY()..sa.maxY() && padWorld.z in sa.minZ()..sa.maxZ()) return@forEachShipBlockInBox
                val t = ((middle.x() - padWorld.x) * facingWorld.x + (middle.y() - padWorld.y) * facingWorld.y +
                    (middle.z() - padWorld.z) * facingWorld.z - PAD_HALF).coerceAtLeast(PAD_HALF)
                if (t < bestT) { bestT = t; best = ship }
            }
            val ship = best ?: return null
            val modelPos = Vector3d(facingWorld).mul(bestT).add(padWorld)
            ship.transform.worldToShip.transformPosition(modelPos)
            return ShipHit(ship.id, bestT, modelPos)
        }

        /** The nearest standalone body whose shape is in [column] and nearer than [maxDistance], or null. */
        @OptIn(GameTickOnly::class, VsBeta::class)
        fun findClosestBodyInBeam(
            level: ServerLevel, column: WorldBox, padWorld: Vector3d, facingWorld: Vector3d, maxDistance: Double,
            scratch: BoxSearchScratch = BoxSearchScratch(),
        ): BodyHit? {
            var best: BodyHit? = null
            for (id in level.standaloneBodiesInBox(column, scratch)) {
                val a = level.shipObjectWorld.allBodies.getById(id)?.aabb ?: continue
                // The near side of the body bounds along the beam.
                val near = ((a.minX() + a.maxX()) * 0.5 - padWorld.x) * facingWorld.x +
                    ((a.minY() + a.maxY()) * 0.5 - padWorld.y) * facingWorld.y +
                    ((a.minZ() + a.maxZ()) * 0.5 - padWorld.z) * facingWorld.z -
                    (Math.abs(facingWorld.x) * (a.maxX() - a.minX()) + Math.abs(facingWorld.y) * (a.maxY() - a.minY()) +
                        Math.abs(facingWorld.z) * (a.maxZ() - a.minZ())) * 0.5
                val t = near.coerceAtLeast(PAD_HALF)
                if (t <= maxDistance && (best == null || t < best.distance)) best = BodyHit(id, t)
            }
            return best
        }

        private const val PAD_HALF = 0.5

        /**
         * Spawn ender particles uniformly throughout the 1×N×1 beam column where
         * `N = ENTITY_RANGE × power/15`. Both the *length* of the visible beam and the
         * *density* of emissions scale with power: a POWER-1 pad emits sparse particles
         * within a 1-block stub, a POWER-15 pad emits at full density along the full
         * 8-block range. This matches the user's spec — "correct location and distance
         * relative to the power".
         */
        fun spawnBeamParticles(
            level: ServerLevel,
            padWorld: Vector3d,
            facingWorld: Vector3d,
            power: Int,
        ) {
            if (power <= 0) return
            if (level.gameTime % BEAM_PARTICLE_INTERVAL != 0L) return
            val powerScale = power / 15.0
            // Density scales linearly with power — at POWER 1 ~6.7% emission probability
            // per interval, at POWER 15 always-emit. Caps total density at the high end.
            if (level.random.nextDouble() > powerScale) return

            val effectiveRange = (ENTITY_RANGE * powerScale).coerceAtLeast(1.0)
            val rng = level.random
            val tipX = padWorld.x + facingWorld.x * effectiveRange
            val tipY = padWorld.y + facingWorld.y * effectiveRange
            val tipZ = padWorld.z + facingWorld.z * effectiveRange
            val minBx = Math.min(padWorld.x, tipX) - ENTITY_PUSH_HALFWIDTH
            val minBy = Math.min(padWorld.y, tipY) - ENTITY_PUSH_HALFWIDTH
            val minBz = Math.min(padWorld.z, tipZ) - ENTITY_PUSH_HALFWIDTH
            val maxBx = Math.max(padWorld.x, tipX) + ENTITY_PUSH_HALFWIDTH
            val maxBy = Math.max(padWorld.y, tipY) + ENTITY_PUSH_HALFWIDTH
            val maxBz = Math.max(padWorld.z, tipZ) + ENTITY_PUSH_HALFWIDTH
            repeat(BEAM_PARTICLES_PER_EMIT) {
                val x = minBx + rng.nextDouble() * (maxBx - minBx)
                val y = minBy + rng.nextDouble() * (maxBy - minBy)
                val z = minBz + rng.nextDouble() * (maxBz - minBz)
                level.sendParticles(
                    ParticleTypes.REVERSE_PORTAL,
                    x, y, z,
                    0,                                              // count=0 → xyzd = velocity
                    facingWorld.x * BEAM_PARTICLE_DRIFT,
                    facingWorld.y * BEAM_PARTICLE_DRIFT,
                    facingWorld.z * BEAM_PARTICLE_DRIFT,
                    1.0,
                )
            }
        }
    }
}
