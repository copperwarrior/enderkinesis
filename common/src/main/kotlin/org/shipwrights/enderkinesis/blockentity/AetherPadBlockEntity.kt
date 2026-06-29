package org.shipwrights.enderkinesis.blockentity

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
import org.shipwrights.enderkinesis.mixin.ServerGamePacketListenerAccessor
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

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
@OptIn(PhysTickOnly::class, VsBeta::class)
class AetherPadBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.AETHER_PAD.get(), pos, state),
    BlockEntityPhysicsListener {

    @Volatile override lateinit var dimension: DimensionId

    @Volatile private var cachedFacing: Direction = state.getValue(BlockStateProperties.FACING)
    @Volatile private var cachedPower: Int = state.getValue(BlockStateProperties.POWER)

    // Ship-resident path: distance from pad centre to nearest world surface along FACING.
    // -1 = "not ship-resident this tick".
    @Volatile private var cachedGroundDistance: Double = -1.0

    // World-placed path: closest ship in beam (single target). -1 shipId = no target.
    @Volatile private var cachedTargetShipId: Long = -1L
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
            spawnBeamParticles(level, padWorld, facingWorld, cachedPower)
            if (ship != null) {
                // SHIP-RESIDENT: trace WORLD ground for hover lift.
                cachedGroundDistance = computeGroundDistance(level, padWorld, facingWorld)
                cachedTargetShipId = -1L
                cachedTargetDistance = -1.0
            } else {
                // WORLD-PLACED: trace for SHIP in beam (mirror of ship→world).
                cachedGroundDistance = -1.0
                val hit = findClosestShipInBeam(level, padWorld, facingWorld)
                if (hit == null) {
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

    override fun physTick(physShip: PhysShip?, physLevel: PhysLevel) {
        val power = cachedPower
        if (power <= 0) return                              // pad is unpowered

        if (physShip != null) {
            val groundDist = cachedGroundDistance
            if (groundDist < 0.0 || groundDist >= GROUND_MAX_RANGE) return
            applyMirroredHover(
                target = physShip,
                distance = groundDist,
                modelX = blockPos.x + 0.5, modelY = blockPos.y + 0.5, modelZ = blockPos.z + 0.5,
                pushSign = -1.0,                            // host ship gets pushed *against* FACING
                power = power,
                // Pad block is on this same ship — its output scales with the ship's scale.
                padScale = physShip.transform.shipToWorldScaling.x(),
            )
        } else {
            val shipId = cachedTargetShipId
            if (shipId < 0L) return
            val target = physLevel.getShipById(shipId) ?: return
            val targetDist = cachedTargetDistance
            if (targetDist < 0.0 || targetDist >= GROUND_MAX_RANGE) return
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
     * Shared push routine for ship-resident hover and world-pad → ship mirror. Both apply
     * the same spring + critical-damping + lateral-friction model along the world-frame
     * `FACING` axis (× pushSign), at the supplied model position on `target`. All three
     * components are **mass-relative**: the lift force scales with the target's mass so a
     * 1-tonne skiff and a 1-kilotonne galleon both hover at the same equilibrium height
     * (set by [HOVER_LIFT_FACTOR] / [ASSUMED_PAD_COUNT]). Without mass-relative scaling,
     * light ships rocket past the range cap and heavy ships can't lift off the ground.
     *
     *  - **Spring lift**: `springForcePerPad(m, power) × proximity`. Linear distance
     *    falloff, linear in mass and power.
     *  - **Critical damping**: `2·√(k·m/n)` where `k` is the spring stiffness (∂F/∂distance,
     *    also mass-aware), so the damping coefficient scales with mass and keeps both small
     *    and large ships settling cleanly without over-damping.
     *  - **Lateral friction**: `LATERAL_FRICTION_RATE × m/n × proximity × power/15`,
     *    against the target's velocity perpendicular to the lift axis. The 1/s rate means
     *    a constant *deceleration* per unit of lateral velocity regardless of ship size —
     *    a 1-tonne skiff and a 1-kilotonne galleon both lose perpendicular drift at the
     *    same rate per pad.
     */
    private fun applyMirroredHover(
        target: PhysShip,
        distance: Double,
        modelX: Double, modelY: Double, modelZ: Double,
        pushSign: Double,
        power: Int,
        padScale: Double,
    ) {
        val powerScale = power / 15.0
        val proximity = 1.0 - (distance / GROUND_MAX_RANGE)
        val effectiveMass = target.mass.coerceAtLeast(MIN_MASS_FOR_DAMP)
        val massShare = effectiveMass / ASSUMED_PAD_COUNT

        // Push direction in world frame.
        val pushX = cachedFacingWorldX * pushSign
        val pushY = cachedFacingWorldY * pushSign
        val pushZ = cachedFacingWorldZ * pushSign

        // Spring force — mass-relative. Per-pad force at the ground (proximity=1) is
        // HOVER_LIFT_FACTOR × m × g / n_pads, so n_pads of them together push the ship
        // upward with HOVER_LIFT_FACTOR × m × g of force at zero distance, settling at
        // proximity = 1 / (HOVER_LIFT_FACTOR × powerScale) where lift equals gravity.
        val springMag = springForcePerPad(effectiveMass, power) * proximity

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
        val velAlongPush = vpX * pushX + vpY * pushY + vpZ * pushZ

        // Critical damping on the lift axis. `k` (mass-aware) = peak spring force / range.
        val k = springForcePerPad(effectiveMass, power) / GROUND_MAX_RANGE
        val cCrit = 2.0 * Math.sqrt(k * massShare)
        val liftMag = (springMag - cCrit * velAlongPush).coerceAtLeast(0.0)

        // Lateral friction (everything perpendicular to push axis). Force per pad =
        // LATERAL_FRICTION_RATE × (mass/n) × v_perp × proximity × powerScale, giving a
        // constant deceleration rate per pad regardless of ship size.
        val vPerpX = vpX - pushX * velAlongPush
        val vPerpY = vpY - pushY * velAlongPush
        val vPerpZ = vpZ - pushZ * velAlongPush
        val frictionCoef = LATERAL_FRICTION_RATE * massShare * proximity * powerScale

        val rawFx = pushX * liftMag - vPerpX * frictionCoef
        val rawFy = pushY * liftMag - vPerpY * frictionCoef
        val rawFz = pushZ * liftMag - vPerpZ * frictionCoef
        // Block-sourced scale³ multiplier (VS2 drag is cross-section ∝ scale²;
        // cubic force cancels that to give scale-invariant ship-lengths-per-second).
        val s3 = padScale * padScale * padScale
        val totalFx = rawFx * s3
        val totalFy = rawFy * s3
        val totalFz = rawFz * s3

        target.applyWorldForceToModelPos(
            Vector3d(totalFx, totalFy, totalFz),
            Vector3d(modelX, modelY, modelZ),
        )
    }

    /** Result of a successful ship-in-beam trace. Public so [CreateCompat] can call
     *  [findClosestShipInBeam] from the contraption-tick path. */
    data class ShipHit(
        val shipId: Long,
        val distance: Double,
        val modelPos: Vector3d,                            // shipyard coords on the target
    )

    companion object {
        private val LOG = com.mojang.logging.LogUtils.getLogger()

        /** Reflectively-loaded Create `AbstractContraptionEntity` class, or null if
         *  Create isn't installed. Used by [pushEntitiesForward] to skip contraption
         *  entities from the beam — pushing them via `deltaMovement` desyncs the
         *  contraption's anchor from the visual blocks it carries. Loaded once at
         *  class-init time so the per-entity check in the hot loop is just a fast
         *  `isInstance`. */
        private val CONTRAPTION_ENTITY_CLASS: Class<*>? = run {
            // Modern (Create 0.5+/6.x) path first, with a legacy fallback so the same
            // build works against older Create if anyone backports.
            val candidates = listOf(
                "com.simibubi.create.content.contraptions.AbstractContraptionEntity",
                "com.simibubi.create.content.contraptions.components.structureMovement.AbstractContraptionEntity",
            )
            for (name in candidates) {
                try { return@run Class.forName(name) } catch (_: Throwable) {}
            }
            null
        }

        /** Mass-relative hover-lift factor. Per-pad peak force at the ground (proximity=1,
         *  POWER 15) is `HOVER_LIFT_FACTOR × m × g / ASSUMED_PAD_COUNT` newtons. With
         *  factor 2.5 and 4 assumed pads, the n_pads together produce 2.5·m·g at the
         *  ground — 2.5 g of net upward thrust over gravity — and the ship settles at
         *  proximity = 1 / (HOVER_LIFT_FACTOR · powerScale) where spring lift equals
         *  weight. At full power that's proximity ≈ 0.4 → ≈ 2.4 blocks of hover height
         *  above the ground, regardless of ship mass. */
        const val HOVER_LIFT_FACTOR: Double = 2.5

        /** Maximum ground-effect detection range, blocks. */
        const val GROUND_MAX_RANGE: Double = 4.0

        /** Lateral friction rate (1/s) — per-pad deceleration applied to perpendicular
         *  velocity at full power, full proximity, scaled by `mass / ASSUMED_PAD_COUNT`.
         *  4.0 → ~1 m/s² of decel per m/s of drift per pad on a typical hovercraft, so
         *  4 pads together provide ~4 m/s² of grip. Mass-aware so the feel is consistent
         *  across ship sizes. */
        const val LATERAL_FRICTION_RATE: Double = 4.0

        /** Standard gravity (m/s²) used for hover-force sizing. Matches VS2's world
         *  gravity so the spring math stays in physical units. */
        const val GRAVITY: Double = 10.0

        /** Per-pad spring force (N) at proximity = 1 on a target of [mass] kg at [power]
         *  (0–15). Total upward force from `ASSUMED_PAD_COUNT` pads at the ground equals
         *  `HOVER_LIFT_FACTOR × mass × GRAVITY × power/15`. Shared between the BE's
         *  `applyMirroredHover` and Create-compat's contraption staging path so both
         *  routes apply the same physics. */
        fun springForcePerPad(mass: Double, power: Int): Double {
            val effective = mass.coerceAtLeast(MIN_MASS_FOR_DAMP)
            val powerScale = power / 15.0
            return HOVER_LIFT_FACTOR * effective * GRAVITY * powerScale / ASSUMED_PAD_COUNT
        }

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

        /** Assumed pad count for per-pad mass-share. Both the spring sizing and the
         *  critical-damping formula divide the target's mass by this. Tuning constant —
         *  a rig with exactly [ASSUMED_PAD_COUNT] pads experiences textbook critical
         *  damping; more pads over-damp slightly, fewer under-damp. */
        const val ASSUMED_PAD_COUNT: Double = 4.0

        /** Floor on the target mass used in spring/damping sizing so a near-massless
         *  body (or one whose inertia hasn't initialised yet) doesn't compute zero
         *  forces. 1000 kg ≈ one slab of cobble. */
        const val MIN_MASS_FOR_DAMP: Double = 1000.0

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
                // Skip Create contraption entities — they have their own movement/rotation
                // logic and pushing them via deltaMovement causes desyncs/glitches between
                // the contraption's anchor entity and the visual blocks it carries.
                if (CONTRAPTION_ENTITY_CLASS?.isInstance(entity) == true) continue
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
                    ClipContext(
                        rayStart, rayEnd,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                        null as Entity?,
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
            val ctx = ClipContext(
                from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY,
                null as Entity?,
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

        /**
         * Find the closest VS2 ship whose world AABB the beam enters within
         * [GROUND_MAX_RANGE], or null if none. Uses the slab method for ray-vs-AABB —
         * a clean intersection point, no false hits from inflated-box overlap. The hit's
         * shipyard position (`worldToShip` of the world entry point) is what physTick
         * applies the push at, so off-COM application produces torque on the target.
         */
        fun findClosestShipInBeam(
            level: ServerLevel,
            padWorld: Vector3d,
            facingWorld: Vector3d,
        ): ShipHit? {
            var bestT = GROUND_MAX_RANGE
            var bestShipId = -1L
            var bestWorldX = 0.0; var bestWorldY = 0.0; var bestWorldZ = 0.0
            var bestShipRef: org.valkyrienskies.core.api.ships.LoadedServerShip? = null

            for (s in level.shipObjectWorld.allShips) {
                val sa = s.worldAABB
                // Frame-of-reference guard: skip any ship whose worldAABB *contains*
                // the pad's own world position. The pad is on that ship; pushing it
                // would be the pad reacting against its own frame — an internal force
                // that physically does nothing useful and in practice produces
                // self-resonating oscillation as both the pad and the ship try to
                // move in opposite directions. For a correctly-classified world-placed
                // pad no ship contains its position and this is a no-op; for an
                // edge-case where the world-pad branch ran on a ship-mounted pad
                // (race between chunk load and `getLoadedShipManagingPos`), this is
                // the defensive cutout that prevents the bug.
                if (padWorld.x in sa.minX()..sa.maxX() &&
                    padWorld.y in sa.minY()..sa.maxY() &&
                    padWorld.z in sa.minZ()..sa.maxZ()) continue

                val t = raySlabIntersect(
                    padWorld.x, padWorld.y, padWorld.z,
                    facingWorld.x, facingWorld.y, facingWorld.z,
                    sa.minX(), sa.minY(), sa.minZ(),
                    sa.maxX(), sa.maxY(), sa.maxZ(),
                )
                if (t.isNaN() || t > bestT) continue
                bestT = t
                bestShipId = s.id
                bestWorldX = padWorld.x + facingWorld.x * t
                bestWorldY = padWorld.y + facingWorld.y * t
                bestWorldZ = padWorld.z + facingWorld.z * t
                bestShipRef = s as? org.valkyrienskies.core.api.ships.LoadedServerShip
            }
            if (bestShipId < 0L || bestShipRef == null) return null

            // Project world hit → shipyard position on the target. physTick uses model
            // pos for off-COM application, which is what makes asymmetric ship layouts
            // (target's COM off the pad's axis) naturally tilt under the push.
            val modelPos = Vector3d(bestWorldX, bestWorldY, bestWorldZ)
            bestShipRef.transform.worldToShip.transformPosition(modelPos)
            return ShipHit(bestShipId, bestT, modelPos)
        }

        /** Slab-method ray-vs-AABB intersection. Returns the entry-t along the ray
         *  (origin + t·dir = hit point), or NaN if no intersection within `t ≥ 0`.
         *  Origin inside the box returns t=0. */
        private fun raySlabIntersect(
            ox: Double, oy: Double, oz: Double,
            dx: Double, dy: Double, dz: Double,
            minX: Double, minY: Double, minZ: Double,
            maxX: Double, maxY: Double, maxZ: Double,
        ): Double {
            var tmin = Double.NEGATIVE_INFINITY
            var tmax = Double.POSITIVE_INFINITY
            // X slab
            if (Math.abs(dx) < 1e-9) {
                if (ox < minX || ox > maxX) return Double.NaN
            } else {
                val t1 = (minX - ox) / dx
                val t2 = (maxX - ox) / dx
                tmin = Math.max(tmin, Math.min(t1, t2))
                tmax = Math.min(tmax, Math.max(t1, t2))
                if (tmin > tmax) return Double.NaN
            }
            // Y slab
            if (Math.abs(dy) < 1e-9) {
                if (oy < minY || oy > maxY) return Double.NaN
            } else {
                val t1 = (minY - oy) / dy
                val t2 = (maxY - oy) / dy
                tmin = Math.max(tmin, Math.min(t1, t2))
                tmax = Math.min(tmax, Math.max(t1, t2))
                if (tmin > tmax) return Double.NaN
            }
            // Z slab
            if (Math.abs(dz) < 1e-9) {
                if (oz < minZ || oz > maxZ) return Double.NaN
            } else {
                val t1 = (minZ - oz) / dz
                val t2 = (maxZ - oz) / dz
                tmin = Math.max(tmin, Math.min(t1, t2))
                tmax = Math.min(tmax, Math.max(t1, t2))
                if (tmin > tmax) return Double.NaN
            }
            return if (tmin >= 0) tmin else if (tmax >= 0) 0.0 else Double.NaN
        }

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
