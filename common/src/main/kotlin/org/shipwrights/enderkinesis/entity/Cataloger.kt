package org.shipwrights.enderkinesis.entity

import java.util.Optional
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.AnimationState
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.registry.EKSounds

/**
 * The Cataloger — a tall humanoid that wanders Sselith's Repertory, slowly
 * pathing between blocks tagged `#enderkinesis:cataloger_targets`
 * (lecterns, signs, chiseled bookshelves, enchanting tables, gold blocks).
 *
 *  - Renders via a custom robed humanoid model with Blockbench keyframe
 *    animations (see `CatalogerAnimations`). Walking and idle play on
 *    separate, mutually exclusive [AnimationState]s — time-driven, so the
 *    cycle cadence matches the artist's design regardless of the cataloger's
 *    actual movement speed.
 *  - Footstep sounds use [EKSounds.CATALOGER_FOOTSTEPS] (`sselith1..6.ogg`)
 *    and are **synced to the animation phase**, not to walked distance:
 *    `aiStep` fires a step at the exact ticks the keyframes plant each foot
 *    (phase 12 = left, phase 47 = right inside the 80-tick / 4-second cycle).
 *    Vanilla's distance-triggered call to `playStepSound` is cancelled.
 *  - Leaves a trail of yellow `sselith_mote` dust while walking, spawned
 *    server-side at random positions within the bounding box.
 *  - **Fades when overlapping another cataloger.** [intersectionAlpha] is
 *    recomputed each client tick as `1 − max(overlap_volume / our_volume)`
 *    across nearby catalogers; the renderer reads it to drive a translucent
 *    alpha pass on the model. Two catalogers fully overlapped → both invisible;
 *    half-overlapped → both 50% transparent.
 */
class Cataloger(type: EntityType<out Cataloger>, level: Level) : PathfinderMob(type, level) {

    init {
        // Quadruple the pathfinder's node budget. Sselith corridors are long
        // and branching — vanilla's 5000-node default exhausts before
        // finding a path to a target one cell over, so the goal sees
        // `path == null` and never moves.
        navigation.setMaxVisitedNodesMultiplier(PATHFIND_NODE_MULTIPLIER)
    }

