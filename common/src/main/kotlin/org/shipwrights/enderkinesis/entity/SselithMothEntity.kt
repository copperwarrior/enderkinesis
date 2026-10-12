package org.shipwrights.enderkinesis.entity

import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.FlyingMoveControl
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKParticles
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **Sselith Moth** — small flocking mob that drifts through the extreme
 * high and low reaches of Sselith. Boid-style movement (separation +
 * alignment + cohesion + goal steering) with a dust-mote trail. No
 * pathfinding, no hostility, low tick cost — a group of 3–5 exists
 * mostly to look alive in the ceiling / floor voids of the Repertory.
 *
 * Boid rules per server tick:
 *   1. Sample nearby moths within [BOID_RADIUS].
 *   2. **Separation**: steer away from any moth closer than
 *      [SEPARATION_RADIUS], weighted 1/dist.
 *   3. **Alignment**: nudge velocity toward the neighbourhood's average
 *      velocity, scaled by [ALIGNMENT_WEIGHT].
 *   4. **Cohesion**: nudge position toward the neighbourhood's centroid,
 *      scaled by [COHESION_WEIGHT].
 *   5. **Goal**: gentle steer toward the desired [targetY] band (high or
 *      low corner of Sselith), so the flock as a whole stays in its
 *      preferred altitude.
 *   6. Clamp to [MAX_SPEED]; write via [setDeltaMovement]; let vanilla
 *      `travel()` apply the delta.
 */