    /** Use a [CatalogerNodeEvaluator]-backed ground navigation so the cataloger
     *  never paths onto bookshelf walls or target blocks (lecterns, signs…). It
     *  still walks floors, stairs and ladders, so floor-to-floor wandering is
     *  unchanged — it just stops climbing the walls. Mirrors vanilla
     *  `GroundPathNavigation.createPathFinder`, swapping in our evaluator. */
    override fun createNavigation(level: Level): PathNavigation =
        object : GroundPathNavigation(this, level) {
            override fun createPathFinder(maxVisitedNodes: Int): PathFinder {
                nodeEvaluator = CatalogerNodeEvaluator()
                nodeEvaluator.setCanPassDoors(true)
                return PathFinder(nodeEvaluator, maxVisitedNodes)
            }
        }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        // Rare bookshelf summon — pauses both wander goals while it runs.
        goalSelector.addGoal(1, SummonTomeFromBookshelfGoal(this))
        // The wander goal also owns LOOK while staring at its current target.
        // No LookAtPlayerGoal: the cataloger is indifferent to onlookers.
        // POI-backed lookup means the search radius can be wide without
        // costing per-tick block scans.
        goalSelector.addGoal(2, WanderToTaggedBlockGoal(this, SEARCH_RADIUS))
        // Lower priority than the POI goal: only drifts to a random point when no
        // target is found; a POI find at priority 2 interrupts it.
        goalSelector.addGoal(3, CatalogerWanderGoal(this, SEARCH_RADIUS, MOVE_SPEED))
        goalSelector.addGoal(6, RandomLookAroundGoal(this))
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_TOME_BOOKSHELF, Optional.empty())
        entityData.define(DATA_TOME_RETURN_BOOKSHELF, Optional.empty())
    }

    /** Server-side: kick off the tome-summon visual. The tome emerges
     *  from [source] for the outbound flight and disappears into
     *  [returnTo] on the inbound — pass the same value for both when
     *  only one eligible shelf is in range. Caller (the goal) is
     *  responsible for the matching [endTomeSummon]. */
    fun beginTomeSummon(source: BlockPos, returnTo: BlockPos) {
        entityData.set(DATA_TOME_BOOKSHELF, Optional.of(source.immutable()))
        entityData.set(DATA_TOME_RETURN_BOOKSHELF, Optional.of(returnTo.immutable()))
    }

    /** Server-side: clear the summon — the client clears its local
     *  start tick via [onSyncedDataUpdated] and stops drawing. */
    fun endTomeSummon() {
        entityData.set(DATA_TOME_BOOKSHELF, Optional.empty())
        entityData.set(DATA_TOME_RETURN_BOOKSHELF, Optional.empty())
    }

    /** Source bookshelf (where the outbound flight begins), or null
     *  when no summon is running. */
    val tomeSummonBookshelf: BlockPos?
        get() = entityData.get(DATA_TOME_BOOKSHELF).orElse(null)

    /** Return bookshelf (where the inbound flight ends), or null when
     *  no summon is running. Equal to [tomeSummonBookshelf] when only
     *  one bookshelf was eligible at goal start. */
    val tomeReturnBookshelf: BlockPos?
        get() = entityData.get(DATA_TOME_RETURN_BOOKSHELF).orElse(null)

    /** Client-side tick at which the summon-bookshelf transitioned from
     *  empty → present (i.e., when this client first learned the summon
     *  was happening). `-1` outside an active summon. Used by the
     *  renderer to compute elapsed-since-start for phase math. */
    @Volatile
    var clientTomeSummonStartTick: Int = -1
        private set

    /** Spring-eased hold-point for the tome's dwell anchor. Renderer
     *  computes the gaze-vector target each frame and calls
     *  [updateSmoothedTomeHold] to advance this toward it — the book
     *  catches up exponentially over `~1/HOLD_EASE_PER_TICK` ticks, so
     *  rapid head turns leave a visible spring trail. `NaN` until the
     *  first call of each summon; reset on summon-end. */
    @Volatile var clientSmoothedTomeHoldX: Double = Double.NaN
        private set
    @Volatile var clientSmoothedTomeHoldY: Double = Double.NaN
        private set
    @Volatile var clientSmoothedTomeHoldZ: Double = Double.NaN
        private set
    @Volatile private var clientSmoothedTomeHoldTick: Int = -1

    /** Advance the smoothed hold toward the gaze-direction target by
     *  one exponential ease step per server tick that has passed since
     *  the previous call. Snaps to target on the first frame of a
     *  summon, and snaps again if more than ~2.5 s has elapsed (the
     *  player paused, switched dimensions, etc.). */
    fun updateSmoothedTomeHold(targetX: Double, targetY: Double, targetZ: Double) {
        val tick = this.tickCount
        val last = clientSmoothedTomeHoldTick
        val deltaT = tick - last
        if (clientSmoothedTomeHoldX.isNaN() || deltaT < 0 || deltaT > 50) {
            clientSmoothedTomeHoldX = targetX
            clientSmoothedTomeHoldY = targetY
            clientSmoothedTomeHoldZ = targetZ
        } else if (deltaT > 0) {
            val keep = Math.pow((1.0 - HOLD_EASE_PER_TICK), deltaT.toDouble())
            clientSmoothedTomeHoldX = targetX + (clientSmoothedTomeHoldX - targetX) * keep
            clientSmoothedTomeHoldY = targetY + (clientSmoothedTomeHoldY - targetY) * keep
            clientSmoothedTomeHoldZ = targetZ + (clientSmoothedTomeHoldZ - targetZ) * keep
        }
        clientSmoothedTomeHoldTick = tick
    }

    /** Clear the smoothed hold so the next summon initialises afresh
     *  (no leftover position from a previous run jumping the arc). */
    private fun resetSmoothedTomeHold() {
        clientSmoothedTomeHoldX = Double.NaN
        clientSmoothedTomeHoldY = Double.NaN
        clientSmoothedTomeHoldZ = Double.NaN
        clientSmoothedTomeHoldTick = -1
    }

    override fun onSyncedDataUpdated(dataAccessor: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(dataAccessor)
        if (!this.level().isClientSide) return
        if (dataAccessor == DATA_TOME_BOOKSHELF) {
            val present = entityData.get(DATA_TOME_BOOKSHELF).isPresent
            clientTomeSummonStartTick = if (present) this.tickCount else -1
            if (!present) resetSmoothedTomeHold()
        }
    }


    /** Idle animation state — keyframe clock for [CatalogerAnimations.IDLE]. Started
     *  once on first tick and never stopped: even while moving the idle clock keeps
     *  advancing so that when the cataloger stops again the resumed idle reads
     *  in-phase, not as a sudden reset to frame 0. The model scales its contribution
     *  by `(1 − walkBlend)`, so a running idle clock with zero visual weight is
     *  cheap. Public for [org.shipwrights.enderkinesis.client.model.CatalogerModel]
     *  access. */
    val idleAnimationState: AnimationState = AnimationState()

    /** Walking animation state — same treatment as [idleAnimationState] but scaled
     *  by `walkBlend` in the model. Stays running so cycle phase is preserved
     *  through stops. */
    val walkingAnimationState: AnimationState = AnimationState()

    /** Current blend weight between idle (0) and walking (1). Lerped each tick
     *  toward the target by [BLEND_RATE]; the model cross-fades the two animations
     *  using this value. Visible to the renderer through `walkBlendOld`/`walkBlend`
     *  + partial-tick interpolation for sub-tick smoothness. */
    var walkBlend: Float = 0f
        private set

    /** Previous tick's [walkBlend]. The model lerps `(walkBlendOld → walkBlend)` by
     *  partial-tick to get a smooth visual transition that doesn't step per-tick. */
    var walkBlendOld: Float = 0f
        private set

    /** The tick on which `walkingAnimationState` was first started, captured so we
     *  can compute the current animation cycle phase server-side and align the
     *  footstep / dust triggers exactly with what the model is rendering. -1
     *  before the first aiStep. */
    private var walkAnimStartTick: Int = -1

    /** Render-only fade based on overlap with other catalogers. Updated each
     *  client tick by [tick]; `1` = fully visible, `0` = fully invisible. The
     *  renderer reads this to switch into a translucent render type and the
     *  model multiplies it into the vertex alpha. */
    var intersectionAlpha: Float = 1f
        private set

    override fun aiStep() {
        super.aiStep()
        // Cheap moving check based on the tick's horizontal displacement. Using
        // `deltaMovement` would also work but reads zero for pathfinder mobs in
        // certain navigation transitions; positional delta is more reliable.
        val dxz = (x - xo) * (x - xo) + (z - zo) * (z - zo)
        val moving = dxz > MOVE_EPSILON

        walkBlendOld = walkBlend
        val target = if (moving) 1f else 0f
        val delta = target - walkBlend
        walkBlend = (walkBlend + delta.coerceIn(-BLEND_RATE, BLEND_RATE)).coerceIn(0f, 1f)

        // First-tick: start both states with the SAME startTick and capture it
        // for later phase math. After this, the model's per-frame `updateTime`
        // calls advance the AnimationState clocks at 50 ms/tick — so on tick T
        // the walking-animation cycle phase (in ticks) is
        // (T - walkAnimStartTick) mod WALKING_CYCLE_TICKS.
        if (walkAnimStartTick < 0) {
            idleAnimationState.start(this.tickCount)
            walkingAnimationState.start(this.tickCount)
            walkAnimStartTick = this.tickCount
        }

        val phaseTick = Math.floorMod(this.tickCount - walkAnimStartTick, WALKING_CYCLE_TICKS)

        if (walkBlend > WALK_AUDIBLE_THRESHOLD) {
            if (phaseTick == LEFT_PLANT_PHASE || phaseTick == RIGHT_PLANT_PHASE) {
                playFootstepNow()
            }
            if (this.tickCount % DUST_INTERVAL_TICKS == 0) {
                spawnDustTrail()
            }
        }

        // Vanilla pushEntities (which we don't override — see [pushNearbyCatalogers])
        // skips other catalogers because their `isPushable = false`. Run our own
        // gentle pass here so catalogers still shove each other apart.
        pushNearbyCatalogers()
    }

    /** Cancel the vanilla distance-triggered step sound. Footsteps are emitted
     *  from [aiStep] on the exact animation-cycle ticks where the keyframes
     *  plant a foot. */
    override fun playStepSound(pos: BlockPos, state: BlockState) {
        // intentionally empty
    }

    /** Pure-visual update: each tick on the client, scan for other catalogers
     *  whose bounding box overlaps ours and compute the maximum overlap ratio.
     *  The renderer uses `1 − maxOverlap` as the model's alpha. Cheap because
     *  Sselith spawns catalogers sparsely and the AABB query is the entity-
     *  manager's per-chunk filter. Server-side this is skipped — no gameplay
     *  consequence to the fade. */
    override fun tick() {
        super.tick()
        if (this.level().isClientSide) {
            intersectionAlpha = computeIntersectionAlpha()
        }
    }

    private fun computeIntersectionAlpha(): Float {
        // Inflate both boxes by FADE_INFLATE so the fade engages a bit before
        // physical contact and decays a bit after, giving a softer fade envelope.
        // Symmetric inflation keeps two catalogers fading by the same amount.
        val ourBB = this.boundingBox.inflate(FADE_INFLATE)
        val ourVolume = (ourBB.maxX - ourBB.minX) * (ourBB.maxY - ourBB.minY) * (ourBB.maxZ - ourBB.minZ)
        if (ourVolume <= 0.0) return 1f

        var maxOverlap = 0.0
        // `getEntitiesOfClass(class, AABB, predicate)` filters at the entity-manager
        // level — small list to iterate even in a busy biome. Predicate excludes self
        // AND filters in either another Cataloger or a player whose Sselith Madness
        // amplifier is at [PLAYER_CATALOGER_MIN_AMP] or higher (level 4+), so a
        // possessed player fades catalogers that share their bounding box exactly
        // like a Cataloger neighbour would.
        for (other in this.level().getEntitiesOfClass(LivingEntity::class.java, ourBB) {
            it !== this && countsAsCataloger(it)
        }) {
            val o = other.boundingBox.inflate(FADE_INFLATE)
            val ix = (Math.min(ourBB.maxX, o.maxX) - Math.max(ourBB.minX, o.minX)).coerceAtLeast(0.0)
            val iy = (Math.min(ourBB.maxY, o.maxY) - Math.max(ourBB.minY, o.minY)).coerceAtLeast(0.0)
            val iz = (Math.min(ourBB.maxZ, o.maxZ) - Math.max(ourBB.minZ, o.minZ)).coerceAtLeast(0.0)
            val overlap = (ix * iy * iz) / ourVolume
            if (overlap > maxOverlap) maxOverlap = overlap
        }
        return (1.0 - maxOverlap).toFloat().coerceIn(0f, 1f)
    }

    /** Pick and play a random Sselith footstep at the cataloger's position.
     *  Called from [aiStep] when the walking-cycle phase crosses a foot-plant
     *  tick. Server-side `playSound` broadcasts to all nearby clients. */
    private fun playFootstepNow() {
        val idx = random.nextInt(EKSounds.CATALOGER_FOOTSTEPS.size)
        val sound = EKSounds.CATALOGER_FOOTSTEPS[idx].get()
        val pitch = FOOTSTEP_PITCH_MIN + random.nextFloat() * (FOOTSTEP_PITCH_MAX - FOOTSTEP_PITCH_MIN)
        playSound(sound, FOOTSTEP_VOLUME, pitch)
    }

    /** Emit [DUST_PER_TICK] `sselith_dust` particles at random positions inside the
     *  bounding box. Server-side via `sendParticles` so every nearby player sees
     *  the same trail; gravity pulls each one down, block collision lets it settle
     *  on the floor / shelves the cataloger passes, and a short particle lifetime
     *  fades the trail out behind. `sselith_dust` is the collision-aware sibling
     *  of the biome-wide `sselith_mote` — see [EKParticles.SSELITH_DUST]. */
    private fun spawnDustTrail() {
        val lvl = this.level()
        if (lvl !is ServerLevel) return
        val bb = this.boundingBox
        val dx = bb.maxX - bb.minX
        val dy = bb.maxY - bb.minY
        val dz = bb.maxZ - bb.minZ
        repeat(DUST_PER_TICK) {
            lvl.sendParticles(
                EKParticles.sselithDust(),
                bb.minX + random.nextDouble() * dx,
                bb.minY + random.nextDouble() * dy,
                bb.minZ + random.nextDouble() * dz,
                1,
                0.0, 0.0, 0.0,
                0.0,
            )
        }
    }

    /** Catalogers never interact with fluids — fitting their phasic nature, and
     *  free of cost. Sselith's generator places no water or lava, and catalogers
     *  are invulnerable and confined to dry deepslate platforms, so the vanilla
     *  per-tick fluid machinery is pure dead weight for them.
     *
     *  [updateFluidHeightAndDoFluidPushing] is the per-block AABB fluid scan
     *  vanilla runs every tick from `baseTick` (once for water-current pushing,
     *  once for lava). Profiling Sselith showed it — together with the Valkyrien
     *  Skies `@WrapOperation`s on the `getFluidState` lookups *inside* it
     *  (`overrideFluidStateForWaterPockets`, the `ShipWaterPocketManager` calls) —
     *  accounting for a large slice of each cataloger's tick. Returning `false`
     *  without scanning short-circuits the vanilla scan **and** every VS2 wrapper
     *  that hangs off the `getFluidState` calls inside it, because virtual dispatch
     *  lands here instead of on the mixin'd `Entity` method.
     *
     *  [isEyeInFluid] is the eye-submersion check (breathing / drowning / fog).
     *  Catalogers don't breathe, don't drown, and render their own fog-free void,
     *  so it's always `false`.
     *
     *  Net behavioural effect: a cataloger walks through player-placed water as if
     *  it were dry, with no current push and no swim — consistent with a phasic
     *  inhabitant that the dimension's hazards don't touch. */
    override fun updateFluidHeightAndDoFluidPushing(fluidTag: TagKey<Fluid>, motionScale: Double): Boolean = false

    override fun isEyeInFluid(fluidTag: TagKey<Fluid>): Boolean = false

    /** No ambient idle sound; the footsteps are the only audible voice. */
    override fun getAmbientSound() = null

    override fun getSoundSource(): SoundSource = SoundSource.NEUTRAL

    /** Catalogers never despawn — the per-column spawn is deterministic at
     *  chunk-gen, and we don't want them disappearing the first time a
     *  player wanders 128 blocks away. */
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    /** Catalogers are invulnerable. All damage sources are absorbed so they
     *  never die — they're permanent ambient inhabitants of Sselith. */
    override fun isInvulnerableTo(source: DamageSource): Boolean = true

    /** Catalogers can't be pushed — by other mobs, players, or by their own
     *  reaction to pushing neighbours. With this `false`, other entities'
     *  `pushEntities` excludes us (the `EntitySelector.pushableBy` filter
     *  checks `isPushable`), and our own [pushEntities] only pushes others
     *  outward without a self back-reaction.
     *
     *  Net: the cataloger plows through any crowd, gently shoving entities
     *  aside as it walks, and nothing can shove it back. */
    override fun isPushable(): Boolean = false

    /** Short-circuit the per-entity push for any entity that
     *  [countsAsCataloger] — i.e. another Cataloger (filtered earlier
     *  by `isPushable`, but defensive) or a player at madness level 4+.
     *  Without this, vanilla `pushEntities` calls `doPush` on the
     *  high-madness player with the full ≈ 0.05 vanilla force, and
     *  then [pushNearbyCatalogers] piles the cataloger-style gentle
     *  shove on top — the player gets pushed twice. With this
     *  override, only the gentle pass applies, matching how two real
     *  catalogers interact. */
    override fun doPush(entity: Entity) {
        if (countsAsCataloger(entity)) return
        super.doPush(entity)
    }

    /** Cataloger-vs-cataloger push: vanilla `pushEntities` (which we deliberately
     *  DON'T override — earlier diagnostics showed any override of pushEntities,
     *  even an additive one calling `super`, breaks the cataloger's own
     *  navigation in a way I couldn't pin down) skips other catalogers because
     *  `EntitySelector.pushableBy` filters them out (`isPushable = false`). To
     *  still get catalogers shoving each other apart we do a separate gentle
     *  push pass here, called from [aiStep] after `super.aiStep()`.
     *
     *  Direction / falloff mirror vanilla's `Entity.push(Entity)` so the visual
     *  feel matches; force is [GENTLE_PUSH_FORCE] (1/5 of vanilla's `0.05`).
     *  Calling `Entity.push(double, double, double)` directly bypasses the
     *  `isPushable` gate, which is what lets two `isPushable = false` catalogers
     *  shove each other. */
    private fun pushNearbyCatalogers() {
        val level = this.level()
        if (level.isClientSide) return
        // Includes other Catalogers AND players at madness level 4+ —
        // the high-madness player is treated as one of us for the
        // gentle inter-cataloger shove.
        val nearby = level.getEntitiesOfClass(LivingEntity::class.java, this.boundingBox) {
            it !== this && countsAsCataloger(it)
        }
        if (nearby.isEmpty()) return
        for (other in nearby) {
            if (other.isPassengerOfSameVehicle(this)) continue
            if (other.noPhysics || this.noPhysics) continue
            if (other.isVehicle) continue
            val dx = other.x - this.x
            val dz = other.z - this.z
            var d = Mth.absMax(dx, dz)
            if (d < 0.01) continue
            d = Math.sqrt(d)
            val nx = dx / d
            val nz = dz / d
            val scale = (1.0 / d).coerceAtMost(1.0)
            other.push(nx * scale * GENTLE_PUSH_FORCE, 0.0, nz * scale * GENTLE_PUSH_FORCE)
        }
    }

    /** True iff [entity] should be treated as a Cataloger for the
     *  cataloger-vs-cataloger push and intersection-fade systems.
     *  Either an actual Cataloger or a player whose Sselith Madness
     *  amplifier is `≥ [PLAYER_CATALOGER_MIN_AMP]` (level 4 or above). */
    private fun countsAsCataloger(entity: Entity): Boolean {
        if (entity is Cataloger) return true
        if (entity !is Player) return false
        val effect = entity.getEffect(EKEffects.SSELITH_MADNESS.get()) ?: return false
        return effect.amplifier >= PLAYER_CATALOGER_MIN_AMP
    }

    companion object {
        const val ID_PATH = "cataloger"
        val ID: ResourceLocation = EnderkinesisMod.id(ID_PATH)

        /** Source bookshelf for the rare summon-tome flourish — where the
         *  outbound flight begins. Empty when no summon is running.
         *  Synced from server so the client renderer can draw the
         *  floating tome. */
        @JvmField
        val DATA_TOME_BOOKSHELF: EntityDataAccessor<Optional<BlockPos>> =
            SynchedEntityData.defineId(Cataloger::class.java, EntityDataSerializers.OPTIONAL_BLOCK_POS)

        /** Return bookshelf for the inbound flight. Picked separately
         *  from the source so the tome can disappear into a *different*
         *  Sselith bookshelf than the one it emerged from; when only
         *  one eligible bookshelf is nearby, the goal sets this equal
         *  to [DATA_TOME_BOOKSHELF] and the inbound flight retraces. */
        @JvmField
        val DATA_TOME_RETURN_BOOKSHELF: EntityDataAccessor<Optional<BlockPos>> =
            SynchedEntityData.defineId(Cataloger::class.java, EntityDataSerializers.OPTIONAL_BLOCK_POS)

        /** Outbound flight ticks (bookshelf → cataloger, closed, tumbling).
         *  140 ticks ≈ 7 s — long enough for the eased path/tumble to read
         *  as a deliberate float rather than a snap. */
        const val TOME_OUTBOUND_TICKS = 140

        /** Dwell ticks at the cataloger (open, idle page flop). 240 ≈ 12 s. */
        const val TOME_DWELL_TICKS = 240

        /** Inbound flight ticks (cataloger → bookshelf, closed, tumbling). */
        const val TOME_INBOUND_TICKS = 140

        /** Total ticks of one summon cycle, server-side window length. */
        const val TOME_TOTAL_TICKS =
            TOME_OUTBOUND_TICKS + TOME_DWELL_TICKS + TOME_INBOUND_TICKS

        /** Per-tick exponential-ease factor for the smoothed hold-point.
         *  0.18 → about 25 % of the gap closed each tick; half-life ≈
         *  3.5 ticks (≈ 0.175 s) at 20 tps. Snappy but the spring is
         *  visible when the head whips between source and return. */
        private const val HOLD_EASE_PER_TICK: Double = 0.18

        /** Squared horizontal-displacement threshold (per tick) above which the
         *  cataloger is considered "moving" — drives idle/walking state switching.
         *  ~0.001 blocks² ≈ 0.032 b/tick, well below any real navigation step. */
        private const val MOVE_EPSILON = 0.000001

        /** Minimum Sselith Madness **amplifier** (= level − 1) for a
         *  player to count as a Cataloger in the inter-cataloger push
         *  and intersection-fade systems. 3 = madness level 4. Same
         *  threshold the menu-walk possession uses. */
        const val PLAYER_CATALOGER_MIN_AMP = 3

        /** Per-tick step for the idle↔walking cross-fade. 0.15 → full transition in
         *  ~7 ticks (≈0.35s) — long enough that the foot-plant of the walking cycle
         *  doesn't pop, short enough that a stop reads as "settling" rather than
         *  "drifting to a halt". */
        private const val BLEND_RATE = 0.15f

        private const val FOOTSTEP_VOLUME = 0.35f
        private const val FOOTSTEP_PITCH_MIN = 0.85f
        private const val FOOTSTEP_PITCH_MAX = 1.05f

        /** XZ radius (blocks) for the POI search. Set near the pathfinder's
         *  practical reach — POIs much further than this are almost never
         *  navigable through Sselith's stairwell/ladder/pathway maze, so a
         *  wider search just wastes pickTarget retries. */
        private const val SEARCH_RADIUS = 48

        /** Navigation speed modifier for the fallback [CatalogerWanderGoal] —
         *  1.0 (full), matching [WanderToTaggedBlockGoal]'s `MOVE_SPEED`. */
        private const val MOVE_SPEED = 1.0

        /** Multiplier on `PathFinder.maxVisitedNodes` (default 5000). Sselith
         *  paths visit many junction nodes, and two-floor routes through
         *  stairwells/ladders expand that further; 8× gives us ~40 000 to play
         *  with, enough budget for the
         *  [org.shipwrights.enderkinesis.entity.WanderToTaggedBlockGoal.SAME_FLOOR_Y]
         *  = 48-block vertical reach. */
        private const val PATHFIND_NODE_MULTIPLIER = 8f

        /** Length of one walking-animation cycle in server ticks (4 s × 20 tps). */
        private const val WALKING_CYCLE_TICKS = 80

        /** Phase tick within the cycle at which the LEFT foot finishes its swing
         *  and plants. Derived from the keyframe data in `CatalogerAnimations.WALKING`:
         *  `left_leg` POSITION returns to Y=0 at t ≈ 0.58 s → 0.58 × 20 ≈ 12 ticks. */
        private const val LEFT_PLANT_PHASE = 12

        /** Phase tick within the cycle at which the RIGHT foot plants. From the
         *  same keyframe analysis: `right_leg` POSITION returns to Y=0 at
         *  t ≈ 2.33 s → 2.33 × 20 ≈ 47 ticks. */
        private const val RIGHT_PLANT_PHASE = 47

        /** [walkBlend] threshold above which footstep / dust effects fire. Below
         *  this the visual blend is still mostly idle, so a footstep here would
         *  read as a step from a standing pose — uncanny. */
        private const val WALK_AUDIBLE_THRESHOLD = 0.5f

        /** Per-axis inflation (blocks) applied to the cataloger's bounding box
         *  when computing the cataloger-vs-cataloger overlap fade. A small bump
         *  softens the fade onset so the alpha starts dropping just before
         *  physical contact and finishes just after — symmetric so two
         *  catalogers fade by identical amounts. */
        private const val FADE_INFLATE = 0.15

        /** Force multiplier in [pushEntities], replacing vanilla `Entity.push(Entity)`'s
         *  hardcoded 0.05. Five-times smaller — neighbours drift out of the way as
         *  the cataloger shambles past instead of getting visibly flicked. */
        private const val GENTLE_PUSH_FORCE = 0.01

        /** Yellow mote particles emitted per dust burst. Distributed uniformly
         *  through the bounding box (0.6 × 1.8 × 0.6 ≈ 0.65 m³). */
        private const val DUST_PER_TICK = 1

        /** Tick interval between dust bursts. With [DUST_PER_TICK] = 1 and an
         *  interval of 4, we emit 5 particles/second — a thin trail that the
         *  ground accumulation reads against, but not the dense haze that
         *  spawning every tick produced. */
        private const val DUST_INTERVAL_TICKS = 4

        /** Default attributes — slow, modest health, no attack.
         *
         *  `FOLLOW_RANGE` doubles as the path-length budget for [PathNavigation]
         *  (`getMaxPathLength()` reads it). Setting it to match the POI
         *  search radius means the cataloger can actually path to the
         *  distant POIs the goal hands it; vanilla's default 32 would let
         *  the cataloger pick a target 100 blocks away and then immediately
         *  fail to build a path, sit for 45 s, and repeat — looking exactly
         *  like "doesn't move". */
        fun createAttributes(): AttributeSupplier.Builder =
            LivingEntity.createLivingAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.153)
                .add(Attributes.FOLLOW_RANGE, 192.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6)
    }
}