class SselithMothEntity(type: EntityType<out SselithMothEntity>, level: Level) :
    Mob(type, level) {

    /** Persistent horizontal migration direction (radians). Every moth in a
     *  flock is spawned with the SAME [driftYaw] so the group actually
     *  travels together across the world; alignment steering plus this
     *  shared drift produces a coherent one-way migration rather than the
     *  loiter-around-spawn behaviour of pure boid rules. NBT-saved so a
     *  reload doesn't scramble each flock's heading. */
    private var driftYaw: Float = Float.NaN

    /** Per-moth vertical offset from the shared glide altitude (±0.2) so
     *  a flock doesn't compress into one perfectly flat sheet. Derived
     *  from the UUID — stable across saves without NBT. */
    private val glideYOffset: Double by lazy {
        (uuid.leastSignificantBits and 0xFFFF).toDouble() / 65535.0 * 0.4 - 0.2
    }

    /** Per-moth phases for [bobOffset], from the UUID so a flock doesn't bob in step. */
    private val bobPhases: DoubleArray by lazy {
        val bits = uuid.mostSignificantBits
        DoubleArray(2) { ((bits ushr (it * 16)) and 0xFFFF).toDouble() / 65535.0 * 2.0 * PI }
    }

    /** Vertical velocity the bob added last tick; removed again before the boid step reads velocity. */
    private var appliedBobVy = 0.0

    private var boidNeighbours: List<SselithMothEntity> = emptyList()

    init {
        setNoGravity(true)
        moveControl = FlyingMoveControl(this, 20, true)
        noPhysics = false
    }

    /** Mob's default navigation is ground-walking — the landing approach
     *  paths through the air, so it needs the flying evaluator or moths
     *  pin themselves against ledges between them and the anchor. */
    override fun createNavigation(level: Level): net.minecraft.world.entity.ai.navigation.PathNavigation =
        net.minecraft.world.entity.ai.navigation.FlyingPathNavigation(this, level).apply {
            setCanOpenDoors(false)
            setCanPassDoors(true)
        }

    // ------------------------------------------------------------ landing

    /** Server-side landing state. The moth occasionally picks a nearby
     *  block with an air-exposed sturdy face, flies to it, clings to that
     *  face for a while, then takes off again. */
    private var landAnchor: Vec3? = null
    private var pendingFace: Direction? = null
    private var pendingPos: BlockPos? = null
    private var pendingNormal: Vec3? = null
    private var pendingYaw: Float = 0f
    private var approachTicks = 0
    private var landedTicks = 0

    /** Client-side smoothed fly↔idle blend, lerped for partial ticks. */
    private var landedBlendPrev = 0f
    private var landedBlendCur = 0f

    /** Client-side cache of the perch pose, kept alive through the
     *  takeoff fade: the synced face flips to −1 instantly at takeoff,
     *  but the renderer and camo layer need the old pose while the blend
     *  eases back to zero instead of hard-cutting. */
    private var fadeFace: Direction? = null
    private var fadeNormal: Vec3? = null
    private var fadePerchBlock: BlockPos? = null

    /** Perch face/normal/block for RENDERING — live while landed, the
     *  cached values while the takeoff fade plays out, null once faded. */
    fun perchRenderFace(): Direction? =
        landedFace() ?: if (landedBlendCur > 0.01f) fadeFace else null

    fun perchRenderNormal(): Vec3? =
        if (isLanded()) landedNormal() else if (landedBlendCur > 0.01f) fadeNormal else null

    fun perchRenderBlock(): BlockPos? =
        if (landedBlendCur > 0.01f || isLanded()) fadePerchBlock else null

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_LANDED_FACE, -1)
        entityData.define(DATA_LAND_NX, 0f)
        entityData.define(DATA_LAND_NY, 1f)
        entityData.define(DATA_LAND_NZ, 0f)
    }

    /** The face this moth is clinging to, or null while flying. -1 in the
     *  synced byte means airborne; 0–5 is [Direction.get3DDataValue]. */
    fun landedFace(): Direction? {
        val raw = entityData.get(DATA_LANDED_FACE).toInt()
        return if (raw < 0) null else Direction.from3DDataValue(raw)
    }

    fun isLanded(): Boolean = landedFace() != null

    /** Smoothed away-from-surface normal at the perch point, sampled from
     *  the terrain SDF at touchdown — the renderer aligns the moth's belly
     *  to THIS, not to the axis-aligned block face, so perches on stairs /
     *  slabs / rough crowns follow the actual local surface. */
    fun landedNormal(): Vec3? {
        if (!isLanded()) return null
        return Vec3(
            entityData.get(DATA_LAND_NX).toDouble(),
            entityData.get(DATA_LAND_NY).toDouble(),
            entityData.get(DATA_LAND_NZ).toDouble(),
        )
    }

    /** Smoothed 0 (flying) → 1 (landed) used by the model to cross-fade
     *  the FLY and IDLE animations. Clamped: the camo bake pass calls
     *  setupAnim with a synthetic age whose derived partial is far outside
     *  [0, 1], and an extrapolated lerp would fly off. */
    fun landedBlend(partialTick: Float): Float =
        Mth.lerp(partialTick, landedBlendPrev, landedBlendCur).coerceIn(0f, 1f)

    /** Perching flush against walls/ceilings puts the hitbox (and on
     *  ceilings the eye point) fractionally inside the surface block —
     *  vanilla's suffocation probe would kill every clinging moth. Moths
     *  never suffocate. */
    override fun isInWall(): Boolean = false

    /** Right-click to ride — passenger only, never controllable: Mob's
     *  default null controlling passenger means the boid keeps flying its
     *  own course with the rider along for the drift. Landed moths refuse
     *  (and landing throws riders off — see the touchdown). */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        val result = super.mobInteract(player, hand)
        if (result.consumesAction()) return result
        if (isLanded()) return InteractionResult.PASS
        if (!level().isClientSide) {
            return if (player.startRiding(this)) InteractionResult.CONSUME else InteractionResult.PASS
        }
        return InteractionResult.SUCCESS
    }

    /** Seats the rider down on the body's back (net of the player's own
     *  −0.35 riding offset) rather than hovering above it. */
    override fun getPassengersRidingOffset(): Double = 0.1

    /** Wings reach ~3.7 blocks laterally and ~4.9 aft — the render culling
     *  box must cover them or the moth pops out at screen edges. The
     *  COLLISION box stays body-sized (see EKEntities): a wingspan hitbox
     *  suffocates in terrain and fights the flocking. */
    override fun getBoundingBoxForCulling(): AABB =
        boundingBox.inflate(WING_CULL_INFLATE_XZ, WING_CULL_INFLATE_Y, WING_CULL_INFLATE_XZ)

    override fun registerGoals() { /* pure custom AI in [tick] */ }

    override fun getStandingEyeHeight(pose: net.minecraft.world.entity.Pose, dims: net.minecraft.world.entity.EntityDimensions): Float =
        dims.height * 0.5f

    /** Vanilla's `Entity.baseTick` calls `outOfWorld()` when
     *  `y < minBuildHeight − 64`. On [LivingEntity] that hurts the mob for
     *  4 hp per tick with `FELL_OUT_OF_WORLD` damage. Moths patrol Y = −134.5
     *  below Sselith's minBuildHeight = −128 by design, so override to a
     *  no-op — nothing about the moth's behaviour cares about world Y bounds. */
    override fun onBelowWorld() {}

    /** Ignore vertical distance: the flock lives well above/below Sselith's
     *  block volume, so the default 128-block 3-D despawn radius would
     *  cull it every tick even when a player is standing directly under
     *  the flock's XZ column. Despawn only when no player is within
     *  [DESPAWN_XZ] in the horizontal plane. */
    override fun checkDespawn() {
        if (level().isClientSide) return
        var anyPlayer = false
        for (player in level().players()) {
            if (player.isSpectator) continue
            anyPlayer = true
            val dx = player.x - x
            val dz = player.z - z
            if (dx * dx + dz * dz <= DESPAWN_XZ * DESPAWN_XZ) return
        }
        if (anyPlayer) discard()
    }

    override fun tick() {
        if (!level().isClientSide) {
            if (driftYaw.isNaN()) driftYaw = random.nextFloat() * (2f * PI.toFloat())
            // Stuck detection: cruise and the approach glide are blind
            // velocity pushes, so a moth wedged in a block gap grinds
            // against it indefinitely. A sustained collision streak
            // abandons whatever it was doing and triggers an escape.
            if (!isLanded()) {
                stuckTicks = if (horizontalCollision || verticalCollision) stuckTicks + 1 else 0
                if (escapeTicks <= 0 && stuckTicks > STUCK_TICKS_THRESHOLD) {
                    startEscape()
                }
            }
            // Guaranteed sneak-dismount: vanilla's Player.rideTick path
            // should handle this, but it has proven unreliable for the
            // moth in practice — enforce it here so riders can always
            // get off.
            if (isVehicle()) {
                for (rider in passengers.toList()) {
                    if (rider is Player && rider.isShiftKeyDown) rider.stopRiding()
                }
            }
            if (isLanded() || escapeTicks > 0 || landAnchor != null) appliedBobVy = 0.0
            when {
                isLanded() -> tickLanded()
                escapeTicks > 0 -> tickEscape()
                landAnchor != null -> tickApproach()
                else -> {
                    runBoidStep()
                    maybeStartLanding()
                }
            }
        }
        super.tick()
        if (level().isClientSide) {
            landedBlendPrev = landedBlendCur
            val target = if (isLanded()) 1f else 0f
            landedBlendCur += (target - landedBlendCur) * LANDED_BLEND_RATE
            val face = landedFace()
            if (face != null) {
                fadeFace = face
                fadeNormal = landedNormal()
                fadePerchBlock = BlockPos.containing(
                    x - face.stepX, y - face.stepY, z - face.stepZ,
                )
            }
            if (!isLanded()) spawnDustTrail()
        }
    }

    /** Roll a landing attempt: sample random blocks in the terrain band
     *  that matches this moth's altitude (high flock → the Y 115–125 crown
     *  of the Repertory, low flock → the Y −129…−126 floor), keep the first
     *  with a sturdy face exposed to air, and start the approach toward a
     *  point just off that face. */
    private fun maybeStartLanding() {
        if (random.nextInt(LAND_ATTEMPT_INTERVAL) != 0) return
        val bandMin = if (y >= 0.0) LAND_BAND_HIGH_MIN else LAND_BAND_LOW_MIN
        val bandMax = if (y >= 0.0) LAND_BAND_HIGH_MAX else LAND_BAND_LOW_MAX
        repeat(LAND_SAMPLE_COUNT) {
            val pos = BlockPos.containing(
                x + (random.nextDouble() - 0.5) * 2.0 * LAND_SEARCH_RADIUS,
                (bandMin + random.nextInt(bandMax - bandMin + 1)).toDouble(),
                z + (random.nextDouble() - 0.5) * 2.0 * LAND_SEARCH_RADIUS,
            )
            val state = level().getBlockState(pos)
            if (state.isAir) return@repeat
            // Random face first, then the rest — keeps the pick unbiased
            // while still accepting a block with only one open face.
            val start = random.nextInt(6)
            for (i in 0 until 6) {
                val face = Direction.from3DDataValue((start + i) % 6)
                if (!state.isFaceSturdy(level(), pos, face)) continue
                if (!level().getBlockState(pos.relative(face)).isAir) continue
                val anchor = Vec3.atCenterOf(pos).add(
                    face.stepX * (0.5 + LAND_SURFACE_EPS),
                    face.stepY * (0.5 + LAND_SURFACE_EPS),
                    face.stepZ * (0.5 + LAND_SURFACE_EPS),
                )
                // Decide the COMPLETE final pose here — SDF-conforming
                // normal, yaw, dip clearance — so the wing-footprint test
                // runs in the actual rotated wing plane, not an idealised
                // face-aligned disc.
                val faceNormal = Vec3(
                    face.stepX.toDouble(), face.stepY.toDouble(), face.stepZ.toDouble(),
                )
                var normal = faceNormal
                // Sample from a raised standoff: Repertory perches are
                // mostly crag tops with walls falling away below — at the
                // face-adjacent point those walls barely register, but a
                // block higher they contribute and the crest's curvature
                // shows up in the gradient.
                val sampleCentre = anchor.add(faceNormal.scale(LAND_SDF_EXTRA_STANDOFF))
                val sdf = ConstriliarSurfaceField.sample(
                    level(), this, sampleCentre, LAND_SDF_RANGE, null, LAND_SDF_SMOOTHING, LAND_SDF_MAX_BOXES,
                )
                if (sdf != null) {
                    normal = Vec3(sdf.up.x, sdf.up.y, sdf.up.z)
                    val cosAngle = normal.dot(faceNormal).coerceIn(-1.0, 1.0)
                    val angle = kotlin.math.acos(cosAngle)
                    // Amplify the deviation so the conforming lean READS,
                    // then clamp. Gain > 1 extrapolates slightly past the
                    // sampled gradient via nlerp — fine at these angles.
                    val effective = (angle * LAND_TILT_GAIN).coerceAtMost(LAND_MAX_TILT_RAD)
                    if (angle > 1.0e-4) {
                        val t = effective / angle
                        normal = faceNormal.scale(1.0 - t).add(normal.scale(t)).normalize()
                    }
                }
                // Walls: head into the surface (renderer tilts it up the
                // slope). Floors/ceilings: pick the perch heading now so
                // the footprint test and the final pose agree.
                val yawDeg = if (kotlin.math.abs(normal.y) < 0.7) {
                    Math.toDegrees(atan2(normal.x, -normal.z)).toFloat()
                } else {
                    random.nextFloat() * 360f
                }
                // The tilt CONFORMS to the slope, so the terrain falls away
                // under the dip-side wing — full-span clearance would float
                // the moth ~2 blocks up. Only the near-body region needs
                // standoff against the face it's actually touching.
                val tiltFromFace = kotlin.math.acos(normal.dot(faceNormal).coerceIn(-1.0, 1.0))
                val clearance = LAND_SURFACE_EPS +
                    kotlin.math.sin(tiltFromFace) * LAND_TILT_CLEARANCE
                val perch = anchor.add(faceNormal.scale(clearance))
                if (!wingFootprintClear(perch, normal, yawDeg)) continue
                pendingPos = pos
                pendingFace = face
                pendingNormal = normal
                pendingYaw = yawDeg
                landAnchor = perch
                approachTicks = 0
                return
            }
        }
    }

    /** True when the moth's WING FOOTPRINT — the model's actual planform
     *  (±3.5 lateral, +1.5 fore, −4.5 aft), placed at [perch], tilted so
     *  model-up is [normal], and rotated to [yawDeg] — is free of blocks.
     *  Probes each grid point in the wing plane plus one layer outward
     *  along the normal (flap headroom). */
    private fun wingFootprintClear(perch: Vec3, normal: Vec3, yawDeg: Float): Boolean {
        val yawRad = Math.toRadians(yawDeg.toDouble())
        val fYaw = Vec3(-sin(yawRad), 0.0, cos(yawRad))
        // Project the heading into the tilted wing plane; degenerate when
        // the normal is (anti)parallel to the heading — fall back to any
        // tangent.
        var fwd = fYaw.subtract(normal.scale(fYaw.dot(normal)))
        fwd = if (fwd.lengthSqr() < 1.0e-6) {
            Vec3(1.0, 0.0, 0.0).subtract(normal.scale(normal.x)).normalize()
        } else {
            fwd.normalize()
        }
        val right = fwd.cross(normal).normalize()
        for (lat in WING_CLEAR_LAT) {
            for (lon in WING_CLEAR_LON) {
                // Probe slightly ABOVE the wing plane: a conforming perch
                // legitimately grazes the slope with its dip-side wing, and
                // in-plane probes would veto exactly the crest sites the
                // moth should favour. Real obstructions still register.
                val p = perch
                    .add(right.scale(lat))
                    .add(fwd.scale(lon))
                    .add(normal.scale(WING_CLEAR_PROBE_LIFT))
                if (!level().getBlockState(BlockPos.containing(p.x, p.y, p.z)).isAir) return false
                val above = p.add(normal)
                if (!level().getBlockState(BlockPos.containing(above.x, above.y, above.z)).isAir) return false
            }
        }
        return true
    }

    private fun tickApproach() {
        val anchor = landAnchor ?: return
        val face = pendingFace ?: return
        val pos = pendingPos ?: return
        // Target vanished (block broken / replaced) or we've dawdled too
        // long — give up and resume flocking.
        if (++approachTicks > LAND_APPROACH_TIMEOUT ||
            !level().getBlockState(pos).isFaceSturdy(level(), pos, face)
        ) {
            navigation.stop()
            clearLandingTarget()
            return
        }
        val to = anchor.subtract(position())
        val dist = to.length()
        if (dist < LAND_SNAP_DIST) {
            // Touch down: the complete pose (SDF-conforming normal, yaw,
            // dip-cleared perch) was decided at SITE SELECTION so the
            // wing-footprint clearance test ran against the real rotated
            // wing plane — just apply it.
            navigation.stop()
            // A perched moth carries nobody — riders tumble off at the
            // moment of touchdown.
            ejectPassengers()
            val normal = pendingNormal ?: Vec3(
                face.stepX.toDouble(), face.stepY.toDouble(), face.stepZ.toDouble(),
            )
            setPos(anchor.x, anchor.y, anchor.z)
            setDeltaMovement(0.0, 0.0, 0.0)
            yRot = pendingYaw
            yBodyRot = pendingYaw
            xRot = 0f
            entityData.set(DATA_LANDED_FACE, face.get3DDataValue().toByte())
            entityData.set(DATA_LAND_NX, normal.x.toFloat())
            entityData.set(DATA_LAND_NY, normal.y.toFloat())
            entityData.set(DATA_LAND_NZ, normal.z.toFloat())
            landedTicks = LAND_STAY_MIN + random.nextInt(LAND_STAY_VAR)
            return
        }
        if (dist < LAND_DIRECT_DIST) {
            // Final leg: the anchor hugs a block face, which pathfinding's
            // node granularity refuses to enter — glide straight in (the
            // air here was validated by the footprint test).
            navigation.stop()
            val vel = to.scale(LAND_APPROACH_SPEED / dist)
            setDeltaMovement(vel)
            val horiz = sqrt(vel.x * vel.x + vel.z * vel.z)
            if (horiz > 1.0e-3) {
                yRot = Math.toDegrees(atan2(-vel.x, vel.z)).toFloat()
                yBodyRot = yRot
                xRot = (-Math.toDegrees(atan2(vel.y, horiz))).toFloat()
            }
            return
        }
        // Long leg: fly-pathfind around ledges instead of pushing into
        // them. Repath periodically (and whenever the nav gives up) so a
        // failed path retries rather than stalling out the approach.
        if (navigation.isDone || approachTicks % LAND_REPATH_INTERVAL == 0) {
            navigation.moveTo(anchor.x, anchor.y, anchor.z, LAND_NAV_SPEED)
        }
    }

    private fun tickLanded() {
        setDeltaMovement(0.0, 0.0, 0.0)
        val face = landedFace() ?: return
        val pos = pendingPos
        val supportGone = pos == null ||
            !level().getBlockState(pos).isFaceSturdy(level(), pos, face)
        val disturbed = level().getNearestPlayer(x, y, z, LAND_DISTURB_RADIUS, false) != null
        if (--landedTicks <= 0 || supportGone || disturbed) {
            takeOff(face)
            return
        }
        // Hold the perch — collision resolution can nudge the entity a
        // hair per tick, which would slowly slide it off the face.
        landAnchor?.let { if (position().distanceToSqr(it) > 0.01) setPos(it.x, it.y, it.z) }
    }

    private fun takeOff(face: Direction) {
        // Push off along the SDF perch normal (falls back to the block
        // face), plus a touch of lift so floor launches visibly depart.
        val n = landedNormal() ?: Vec3(
            face.stepX.toDouble(), face.stepY.toDouble(), face.stepZ.toDouble(),
        )
        entityData.set(DATA_LANDED_FACE, -1)
        clearLandingTarget()
        setDeltaMovement(
            n.x * TAKEOFF_SPEED,
            n.y * TAKEOFF_SPEED + 0.02,
            n.z * TAKEOFF_SPEED,
        )
    }

    private fun clearLandingTarget() {
        navigation.stop()
        landAnchor = null
        pendingFace = null
        pendingPos = null
        pendingNormal = null
        approachTicks = 0
    }

    // ------------------------------------------------------------- escape

    private var stuckTicks = 0
    private var escapeTicks = 0
    private var escapeDir: Vec3 = Vec3.ZERO

    /** Wedged in geometry: drop any landing plan and fly out along the
     *  terrain SDF gradient — it points away from the surrounding surface
     *  union, i.e. out of whatever pocket the moth is stuck in. Falls back
     *  to "toward my cruise altitude" when no geometry is in range. */
    private fun startEscape() {
        clearLandingTarget()
        val sdf = ConstriliarSurfaceField.sample(level(), this, position(), ESCAPE_SDF_RANGE, null)
        escapeDir = if (sdf != null && sdf.up.lengthSquared() > 1.0e-6) {
            Vec3(sdf.up.x, sdf.up.y, sdf.up.z)
        } else {
            Vec3(0.0, if (y >= 0.0) 1.0 else -1.0, 0.0)
        }
        escapeTicks = ESCAPE_TICKS
        stuckTicks = 0
    }

    private fun tickEscape() {
        escapeTicks--
        setDeltaMovement(escapeDir.scale(ESCAPE_SPEED))
        val horiz = sqrt(escapeDir.x * escapeDir.x + escapeDir.z * escapeDir.z)
        if (horiz > 1.0e-3) {
            yRot = Math.toDegrees(atan2(-escapeDir.x, escapeDir.z)).toFloat()
            yBodyRot = yRot
        }
    }

    private fun runBoidStep() {
        // Staggered by id so a flock's queries spread across ticks.
        if ((tickCount + id) % BOID_QUERY_INTERVAL == 0) {
            boidNeighbours = level().getEntitiesOfClass(
                SselithMothEntity::class.java,
                AABB.ofSize(position(), BOID_RADIUS * 2, BOID_RADIUS * 2, BOID_RADIUS * 2),
            ) { it !== this }
        }
        val neighbours = boidNeighbours

        var sepX = 0.0; var sepY = 0.0; var sepZ = 0.0
        var alignX = 0.0; var alignY = 0.0; var alignZ = 0.0
        // Sum of neighbours' driftYaw as unit vectors, for a proper
        // circular-average blend so two flocks that meet up start
        // converging on a shared heading instead of talking past each
        // other with their persistent drifts.
        var yawSumX = 0.0; var yawSumZ = 0.0
        var alignCount = 0

        for (other in neighbours) {
            if (other.isRemoved) continue
            val dx = x - other.x
            val dy = y - other.y
            val dz = z - other.z
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq < SEPARATION_RADIUS * SEPARATION_RADIUS && distSq > 1.0e-6) {
                val invDist = 1.0 / sqrt(distSq)
                sepX += dx * invDist * invDist
                sepY += dy * invDist * invDist
                sepZ += dz * invDist * invDist
            }
            val ov = other.deltaMovement
            alignX += ov.x; alignY += ov.y; alignZ += ov.z
            if (!other.driftYaw.isNaN()) {
                val oy = other.driftYaw.toDouble()
                yawSumX += cos(oy); yawSumZ += sin(oy)
            }
            alignCount++
        }

        if (alignCount > 0) {
            val inv = 1.0 / alignCount
            alignX *= inv; alignY *= inv; alignZ *= inv
            // Blend this moth's persistent drift toward the flock's mean
            // drift so merged clusters converge on a shared heading. Rate
            // is deliberately slow — brief drive-by encounters shouldn't
            // scramble a flock's direction, but a sustained merger does.
            val avgYaw = atan2(yawSumZ, yawSumX).toFloat()
            var delta = avgYaw - driftYaw
            while (delta > PI.toFloat()) delta -= (2f * PI.toFloat())
            while (delta < -PI.toFloat()) delta += (2f * PI.toFloat())
            driftYaw += delta * DRIFT_MERGE_RATE
        }

        // Y goal: fly toward the nearest of ±MOTH_ALTITUDE. Once at that
        // altitude the term drops to zero and the moth continues drifting
        // horizontally along [driftYaw]. Clamped so a moth 400 blocks away
        // doesn't have its entire heading dominated by the vertical steer.
        // The bob moves the goal too, so the altitude steer never pulls against it.
        val time = level().gameTime.toDouble()
        val bob = bobOffset(time)
        val bobVy = bobOffset(time + 1.0) - bob
        val goalYAbs = (if (y >= 0.0) MOTH_ALTITUDE else -MOTH_ALTITUDE) + glideYOffset + bob
        val goalYRaw = (goalYAbs - y) * GOAL_WEIGHT_Y
        val goalY = goalYRaw.coerceIn(-MAX_GOAL_STEER, MAX_GOAL_STEER)

        // Persistent horizontal drift — the flock's shared migration
        // direction, seeded at spawn and NBT-persisted. This is what makes
        // a group actually TRAVEL rather than loiter.
        val yawD = driftYaw.toDouble()
        val driftX = cos(yawD) * DRIFT_WEIGHT
        val driftZ = sin(yawD) * DRIFT_WEIGHT

        val vel = deltaMovement
        val velY = vel.y - appliedBobVy
        var dx = vel.x + sepX * SEPARATION_WEIGHT + alignX * ALIGNMENT_WEIGHT + driftX
        var dy = velY + sepY * SEPARATION_WEIGHT + alignY * ALIGNMENT_WEIGHT + goalY
        var dz = vel.z + sepZ * SEPARATION_WEIGHT + alignZ * ALIGNMENT_WEIGHT + driftZ

        val mag = sqrt(dx * dx + dy * dy + dz * dz)
        if (mag > 1.0e-6) {
            val s = MAX_SPEED / mag
            dx *= s; dy *= s; dz *= s
        } else {
            dx = 0.0; dy = 0.0; dz = 0.0
        }
        setDeltaMovement(dx, dy + bobVy, dz)
        appliedBobVy = bobVy

        // Face the direction of travel. Vanilla convention: yaw = 0 → +Z
        // (south), yaw = -90 → +X (east). `atan2(-vx, vz)` gives exactly
        // that mapping — the stray `-90` I had before was rotating every
        // moth sideways so they flew perpendicular to their heading.
        // Pitch = −atan2(vy, horiz) so the body tilts up while climbing
        // toward +MOTH_ALTITUDE and down while descending to −MOTH_ALTITUDE.
        val horiz = sqrt(dx * dx + dz * dz)
        if (horiz > 1.0e-3) {
            yRot = Math.toDegrees(atan2(-dx, dz)).toFloat()
            yBodyRot = yRot
            xRot = (-Math.toDegrees(atan2(dy, horiz))).toFloat()
        }
    }

    /** Slow vertical drift in blocks; the weights sum to 1, so |bob| ≤ [BOB_AMPLITUDE]. */
    private fun bobOffset(time: Double): Double =
        BOB_AMPLITUDE * (
            BOB_WEIGHT_SLOW * sin(time * (2.0 * PI / BOB_PERIOD_SLOW) + bobPhases[0]) +
                (1.0 - BOB_WEIGHT_SLOW) * sin(time * (2.0 * PI / BOB_PERIOD_FAST) + bobPhases[1])
            )

    /** Client-side dust from the wings. Each spawn picks a random wing
     *  SECTION (upper / lower / trailing tip) per side and a random point
     *  across that section's planform, so over time the dust sheds from
     *  the whole wing surface instead of a fixed tip point. Section
     *  extents approximate the [SselithMothModel] wing boxes in blocks. */
    private fun spawnDustTrail() {
        if ((tickCount and 1) != 0) return
        val yawRad = Math.toRadians(yRot.toDouble())
        val rx = Math.cos(yawRad); val rz = Math.sin(yawRad)   // right
        val fx = -Math.sin(yawRad); val fz = Math.cos(yawRad)  // forward
        val wingY = y + bbHeight * 0.6
        for (side in intArrayOf(-1, 1)) {
            val s = random.nextInt(3) * 4
            val lat = (WING_SECTIONS[s] +
                random.nextDouble() * (WING_SECTIONS[s + 1] - WING_SECTIONS[s])) * side
            val lon = WING_SECTIONS[s + 2] +
                random.nextDouble() * (WING_SECTIONS[s + 3] - WING_SECTIONS[s + 2])
            level().addParticle(
                EKParticles.sselithDust(),
                x + rx * lat + fx * lon,
                wingY + (random.nextDouble() - 0.5) * 0.15,
                z + rz * lat + fz * lon,
                0.0, 0.0, 0.0,
            )
        }
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        if (!driftYaw.isNaN()) tag.putFloat("DriftYaw", driftYaw)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        if (tag.contains("DriftYaw")) driftYaw = tag.getFloat("DriftYaw")
    }

    /** Called by the chunk-gen spawner to pin every moth in a flock to the
     *  same horizontal heading, so they migrate together. */
    fun setDriftYaw(yaw: Float) { driftYaw = yaw }

    companion object {
        const val ID_PATH = "sselith_moth"
        val ID: ResourceLocation = EnderkinesisMod.id(ID_PATH)

        fun createAttributes(): AttributeSupplier.Builder = Mob
            .createMobAttributes()
            .add(Attributes.MAX_HEALTH, 4.0)
            .add(Attributes.MOVEMENT_SPEED, 0.3)
            .add(Attributes.FLYING_SPEED, 0.3)
            // Follow range bounds pathfinding reach — must cover the
            // landing approach (search radius + altitude commute).
            .add(Attributes.FOLLOW_RANGE, 48.0)

        // --- Boid tuning constants ---
        /** Neighbourhood radius for alignment + separation queries. Larger
         *  than the separation radius so alignment can reach the whole
         *  spread-out flock. */
        private const val BOID_RADIUS: Double = 10.0
        /** Distance under which separation kicks in. Wide so moths keep
         *  visible spacing during migration. */
        private const val SEPARATION_RADIUS: Double = 4.0
        /** Strong separation so the flock reads as spaced-out individuals,
         *  not a knot. */
        private const val SEPARATION_WEIGHT: Double = 0.10
        /** Moderate alignment — locks the flock's heading together as the
         *  shared [driftYaw] carries them across the map. */
        private const val ALIGNMENT_WEIGHT: Double = 0.05
        /** Constant flight speed (blocks / tick). Since moths always move
         *  in their heading direction at exactly this speed, this is both
         *  the min AND max — no damping needed. ~2.4 b/s at 20 tps. */
        private const val MAX_SPEED: Double = 0.12

        /** Ticks between boid neighbour queries; positions are still read every tick. */
        private const val BOID_QUERY_INTERVAL = 4

        /** Upper bound on the vertical drift during flight, in blocks either side of the path. */
        private const val BOB_AMPLITUDE: Double = 0.5
        private const val BOB_PERIOD_SLOW: Double = 460.0
        private const val BOB_PERIOD_FAST: Double = 180.0
        private const val BOB_WEIGHT_SLOW: Double = 0.6

        /** Absolute altitude every moth converges to (Y = ±this). Must
         *  match [SselithRepertoryChunkGenerator.MOTH_ALTITUDE]. */
        private const val MOTH_ALTITUDE: Double = 134.5

        /** XZ-only despawn radius. Vertical distance is ignored — flocks
         *  live at Y = ±MOTH_ALTITUDE around Sselith's block volume, so 3-D
         *  distance would always cull them. */
        private const val DESPAWN_XZ: Double = 96.0
        /** Y-goal gain per (goalY − y). At goalY = 134.5, y = 0, the raw
         *  vertical steer is 134.5 × this = 0.4 — capped by [MAX_GOAL_STEER]. */
        private const val GOAL_WEIGHT_Y: Double = 0.003
        /** Hard cap on per-tick Y steer so a moth far from the goal altitude doesn't
         *  end up flying straight up at the exclusion of the horizontal
         *  drift. Rougly matches [DRIFT_WEIGHT] so the two forces balance
         *  during ascent — moth flies at ~45° up while migrating. */
        private const val MAX_GOAL_STEER: Double = 0.025
        /** Persistent horizontal migration force from [driftYaw]. Tuned
         *  against [SEPARATION_WEIGHT] so a spread-out flock still holds
         *  a coherent overall direction. */
        private const val DRIFT_WEIGHT: Double = 0.025

        /** Per-tick drift-yaw blend fraction toward the neighbourhood's
         *  circular mean. 0.008 ≈ ~125 ticks (~6 s) to close half the
         *  angle gap — slow enough that a passing flock doesn't disrupt,
         *  fast enough that a sustained merger converges on one heading. */
        private const val DRIFT_MERGE_RATE: Float = 0.008f

        /** Wing-section planform extents for dust spawns, in blocks, as
         *  (latMin, latMax, lonMin, lonMax) quads — lat = outward from the
         *  body along the wing, lon = along the heading (+forward, −aft).
         *  Rows: upper wing (forward-swept), lower wing, trailing tip.
         *  Approximate the [SselithMothModel] wing boxes at 1/16 scale. */
        private val WING_SECTIONS: DoubleArray = doubleArrayOf(
            0.2, 3.4, -0.6, 1.6,   // upper
            0.2, 2.5, -2.4, 0.3,   // lower
            0.2, 1.6, -4.9, -2.4,  // trailing tip
        )

        // --- Landing tuning ---
        /** Synced clinging face: -1 airborne, else Direction#get3DDataValue. */
        private val DATA_LANDED_FACE: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(SselithMothEntity::class.java, EntityDataSerializers.BYTE)

        /** 1-in-N ticks a flying moth rolls a landing attempt (~25 s mean). */
        private const val LAND_ATTEMPT_INTERVAL = 500

        /** Random block samples per attempt; most fail fast in open air,
         *  and the wing-disc clearance test rejects cramped sites — sized
         *  up so attempts still succeed on rough terrain. */
        private const val LAND_SAMPLE_COUNT = 20

        /** Wing-footprint probe grid, in the moth's local frame (blocks):
         *  lateral spread just inside the ±3.7 wing reach, longitudinal
         *  from the −4.9 trailing tips to the +1.6 fore sweep. */
        private val WING_CLEAR_LAT = doubleArrayOf(-3.5, -1.75, 0.0, 1.75, 3.5)
        private val WING_CLEAR_LON = doubleArrayOf(-4.5, -2.5, -0.5, 1.5)

        /** Probe lift off the wing plane — tolerates the dip-side wing
         *  grazing a conforming slope without vetoing the site. */
        private const val WING_CLEAR_PROBE_LIFT = 0.35

        /** Half-extent of the random landing-spot XZ search. */
        private const val LAND_SEARCH_RADIUS = 8.0

        /** Landing Y bands: high-altitude moths descend to the
         *  Repertory's crown, low-altitude moths rise to its floor. */
        private const val LAND_BAND_HIGH_MIN = 115
        private const val LAND_BAND_HIGH_MAX = 125
        private const val LAND_BAND_LOW_MIN = -129
        private const val LAND_BAND_LOW_MAX = -126

        /** Gap between the face plane and the entity origin at rest. */
        private const val LAND_SURFACE_EPS = 0.02

        /** Approach flight speed (blocks/tick) — 2× cruise, because the
         *  commute from the cruise altitude to the landing band is short
         *  now (~15 blocks) — the speed just keeps landings snappy. */
        private const val LAND_APPROACH_SPEED = 0.24

        /** Give up the approach after this many ticks without touching
         *  down. Sized for the ~80-block commute at [LAND_APPROACH_SPEED]
         *  (~340 ticks) plus headroom. */
        private const val LAND_APPROACH_TIMEOUT = 900

        /** SDF sample range at touchdown, with a raised box budget so the
         *  wider neighbourhood isn't arbitrarily truncated at the field's
         *  default 128-box cap (fine here — this samples once per landing,
         *  not per tick like the Constriliar). */
        private const val LAND_SDF_RANGE = 3.0
        private const val LAND_SDF_MAX_BOXES = 512

        /** Max deviation of the perch normal from the landing face —
         *  underfoot geometry still outranks the surroundings, but crag
         *  crests are allowed a hard lean. */
        private val LAND_MAX_TILT_RAD = Math.toRadians(60.0)

        /** Extra sample standoff above the face — lifts the SDF probe to
         *  where a crag's walls contribute, so crest curvature registers. */
        private const val LAND_SDF_EXTRA_STANDOFF = 0.75

        /** Deviation amplifier — the raw soft gradient under-reads slope
         *  on top-face landings; the gain makes the lean visible. */
        private const val LAND_TILT_GAIN = 1.6

        /** Near-body standoff radius for tilted perches. NOT the full wing
         *  span — the tilt conforms to the slope, so the terrain drops
         *  away under the dip-side wing; only the body-adjacent region
         *  needs lifting off the contact face. At the 40° clamp this is
         *  ~0.4 blocks instead of the old ~2.4. */
        private const val LAND_TILT_CLEARANCE = 0.6
        private const val WING_CULL_INFLATE_XZ = 4.5
        private const val WING_CULL_INFLATE_Y = 0.5


        /** SOFT log-sum-exp for the perch sample (rounding ≈ 1/2 block):
         *  the whole point is that several blocks blend into an effective
         *  inclined surface — individual block faces are always axis-flat,
         *  so a sharp sample can never produce a conforming tilt. */
        private const val LAND_SDF_SMOOTHING = 2.0



        /** Synced components of the SDF perch normal (see [landedNormal]). */
        private val DATA_LAND_NX: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(SselithMothEntity::class.java, EntityDataSerializers.FLOAT)
        private val DATA_LAND_NY: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(SselithMothEntity::class.java, EntityDataSerializers.FLOAT)
        private val DATA_LAND_NZ: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(SselithMothEntity::class.java, EntityDataSerializers.FLOAT)

        /** Distance at which the approach snaps onto the anchor. */
        private const val LAND_SNAP_DIST = 0.25

        /** Within this range the moth stops pathfinding and glides straight
         *  to the anchor (face-adjacent air the pathfinder won't enter). */
        private const val LAND_DIRECT_DIST = 3.0

        /** Repath cadence during the pathfinding leg. */
        private const val LAND_REPATH_INTERVAL = 20

        /** Nav speed modifier (× FLYING_SPEED attribute). */
        private const val LAND_NAV_SPEED = 1.0

        /** Consecutive colliding ticks before the moth declares itself
         *  stuck; escape flight duration; escape speed; SDF sample range
         *  for picking the way out. */
        private const val STUCK_TICKS_THRESHOLD = 8
        private const val ESCAPE_TICKS = 30
        private const val ESCAPE_SPEED = MAX_SPEED * 1.5
        private const val ESCAPE_SDF_RANGE = 2.5

        /** Perch duration: MIN + rand(VAR) ticks (10–25 s). */
        private const val LAND_STAY_MIN = 200
        private const val LAND_STAY_VAR = 300

        /** A player this close startles the moth off its perch. */
        private const val LAND_DISTURB_RADIUS = 2.5

        /** Push-off speed along the face normal at takeoff. */
        private const val TAKEOFF_SPEED = 0.08

        /** Per-client-tick blend rate toward the landed/flying pose.
         *  Deliberately slow — ~2 s to settle (95% at ~40 ticks). Drives
         *  the animation cross-fade, the perch tilt slerp, AND the camo
         *  fade, so the whole landing reads as one unhurried motion. */
        private const val LANDED_BLEND_RATE = 0.07f

        @JvmField
        val TEXTURE: ResourceLocation = EnderkinesisMod.id("textures/entity/sselith_moth.png")
    }
}
