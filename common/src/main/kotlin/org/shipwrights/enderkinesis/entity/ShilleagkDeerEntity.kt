package org.shipwrights.enderkinesis.entity

import com.mojang.logging.LogUtils
import dev.architectury.registry.registries.RegistrySupplier
import java.util.EnumSet
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.AnimationState
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.FollowParentGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.PanicGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.TemptGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.pathfinder.BlockPathTypes
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.util.TICKS_PER_SECOND
import org.shipwrights.enderkinesis.vine.VineTip
import org.shipwrights.enderkinesis.vine.VineTipFilter
import org.shipwrights.enderkinesis.vine.checkVineTip
import org.shipwrights.enderkinesis.vine.eatVineTip
import org.shipwrights.enderkinesis.vine.findEdibleVineTip
import org.shipwrights.enderkinesis.vine.holdVineTipNext
import org.shipwrights.enderkinesis.vine.pinVineTip
import org.shipwrights.enderkinesis.vine.releaseVineTip
import kotlin.math.cos
import kotlin.math.sin

/** Wohlon omnivore. It always flees players and predators, grazes Wohlon plants and wades into shallows for fish. */
class ShilleagkDeerEntity(type: EntityType<out ShilleagkDeerEntity>, level: Level) : Animal(type, level) {

    enum class Action { NONE, GRAZE, UPRIGHT, FISH, ATTACK_ANTLER, ATTACK_BITE, VINE_CHEW, FEED_SPOT }

    val grazeAnimationState: AnimationState = AnimationState()
    val grazeBiteAnimationState: AnimationState = AnimationState()
    val attackAntlerAnimationState: AnimationState = AnimationState()
    val attackBiteAnimationState: AnimationState = AnimationState()
    val idleLookBackAnimationState: AnimationState = AnimationState()
    val idleTailFlickAnimationState: AnimationState = AnimationState()
    val idleStampAnimationState: AnimationState = AnimationState()
    val freakyStartAnimationState: AnimationState = AnimationState()
    val freakyLoopAnimationState: AnimationState = AnimationState()
    val freakyEndAnimationState: AnimationState = AnimationState()

    private var clientAction: Action = Action.NONE
    private var clientFreakyLoopTick: Int = -1
    private var clientFreakyEndTick: Int = -1
    private var clientFeedRestartTick: Int = -1
    private var clientIdle: AnimationState? = null
    private var clientIdleEndTick: Int = -1
    /** Client tick when VINE_CHEW began; the model opens and chews the jaw from it. */
    var clientChewStartTick: Int = 0
        private set

    private var mealCooldown: Int = random.nextInt(MEAL_COOLDOWN_MIN + MEAL_COOLDOWN_SPREAD)
    private var handFedTicks: Int = 0
    // Not saved: trust is short and a reload may forget it.
    private val trustedUntil: MutableMap<UUID, Long> = HashMap()
    private var lastFleeTick: Int = Int.MIN_VALUE / 2
    private var lastSprintTick: Int = Int.MIN_VALUE / 2
    private var wellFedTicks: Int = 0

    // Not saved: a reload rests the deer.
    var sprintStamina: Int = MAX_SPRINT_STAMINA
        private set
    var isExhausted: Boolean = false
        private set
    private val trail = LongArray(TRAIL_SAMPLES)
    private var trailHead: Int = -1
    private val refugeScan = BlockPos.MutableBlockPos()
    private val depthScan = BlockPos.MutableBlockPos()

    val isFleeing: Boolean get() = tickCount - lastSprintTick <= SPRINT_ACTIVE_TICKS
    val isTired: Boolean get() = isExhausted || sprintStamina < TIRED_STAMINA
    val fatigue: Float get() = 1f - sprintStamina.toFloat() / MAX_SPRINT_STAMINA

    /** True while the deer stands in or on water at least [REFUGE_DEPTH] deep. */
    val isInRefuge: Boolean
        get() {
            depthScan.set(blockPosition())
            if (!level().getFluidState(depthScan).`is`(FluidTags.WATER)) depthScan.move(Direction.DOWN)
            return waterDepthAt(depthScan) >= REFUGE_DEPTH
        }

    private var lastWalkPhase: Float = 0f
    // lateinit: Mob's constructor calls registerGoals before this class's initializers run; servers only.
    private lateinit var vineTipGoal: EatVineTipGoal
    private val rippleScan = BlockPos.MutableBlockPos()

    val action: Action
        get() = Action.entries.getOrElse(entityData.get(DATA_ACTION).toInt()) { Action.NONE }

    private fun setAction(next: Action) = entityData.set(DATA_ACTION, next.ordinal.toByte())

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_ACTION, Action.NONE.ordinal.toByte())
    }

    init {
        installWogorLeavesControls(this) { move, jump ->
            moveControl = move
            jumpControl = jump
        }
    }

    override fun createNavigation(level: Level): PathNavigation =
        wogorLeavesNavigation(this, level, walksOnWater = true)

    override fun canStandOnFluid(fluidState: FluidState): Boolean = fluidState.`is`(FluidTags.WATER)

    override fun tick() {
        super.tick()
        floatOnWater()
    }

    // Same rule as Strider.floatStrider, for water.
    private fun floatOnWater() {
        if (!isInWater) return
        val pos = blockPosition()
        if (CollisionContext.of(this).isAbove(LiquidBlock.STABLE_SHAPE, pos, true) &&
            !level().getFluidState(pos.above()).`is`(FluidTags.WATER)
        ) {
            setOnGround(true)
        } else {
            deltaMovement = deltaMovement.scale(FLOAT_DAMPING).add(0.0, FLOAT_RISE, 0.0)
        }
    }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, CorneredStrikeGoal())
        goalSelector.addGoal(1, HandFedGoal())
        goalSelector.addGoal(1, DeerPanicGoal())
        goalSelector.addGoal(2, AtBayGoal())
        goalSelector.addGoal(2, FleeGoal(LivingEntity::class.java) { it.type.`is`(PREDATORS) })
        // Vanilla Ocelot order: tempt outranks the flee from players, and a scared tempt lets the flee run.
        goalSelector.addGoal(3, TemptGoal(this, TEMPT_SPEED, Ingredient.of(HAND_FEED_MEATS), true))
        goalSelector.addGoal(4, FleeGoal(Player::class.java) { !isTrusted(it) })
        goalSelector.addGoal(5, HuntFishGoal())
        vineTipGoal = EatVineTipGoal()
        goalSelector.addGoal(6, vineTipGoal)
        goalSelector.addGoal(6, GrazeGoal())
        goalSelector.addGoal(7, FreakyIdleGoal())
        goalSelector.addGoal(7, BreedGoal())
        goalSelector.addGoal(7, FollowParentGoal(this, FOLLOW_PARENT_SPEED))
        goalSelector.addGoal(8, WaterAvoidingRandomStrollGoal(this, WALK_SPEED))
        goalSelector.addGoal(9, WatchDistantPlayerGoal())
        goalSelector.addGoal(10, RandomLookAroundGoal(this))
    }

    override fun getAmbientSound(): SoundEvent = EKSounds.SHILLEAGK_DEER_AMBIENT.get()

    override fun getHurtSound(source: DamageSource): SoundEvent = EKSounds.SHILLEAGK_DEER_HURT.get()

    override fun getDeathSound(): SoundEvent = EKSounds.SHILLEAGK_DEER_DEATH.get()

    override fun playStepSound(pos: BlockPos, state: BlockState) {
        if (state.fluidState.`is`(FluidTags.WATER)) {
            playSound(EKSounds.SHILLEAGK_DEER_WADE.get(), STEP_VOLUME, 1f)
            return
        }
        super.playStepSound(pos, state)
        playSound(EKSounds.SHILLEAGK_DEER_STEP.get(), STEP_VOLUME, 1f)
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (!player.getItemInHand(hand).`is`(HAND_FEED_MEATS)) return super.mobInteract(player, hand)
        if (level().isClientSide) return InteractionResult.SUCCESS
        if (handFedTicks > 0) return InteractionResult.PASS
        player.useOneHeld(hand)
        heal(MEAL_HEAL)
        mealCooldown = MEAL_COOLDOWN_MIN + random.nextInt(MEAL_COOLDOWN_SPREAD)
        wellFedTicks = WELL_FED_TICKS
        handFedTicks = FISH_EAT_TICKS
        val now = level().gameTime
        trustedUntil.values.removeIf { it <= now }
        trustedUntil[player.uuid] = now + TRUST_TICKS
        return InteractionResult.CONSUME
    }

    private fun isTrusted(entity: LivingEntity): Boolean {
        val until = trustedUntil[entity.uuid] ?: return false
        return until > level().gameTime
    }

    // A dead or removed deer runs no goal stop, so its pinned vine tip must be let go here.
    override fun die(source: DamageSource) {
        if (::vineTipGoal.isInitialized) vineTipGoal.letGo("died")
        super.die(source)
    }

    override fun remove(reason: RemovalReason) {
        if (::vineTipGoal.isInitialized) vineTipGoal.letGo("removed")
        super.remove(reason)
    }

    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    // Breeding is BreedGoal's own rule, so no item starts vanilla love mode.
    override fun isFood(stack: ItemStack): Boolean = false

    override fun getBreedOffspring(level: ServerLevel, otherParent: AgeableMob): AgeableMob? =
        EKEntities.SHILLEAGK_DEER.get().create(level)

    // Animal prefers grass and light when it wanders, and sounds less often; keep the PathfinderMob and Mob values.
    override fun getWalkTargetValue(pos: BlockPos, level: LevelReader): Float = 0f

    override fun getAmbientSoundInterval(): Int = AMBIENT_SOUND_INTERVAL

    override fun getDimensions(pose: Pose): EntityDimensions =
        if (action == Action.UPRIGHT) UPRIGHT_DIMENSIONS.scale(scale) else super.getDimensions(pose)

    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(key)
        if (key != DATA_ACTION) return
        refreshDimensions()
        if (level().isClientSide) onClientActionChanged()
    }

    // Vanilla caps the swing speed at 1 (0.25 blocks/tick), which the run gait passes.
    override fun updateWalkAnimation(distance: Float) {
        walkAnimation.update(minOf(distance * LIMB_SWING_PER_BLOCK, MAX_LIMB_SWING_SPEED), LIMB_SWING_SMOOTHING)
    }

    override fun aiStep() {
        super.aiStep()
        if (level().isClientSide) {
            tickClientAnimations()
            tickRipples()
            return
        }
        if (mealCooldown > 0) mealCooldown--
        if (wellFedTicks > 0) wellFedTicks--
        tickSprintStamina()
        recordTrail()
    }

    private fun tickSprintStamina() {
        if (isFleeing) {
            if (sprintStamina > 0 && --sprintStamina == 0) isExhausted = true
        } else if (sprintStamina < MAX_SPRINT_STAMINA && tickCount % SPRINT_RECOVER_INTERVAL == 0) {
            sprintStamina++
            if (isExhausted && sprintStamina >= TIRED_STAMINA) isExhausted = false
        }
    }

    private fun recordTrail() {
        if (trailHead >= 0 && tickCount % TRAIL_SAMPLE_TICKS != 0) return
        val pos = blockPosition().asLong()
        if (trailHead < 0) {
            trail.fill(pos)
            trailHead = 0
            return
        }
        trailHead = (trailHead + 1) % TRAIL_SAMPLES
        trail[trailHead] = pos
    }

    /** Where the deer was [samplesBack] trail samples ago; 0 is the newest, each older one [TRAIL_SAMPLE_TICKS] earlier. */
    fun trailPoint(samplesBack: Int, out: BlockPos.MutableBlockPos): BlockPos.MutableBlockPos {
        require(samplesBack in 0 until TRAIL_SAMPLES)
        if (trailHead < 0) return out.set(blockPosition())
        return out.set(trail[Math.floorMod(trailHead - samplesBack, TRAIL_SAMPLES)])
    }

    private fun waterDepthAt(top: BlockPos.MutableBlockPos): Int {
        var depth = 0
        while (depth < REFUGE_DEPTH && level().getFluidState(top).`is`(FluidTags.WATER)) {
            depth++
            top.move(Direction.DOWN)
        }
        return depth
    }

    /** Nearest deep-water surface that the deer reaches before [threat]; a bounded scan of every other column. */
    private fun findRefuge(threat: LivingEntity, out: BlockPos.MutableBlockPos): Boolean {
        val origin = blockPosition()
        var best = Double.MAX_VALUE
        for (dx in -REFUGE_SCAN_RADIUS..REFUGE_SCAN_RADIUS step REFUGE_SCAN_STEP) {
            for (dz in -REFUGE_SCAN_RADIUS..REFUGE_SCAN_RADIUS step REFUGE_SCAN_STEP) {
                for (dy in -REFUGE_SCAN_HEIGHT..REFUGE_SCAN_HEIGHT) {
                    refugeScan.set(origin.x + dx, origin.y + dy, origin.z + dz)
                    val mine = distanceToSqr(refugeScan.x + 0.5, refugeScan.y.toDouble(), refugeScan.z + 0.5)
                    if (mine >= best || !isRefugeSurface()) continue
                    if (threat.distanceToSqr(refugeScan.x + 0.5, refugeScan.y.toDouble(), refugeScan.z + 0.5) <= mine) continue
                    best = mine
                    out.set(refugeScan)
                }
            }
        }
        return best != Double.MAX_VALUE
    }

    private fun isRefugeSurface(): Boolean {
        val level = level()
        if (!level.getFluidState(refugeScan).`is`(FluidTags.WATER)) return false
        if (level.getFluidState(depthScan.set(refugeScan).move(Direction.UP)).`is`(FluidTags.WATER)) return false
        return waterDepthAt(depthScan.set(refugeScan)) >= REFUGE_DEPTH
    }

    fun mayBreed(): Boolean =
        !isBaby && age == 0 && wellFedTicks > 0 && !isExhausted && !isFleeing && health >= maxHealth

    private fun isThreat(entity: LivingEntity): Boolean = entity.isAlive && when {
        entity is Player -> EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(entity)
        entity.type.`is`(PREDATORS) -> true
        else -> entity === lastHurtByMob
    }

    private fun onClientActionChanged() {
        val previous = clientAction
        val next = action
        clientAction = next
        stopClientIdle()
        if (previous == Action.GRAZE) grazeAnimationState.stop()
        if (previous == Action.FISH || previous == Action.FEED_SPOT) grazeBiteAnimationState.stop()
        clientFeedRestartTick = -1
        if (previous == Action.ATTACK_ANTLER) attackAntlerAnimationState.stop()
        if (previous == Action.ATTACK_BITE) attackBiteAnimationState.stop()
        if (previous == Action.UPRIGHT) {
            freakyStartAnimationState.stop()
            freakyLoopAnimationState.stop()
            clientFreakyLoopTick = -1
            freakyEndAnimationState.start(tickCount)
            clientFreakyEndTick = tickCount + FREAKY_END_TICKS
        }
        when (next) {
            Action.GRAZE -> grazeAnimationState.start(tickCount)
            Action.FISH -> grazeBiteAnimationState.start(tickCount)
            Action.ATTACK_ANTLER -> attackAntlerAnimationState.start(tickCount)
            Action.ATTACK_BITE -> attackBiteAnimationState.start(tickCount)
            Action.UPRIGHT -> {
                freakyEndAnimationState.stop()
                clientFreakyEndTick = -1
                freakyStartAnimationState.start(tickCount)
                clientFreakyLoopTick = tickCount + FREAKY_START_TICKS
            }
            Action.VINE_CHEW -> clientChewStartTick = tickCount
            Action.FEED_SPOT -> {
                grazeBiteAnimationState.start(tickCount)
                clientFeedRestartTick = tickCount + FISH_EAT_TICKS
            }
            Action.NONE -> Unit
        }
    }

    private fun tickClientAnimations() {
        // grazeBite is play-once, so eating at the catch spot restarts it to loop.
        if (action == Action.FEED_SPOT && clientFeedRestartTick >= 0 && tickCount >= clientFeedRestartTick) {
            grazeBiteAnimationState.start(tickCount)
            clientFeedRestartTick = tickCount + FISH_EAT_TICKS
        }
        if (clientFreakyLoopTick >= 0 && tickCount >= clientFreakyLoopTick) {
            freakyStartAnimationState.stop()
            freakyLoopAnimationState.start(tickCount)
            clientFreakyLoopTick = -1
        }
        if (clientFreakyEndTick >= 0 && tickCount >= clientFreakyEndTick) {
            freakyEndAnimationState.stop()
            clientFreakyEndTick = -1
        }
        tickClientIdle()
    }

    // Idles are cosmetic, so each client picks its own; no server sync.
    private fun tickClientIdle() {
        val idle = clientIdle
        val still = action == Action.NONE && onGround() && walkAnimation.speed() < IDLE_MAX_SWING_SPEED
        if (idle != null) {
            if (!still || tickCount >= clientIdleEndTick) stopClientIdle()
            return
        }
        if (!still || random.nextInt(IDLE_CHANCE) != 0) return
        val (state, ticks) = when (random.nextInt(3)) {
            0 -> idleLookBackAnimationState to IDLE_LOOK_BACK_TICKS
            1 -> idleTailFlickAnimationState to IDLE_TAIL_FLICK_TICKS
            else -> idleStampAnimationState to IDLE_STAMP_TICKS
        }
        state.start(tickCount)
        if (state === idleStampAnimationState) {
            level().playLocalSound(x, y, z, EKSounds.SHILLEAGK_DEER_STAMP.get(), soundSource, 1f, 1f, false)
        }
        clientIdle = state
        clientIdleEndTick = tickCount + ticks
    }

    private fun tickRipples() {
        val cycle = walkAnimation.position() / WALK_CYCLE_SWING
        val phase = cycle - Mth.floor(cycle)
        val last = lastWalkPhase
        lastWalkPhase = phase
        if (walkAnimation.speed() < IDLE_MAX_SWING_SPEED) return
        for (foot in FEET) {
            val touched = if (phase >= last) {
                foot.phase > last && foot.phase <= phase
            } else {
                foot.phase > last || foot.phase <= phase
            }
            if (touched) rippleUnder(foot)
        }
    }

    private fun rippleUnder(foot: Foot) {
        val yaw = yBodyRot * Mth.DEG_TO_RAD
        val sin = Mth.sin(yaw)
        val cos = Mth.cos(yaw)
        val fx = x + cos * foot.left + sin * foot.back
        val fz = z + sin * foot.left - cos * foot.back
        val surface = waterSurfaceAt(fx, fz)
        if (!surface.isNaN()) level().addParticle(EKParticles.waterRipple(), fx, surface + RIPPLE_LIFT, fz, 0.0, 0.0, 0.0)
    }

    /** NaN when the foot is not in water, or when the surface is too far above it. */
    private fun waterSurfaceAt(fx: Double, fz: Double): Double {
        val level = level()
        val pos = rippleScan.set(fx, y, fz)
        if (!level.getFluidState(pos).`is`(FluidTags.WATER)) {
            pos.move(Direction.DOWN)
            if (!level.getFluidState(pos).`is`(FluidTags.WATER)) return Double.NaN
        }
        repeat(RIPPLE_MAX_DEPTH_BLOCKS) { _ ->
            pos.move(Direction.UP)
            if (!level.getFluidState(pos).`is`(FluidTags.WATER)) {
                pos.move(Direction.DOWN)
                return pos.y + level.getFluidState(pos).getHeight(level, pos).toDouble()
            }
        }
        return Double.NaN
    }

    private fun stopClientIdle() {
        clientIdle?.stop()
        clientIdle = null
        clientIdleEndTick = -1
    }

    private fun inMouthReach(target: Vec3): Boolean {
        val dx = target.x - x
        val dz = target.z - z
        val dy = target.y - y
        return dx * dx + dz * dz <= MOUTH_REACH * MOUTH_REACH && dy >= -MOUTH_DROP && dy <= MOUTH_RISE
    }

    private fun faceToward(target: Vec3) = faceToward(target.x, target.z)

    private fun faceToward(targetX: Double, targetZ: Double) {
        val yaw = (Mth.atan2(targetZ - z, targetX - x) * Mth.RAD_TO_DEG).toFloat() - 90f
        yRot = yaw
        yBodyRot = yaw
        yHeadRot = yaw
    }

    /**
     * The jaw tip of ShilleagkDeerModel with no clip playing: the neck turns to yHeadRot and the head pitches by xRot,
     * as setupAnim does. A clip pose is client-only, so call this only while the action is NONE or VINE_CHEW.
     */
    private fun mouthPoint(out: Vector3d): Vector3d {
        val pitch = xRot * Mth.DEG_TO_RAD.toDouble()
        val reach = (HEAD_FORWARD_PX + MOUTH_UP_PX * sin(pitch) + MOUTH_FORWARD_PX * cos(pitch)) / PX_PER_BLOCK
        val rise = (NECK_UP_PX + HEAD_UP_PX + MOUTH_UP_PX * cos(pitch) - MOUTH_FORWARD_PX * sin(pitch)) / PX_PER_BLOCK
        val body = yBodyRot * Mth.DEG_TO_RAD.toDouble()
        val head = yHeadRot * Mth.DEG_TO_RAD.toDouble()
        val neck = NECK_FORWARD_PX / PX_PER_BLOCK
        return out.set(x - sin(body) * neck - sin(head) * reach, y + rise, z + cos(body) * neck + cos(head) * reach)
    }

    private fun isInShallowWater(entity: LivingEntity): Boolean {
        if (!entity.isInWater) return false
        val cursor = entity.blockPosition().mutable()
        var depth = 1
        while (depth <= SHALLOW_WATER_DEPTH && level().getFluidState(cursor.move(0, 1, 0)).`is`(FluidTags.WATER)) depth++
        cursor.set(entity.blockPosition())
        while (depth <= SHALLOW_WATER_DEPTH && level().getFluidState(cursor.move(0, -1, 0)).`is`(FluidTags.WATER)) depth++
        return depth <= SHALLOW_WATER_DEPTH
    }

    /** Walk up to a meal, play the graze clip, and consume it on the bite frame. */
    /** The alarm marks the start of a flight, not each re-path while the threat stays near. A spent deer stops fleeing. */
    private inner class FleeGoal<T : LivingEntity>(type: Class<T>, threat: (LivingEntity) -> Boolean) :
        AvoidEntityGoal<T>(this, type, FLEE_DISTANCE, RUN_SPEED, RUN_SPEED, threat) {
        private val refuge = BlockPos.MutableBlockPos()

        // In deep water the deer is safe; the goat does not follow it there.
        override fun canUse(): Boolean = !isExhausted && !isInRefuge && super.canUse()

        override fun canContinueToUse(): Boolean = !isExhausted && super.canContinueToUse()

        override fun start() {
            super.start()
            if (tickCount - lastFleeTick > FLEE_GAP_TICKS) playSound(EKSounds.SHILLEAGK_DEER_ALARM.get(), 1f, 1f)
            lastFleeTick = tickCount
            lastSprintTick = tickCount
            val threat = toAvoid ?: return
            if (findRefuge(threat, refuge)) {
                // Return value ignored: with no path the deer keeps the AvoidEntityGoal path it already has.
                navigation.moveTo(refuge.x + 0.5, refuge.y.toDouble(), refuge.z + 0.5, RUN_SPEED)
            }
            slowIfTired()
        }

        override fun tick() {
            super.tick()
            lastFleeTick = tickCount
            lastSprintTick = tickCount
            slowIfTired()
        }
    }

    // AvoidEntityGoal and PanicGoal set their own speed on start and each tick.
    private fun slowIfTired() {
        if (isTired) navigation.setSpeedModifier(TIRED_RUN_SPEED)
    }

    /** Vanilla panic, but it tires the deer like a flight, and a spent deer does not panic. */
    private inner class DeerPanicGoal : PanicGoal(this@ShilleagkDeerEntity, RUN_SPEED) {
        override fun canUse(): Boolean = !isExhausted && super.canUse()

        override fun canContinueToUse(): Boolean = !isExhausted && super.canContinueToUse()

        override fun start() {
            super.start()
            lastSprintTick = tickCount
            slowIfTired()
        }

        override fun tick() {
            super.tick()
            lastSprintTick = tickCount
            slowIfTired()
        }
    }

    /** A spent deer stands and faces the nearest threat until it gets its breath back; [CorneredStrikeGoal] does the fighting. */
    private inner class AtBayGoal : Goal() {
        private var threat: LivingEntity? = null

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean {
            if (!isExhausted) return false
            threat = level().getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(FLEE_DISTANCE.toDouble())) {
                isThreat(it) && hasLineOfSight(it)
            }.minByOrNull { it.distanceToSqr(this@ShilleagkDeerEntity) }
            return threat != null
        }

        override fun canContinueToUse(): Boolean {
            val facing = threat ?: return false
            return isExhausted && facing.isAlive && distanceToSqr(facing) < FLEE_DISTANCE * FLEE_DISTANCE
        }

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() = navigation.stop()

        override fun tick() {
            val facing = threat ?: return
            faceToward(facing.position())
            lookControl.setLookAt(facing)
        }

        override fun stop() {
            threat = null
        }
    }

    /** Logistic growth: a well-fed adult pairs with a nearby one only while the local herd is above its minimum and under its target. */
    private inner class BreedGoal : Goal() {
        private var partner: ShilleagkDeerEntity? = null
        private var ticks = 0

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean {
            if (!mayBreed() || random.nextInt(BREED_CHANCE) != 0) return false
            val herd = level().getEntitiesOfClass(ShilleagkDeerEntity::class.java, boundingBox.inflate(BREED_SCAN_RANGE)) { it.isAlive }
            if (herd.size < BREED_MIN_DEER || herd.size >= BREED_TARGET_DEER) return false
            partner = herd.filter { it !== this@ShilleagkDeerEntity && it.mayBreed() && distanceToSqr(it) < BREED_PARTNER_RANGE * BREED_PARTNER_RANGE }
                .minByOrNull { distanceToSqr(it) }
            return partner != null
        }

        override fun canContinueToUse(): Boolean {
            val mate = partner ?: return false
            return ticks < BREED_TIMEOUT_TICKS && mate.isAlive && mayBreed() && mate.mayBreed()
        }

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            ticks = 0
        }

        override fun tick() {
            val mate = partner ?: return
            ticks++
            lookControl.setLookAt(mate)
            if (distanceToSqr(mate) < BREED_REACH * BREED_REACH) {
                breedWith(mate)
            } else if (ticks % REPATH_TICKS == 1) {
                // Return value ignored: a failed path ends the goal through the timeout.
                navigation.moveTo(mate, WALK_SPEED)
            }
        }

        override fun stop() {
            partner = null
            navigation.stop()
        }

        private fun breedWith(mate: ShilleagkDeerEntity) {
            val level = level() as? ServerLevel ?: return
            val fawn = getBreedOffspring(level, mate) ?: return
            fawn.age = AgeableMob.BABY_START_AGE
            fawn.moveTo(x, y, z, 0f, 0f)
            age = BREED_COOLDOWN_TICKS
            mate.age = BREED_COOLDOWN_TICKS
            level.addFreshEntityWithPassengers(fawn)
            // [DeerDebug] temporary
            LOG.info("[DeerDebug] deer {} and {} bred a fawn at {}", id, mate.id, blockPosition())
        }
    }

    private abstract inner class MealGoal(private val approachSpeed: Double) : Goal() {
        private var ticks = 0
        private var eatTicks = -1

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)
        }

        protected abstract val mealAction: Action
        protected abstract val eatDurationTicks: Int
        protected abstract val biteTick: Int
        protected abstract val biteSound: RegistrySupplier<SoundEvent>
        protected open val creepsUntilBite: Boolean = false
        protected var ate = false
            private set
        protected val finishedEating: Boolean get() = eatTicks >= eatDurationTicks
        protected open fun onKill() = Unit
        protected open fun whileEating() = Unit
        protected abstract fun findMeal(): Boolean
        protected abstract fun mealPosition(): Vec3
        protected abstract fun mealStillThere(): Boolean
        protected abstract fun consume(): Boolean

        override fun canUse(): Boolean =
            mealCooldown <= 0 && random.nextInt(MEAL_CHANCE) == 0 && findMeal()

        // The timeout bounds the approach only; once eating, the eat timer bounds the meal.
        override fun canContinueToUse(): Boolean =
            eatTicks < eatDurationTicks && (eatTicks >= 0 || (ticks < MEAL_TIMEOUT_TICKS && mealStillThere()))

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            ticks = 0
            eatTicks = -1
            ate = false
            moveTowardMeal()
        }

        override fun tick() {
            ticks++
            if (eatTicks >= 0) {
                eatTicks++
                if (creepsUntilBite) creepTowardMeal()
                if (ate) whileEating()
                if (eatTicks == biteTick && mealStillThere() && inMouthReach(mealPosition())) {
                    if (!consume()) return
                    heal(MEAL_HEAL)
                    playSound(biteSound.get(), 1f, 1f)
                    mealCooldown = MEAL_COOLDOWN_MIN + random.nextInt(MEAL_COOLDOWN_SPREAD)
                    wellFedTicks = WELL_FED_TICKS
                    ate = true
                    onKill()
                }
                return
            }
            val target = mealPosition()
            lookControl.setLookAt(target.x, target.y, target.z)
            if (inMouthReach(target)) {
                navigation.stop()
                faceToward(target)
                eatTicks = 0
                setAction(mealAction)
            } else if (ticks % REPATH_TICKS == 0) {
                moveTowardMeal()
            }
        }

        override fun stop() {
            if (action == mealAction || action == Action.FEED_SPOT) setAction(Action.NONE)
            navigation.stop()
            // navigation.stop() does not clear a creep's MoveControl target.
            if (creepsUntilBite) moveControl.setWantedPosition(x, y, z, 0.0)
            if (!ate) mealCooldown = maxOf(mealCooldown, FAILED_MEAL_COOLDOWN)
        }

        private fun creepTowardMeal() {
            val target = mealPosition()
            val dx = target.x - x
            val dz = target.z - z
            if (eatTicks < biteTick && mealStillThere() && dx * dx + dz * dz > CREEP_STOP * CREEP_STOP) {
                faceToward(target)
                moveControl.setWantedPosition(target.x, target.y, target.z, CREEP_SPEED)
            } else {
                moveControl.setWantedPosition(x, y, z, 0.0)
            }
        }

        private fun moveTowardMeal() {
            val target = mealPosition()
            // Navigation result ignored: a failed path ends the goal through the timeout.
            navigation.moveTo(target.x, target.y, target.z, approachSpeed)
        }
    }

    private inner class GrazeGoal : MealGoal(WALK_SPEED) {
        private val plant = BlockPos.MutableBlockPos()
        private val cursor = BlockPos.MutableBlockPos()

        override val mealAction: Action = Action.GRAZE
        override val eatDurationTicks: Int = GRAZE_TICKS
        override val biteTick: Int = GRAZE_BITE_TICK
        override val biteSound: RegistrySupplier<SoundEvent> = EKSounds.SHILLEAGK_DEER_GRAZE

        override fun findMeal(): Boolean {
            val origin = blockPosition()
            var bestDistSqr = Int.MAX_VALUE
            for (dx in -GRAZE_RADIUS..GRAZE_RADIUS) {
                for (dz in -GRAZE_RADIUS..GRAZE_RADIUS) {
                    for (dy in -GRAZE_HEIGHT..GRAZE_HEIGHT) {
                        val distSqr = dx * dx + dy * dy + dz * dz
                        if (distSqr >= bestDistSqr) continue
                        cursor.setWithOffset(origin, dx, dy, dz)
                        if (level().getBlockState(cursor).`is`(GRAZES_ON)) {
                            plant.set(cursor)
                            bestDistSqr = distSqr
                        }
                    }
                }
            }
            return bestDistSqr != Int.MAX_VALUE
        }

        override fun mealPosition(): Vec3 = Vec3.atBottomCenterOf(plant)

        override fun mealStillThere(): Boolean = level().getBlockState(plant).`is`(GRAZES_ON)

        override fun consume(): Boolean {
            if (level().gameRules.getBoolean(GameRules.RULE_MOBGRIEFING)) {
                // Return value ignored: mealStillThere() already proved the block is present.
                level().destroyBlock(plant, false, this@ShilleagkDeerEntity)
            } else {
                level().levelEvent(LEVEL_EVENT_BLOCK_BREAK, plant, Block.getId(level().getBlockState(plant)))
            }
            return true
        }
    }

    private inner class HuntFishGoal : MealGoal(RUN_SPEED) {
        private var prey: LivingEntity? = null
        private var spotX = 0.0
        private var spotY = 0.0
        private var spotZ = 0.0

        override val mealAction: Action = Action.FISH
        // The catch, then the deer stands over the spot where the fish was and eats it.
        override val eatDurationTicks: Int = FISH_BITE_TICK + FISH_SPOT_EAT_TICKS
        override val biteTick: Int = FISH_BITE_TICK
        override val biteSound: RegistrySupplier<SoundEvent> = EKSounds.SHILLEAGK_DEER_FISH
        override val creepsUntilBite: Boolean = true

        override fun findMeal(): Boolean {
            prey = level().getEntitiesOfClass(
                LivingEntity::class.java,
                boundingBox.inflate(HUNT_RANGE, HUNT_HEIGHT, HUNT_RANGE),
            ) { it.type.`is`(PREY) && it.isAlive && isInShallowWater(it) }
                .minByOrNull { it.distanceToSqr(this@ShilleagkDeerEntity) }
            return prey != null
        }

        override fun mealPosition(): Vec3 = requireNotNull(prey) { "hunt goal running with no prey" }.position()

        override fun mealStillThere(): Boolean = prey?.let { it.isAlive && isInShallowWater(it) } == true

        override fun consume(): Boolean {
            val fish = prey ?: return false
            return fish.eatWhole(this@ShilleagkDeerEntity)
        }

        // The fish is gone, so the meal runs on the eat timer, facing the spot where it was.
        override fun onKill() {
            prey?.let {
                spotX = it.x
                spotY = it.y
                spotZ = it.z
            }
            setAction(Action.FEED_SPOT)
        }

        override fun whileEating() {
            navigation.stop()
            faceToward(spotX, spotZ)
            lookControl.setLookAt(spotX, spotY, spotZ)
        }

        // The default WATER malus keeps paths on land when there is a dry route; a hunt wades straight in.
        override fun start() {
            setPathfindingMalus(BlockPathTypes.WATER, 0f)
            super.start()
        }

        override fun stop() {
            val finished = ate && finishedEating
            super.stop()
            setPathfindingMalus(BlockPathTypes.WATER, BlockPathTypes.WATER.malus)
            if (finished) playSound(SoundEvents.GENERIC_EAT, 1f, 1f)
            prey = null
        }
    }

    private enum class VineMealStage { APPROACH, BITE, CHEW, DONE }

    /** Walk to a vine node in reach, bite it, hold its link across the chewing mouth, then eat that link. */
    private inner class EatVineTipGoal : Goal(), VineTipFilter {
        private val tip = VineTip()
        private val mouth = Vector3d()
        private val groundScan = BlockPos.MutableBlockPos()
        private var stage = VineMealStage.DONE
        private var ticks = 0
        private var stageTicks = 0
        private var nextScanTick = 0
        private var unreachableVine = 0L
        private var path: Path? = null
        private var pinned = false
        private var ate = false
        private var abortReason: String? = null
        // Where the jaw caught the tip: the pin eases from it to the mouth, and the chew nods about it.
        private var caughtX = 0.0
        private var caughtY = 0.0
        private var caughtZ = 0.0
        // The next node at the chew start, and the side of the mouth that the rest of the vine leaves from.
        private var nextFromX = 0.0
        private var nextFromY = 0.0
        private var nextFromZ = 0.0
        private var side = 1.0

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)
        }

        override fun canUse(): Boolean {
            if (mealCooldown > 0 || tickCount < nextScanTick) return false
            nextScanTick = tickCount + VINE_SCAN_TICKS
            val level = level() as? ServerLevel ?: return false
            if (!level.findEdibleVineTip(tip, x, y, z, VINE_SCAN_RADIUS, VINE_SCAN_HEIGHT, this, unreachableVine)) return false
            val found = navigation.createPath(BlockPos.containing(tip.x, tip.groundY, tip.z), VINE_PATH_ACCURACY)
            if (found == null || !found.canReach()) {
                unreachableVine = tip.vineId
                return false
            }
            path = found
            // [DeerDebug] temporary
            LOG.info(
                "[DeerDebug] deer {} vine target: vine {} node {} ({}) at {} {} {}, {} above ground",
                id, tip.vineId, tip.node, kind(), "%.2f".format(tip.x), "%.2f".format(tip.y), "%.2f".format(tip.z),
                "%.2f".format(tip.y - tip.groundY),
            )
            return true
        }

        override fun canContinueToUse(): Boolean = stage != VineMealStage.DONE && abortReason == null && hurtTime == 0

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            ticks = 0
            stageTicks = 0
            stage = VineMealStage.APPROACH
            pinned = false
            ate = false
            abortReason = null
            // Return value ignored: a failed path ends the goal through the timeout.
            navigation.moveTo(path, WALK_SPEED)
            path = null
        }

        override fun tick() {
            ticks++
            stageTicks++
            val level = level() as? ServerLevel ?: return
            abortReason = level.checkVineTip(tip, pinned)
            if (abortReason == null && stage == VineMealStage.APPROACH && ticks > MEAL_TIMEOUT_TICKS) {
                unreachableVine = tip.vineId
                abortReason = "approach timeout"
            }
            if (abortReason != null) return
            when (stage) {
                VineMealStage.APPROACH -> approach()
                VineMealStage.BITE -> bite(level)
                VineMealStage.CHEW -> chew(level)
                VineMealStage.DONE -> Unit
            }
        }

        override fun stop() {
            if (action == Action.ATTACK_BITE || action == Action.VINE_CHEW) setAction(Action.NONE)
            navigation.stop()
            letGo(abortReason ?: if (hurtTime > 0) "hurt" else "interrupted by another goal")
            if (!ate) mealCooldown = maxOf(mealCooldown, FAILED_MEAL_COOLDOWN)
            abortReason = null
        }

        /** Ends a running meal and frees the tip; no-op when no meal runs. */
        fun letGo(reason: String) {
            if (stage == VineMealStage.DONE) return
            if (pinned || tip.nodeHold != null || tip.partnerHold != null) (level() as? ServerLevel)?.releaseVineTip(tip)
            pinned = false
            stage = VineMealStage.DONE
            // [DeerDebug] temporary
            LOG.info("[DeerDebug] deer {} vine meal aborted: {} (vine {} node {}, {})", id, reason, tip.vineId, tip.node, kind())
        }

        override fun groundUnder(nodeX: Double, nodeY: Double, nodeZ: Double): Double {
            val level = level()
            val pos = groundScan.set(nodeX, nodeY, nodeZ)
            repeat(GROUND_PROBE_BLOCKS) { _ ->
                if (!level.isLoaded(pos)) return Double.NaN
                val fluid = level.getFluidState(pos)
                val top = if (fluid.`is`(FluidTags.WATER)) {
                    pos.y + fluid.getHeight(level, pos).toDouble()
                } else {
                    val shape = level.getBlockState(pos).getCollisionShape(level, pos)
                    if (shape.isEmpty) Double.NaN else pos.y + shape.max(Direction.Axis.Y)
                }
                if (!top.isNaN()) {
                    val height = nodeY - top
                    return if (height >= NODE_MIN_HEIGHT && height <= NODE_MAX_HEIGHT) top else Double.NaN
                }
                pos.move(Direction.DOWN)
            }
            return Double.NaN
        }

        private fun approach() {
            lookControl.setLookAt(tip.x, tip.y, tip.z)
            val dx = tip.x - x
            val dz = tip.z - z
            val rise = tip.y - y
            val inReach = dx * dx + dz * dz <= VINE_BITE_REACH * VINE_BITE_REACH &&
                rise >= -VINE_BITE_RISE_SLACK && rise <= NODE_MAX_HEIGHT
            if (inReach) {
                navigation.stop()
                faceToward(tip.x, tip.z)
                enter(VineMealStage.BITE)
                // attackBite snaps at head height; for a low node the jaw opens and the bite lifts the node to it.
                setAction(if (rise >= ATTACK_BITE_MIN_RISE) Action.ATTACK_BITE else Action.VINE_CHEW)
                // The bite sound has the jaw shut timed inside it.
                playSound(EKSounds.SHILLEAGK_DEER_BITE.get(), 1f, 1f)
            } else if (ticks % REPATH_TICKS == 0) {
                // Return value ignored: a failed path ends the goal through the timeout.
                navigation.moveTo(tip.x, tip.groundY, tip.z, WALK_SPEED)
            }
        }

        // The attackBite pose is client-only, so the node holds still from the jaw shut until the clip ends.
        private fun bite(level: ServerLevel) {
            faceToward(tip.x, tip.z)
            if (action == Action.VINE_CHEW) lookControl.setLookAt(tip.x, tip.y, tip.z)
            if (stageTicks == BITE_HIT_TICK) {
                caughtX = tip.x
                caughtY = tip.y
                caughtZ = tip.z
                // [DeerDebug] temporary
                LOG.info("[DeerDebug] deer {} bit vine {} node {} ({})", id, tip.vineId, tip.node, kind())
            }
            if (stageTicks >= BITE_HIT_TICK) pin(level, caughtX, caughtY, caughtZ)
            if (stageTicks >= BITE_TICKS) {
                setAction(Action.VINE_CHEW)
                enter(VineMealStage.CHEW)
                nextFromX = tip.nextX
                nextFromY = tip.nextY
                nextFromZ = tip.nextZ
                // Keep the vine on the side it hangs toward, so it does not pull through the head.
                val yaw = yHeadRot * Mth.DEG_TO_RAD.toDouble()
                side = if ((tip.nextX - caughtX) * cos(yaw) + (tip.nextZ - caughtZ) * sin(yaw) < 0) -1.0 else 1.0
            }
        }

        private fun chew(level: ServerLevel) {
            lookControl.setLookAt(caughtX, caughtY, caughtZ)
            mouthPoint(mouth)
            val ease = minOf(1.0, stageTicks / PIN_EASE_TICKS)
            // The eaten link lies across the mouth, along the head's horizontal side axis.
            val yaw = yHeadRot * Mth.DEG_TO_RAD.toDouble()
            val half = tip.spacing * 0.5 * side
            val acrossX = cos(yaw) * half
            val acrossZ = sin(yaw) * half
            pin(
                level, caughtX + (mouth.x - acrossX - caughtX) * ease, caughtY + (mouth.y - caughtY) * ease,
                caughtZ + (mouth.z - acrossZ - caughtZ) * ease,
            )
            val held = level.holdVineTipNext(
                tip, nextFromX + (mouth.x + acrossX - nextFromX) * ease, nextFromY + (mouth.y - nextFromY) * ease,
                nextFromZ + (mouth.z + acrossZ - nextFromZ) * ease,
            )
            if (!held) abortReason = "the next node hold failed"
            if (abortReason == null && stageTicks >= VINE_CHEW_TICKS) finish(level)
        }

        private fun finish(level: ServerLevel) {
            // [DeerDebug] temporary
            LOG.info("[DeerDebug] deer {} chew end on vine {} node {} ({})", id, tip.vineId, tip.node, kind())
            val griefing = level.gameRules.getBoolean(GameRules.RULE_MOBGRIEFING)
            if (griefing && !level.eatVineTip(tip)) {
                abortReason = "the split failed"
                return
            }
            if (!griefing) level.releaseVineTip(tip)
            pinned = false
            stage = VineMealStage.DONE
            ate = true
            heal(MEAL_HEAL)
            playSound(EKSounds.SHILLEAGK_DEER_GRAZE.get(), 1f, 1f)
            mealCooldown = MEAL_COOLDOWN_MIN + random.nextInt(MEAL_COOLDOWN_SPREAD)
            wellFedTicks = WELL_FED_TICKS
            // [DeerDebug] temporary
            LOG.info(
                "[DeerDebug] deer {} vine {} node {} ({}) segment {}", id, tip.vineId, tip.node, kind(),
                if (griefing) (if (tip.isTip) "eaten" else "eaten, vine snapped") else "kept (mobGriefing off)",
            )
        }

        private fun pin(level: ServerLevel, px: Double, py: Double, pz: Double) {
            if (level.pinVineTip(tip, px, py, pz)) pinned = true else abortReason = "the pin failed"
        }

        private fun enter(next: VineMealStage) {
            stage = next
            stageTicks = 0
        }

        private fun kind(): String = if (tip.isTip) "tip" else "middle"
    }

    /** Chews hand-fed meat with the fish bite; it outranks the flee from players so the bite plays. */
    private inner class HandFedGoal : Goal() {
        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)
        }

        override fun canUse(): Boolean = handFedTicks > 0

        override fun canContinueToUse(): Boolean = handFedTicks > 0 && hurtTime == 0

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            navigation.stop()
            setAction(Action.FISH)
        }

        override fun tick() {
            handFedTicks--
            if (FISH_EAT_TICKS - handFedTicks == FISH_BITE_TICK) playSound(EKSounds.SHILLEAGK_DEER_FISH.get(), 1f, 1f)
        }

        override fun stop() {
            handFedTicks = 0
            if (action == Action.FISH) setAction(Action.NONE)
        }
    }

    /** The deer only fights a threat that got into melee reach while it fled; it turns, strikes once, and runs on. */
    private inner class CorneredStrikeGoal : Goal() {
        private var target: LivingEntity? = null
        private var antler = false
        private var ticks = 0
        private var cooldownUntil = 0

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)
        }

        override fun canUse(): Boolean {
            if (tickCount < cooldownUntil) return false
            target = level().getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(STRIKE_SCAN_RANGE)) {
                isThreat(it) && isWithinMeleeAttackRange(it) && hasLineOfSight(it)
            }.minByOrNull { it.distanceToSqr(this@ShilleagkDeerEntity) }
            return target != null
        }

        override fun canContinueToUse(): Boolean = ticks < (if (antler) ANTLER_TICKS else BITE_TICKS)

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            ticks = 0
            antler = random.nextBoolean()
            navigation.stop()
            setAction(if (antler) Action.ATTACK_ANTLER else Action.ATTACK_BITE)
            // Each strike sound has the hit timed inside it.
            playSound(if (antler) EKSounds.SHILLEAGK_DEER_ANTLER.get() else EKSounds.SHILLEAGK_DEER_BITE.get(), 1f, 1f)
        }

        override fun tick() {
            val victim = target ?: return
            ticks++
            if (ticks <= STRIKE_TURN_TICKS) faceToward(victim.position())
            if (ticks != (if (antler) ANTLER_HIT_TICK else BITE_HIT_TICK)) return
            if (!victim.isAlive || !isWithinMeleeAttackRange(victim)) return
            val damage = if (antler) ANTLER_DAMAGE else BITE_DAMAGE
            if (victim.hurt(damageSources().mobAttack(this@ShilleagkDeerEntity), damage) && antler) {
                val yaw = yRot * Mth.DEG_TO_RAD
                victim.knockback(ANTLER_KNOCKBACK, Mth.sin(yaw).toDouble(), -Mth.cos(yaw).toDouble())
            }
        }

        override fun stop() {
            if (action == Action.ATTACK_ANTLER || action == Action.ATTACK_BITE) setAction(Action.NONE)
            target = null
            cooldownUntil = tickCount + STRIKE_COOLDOWN_TICKS
        }
    }

    /** Rare idle: rear up, stare, snap the head around, drop back down. */
    private inner class FreakyIdleGoal : Goal() {
        private var ticks = 0

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)
        }

        override fun canUse(): Boolean =
            onGround() && !isInWater && navigation.isDone && random.nextInt(FREAKY_CHANCE) == 0 &&
                level().noCollision(this@ShilleagkDeerEntity, UPRIGHT_DIMENSIONS.makeBoundingBox(position()))

        override fun canContinueToUse(): Boolean = ticks < FREAKY_TOTAL_TICKS

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            ticks = 0
            navigation.stop()
            setAction(Action.UPRIGHT)
            playSound(EKSounds.SHILLEAGK_DEER_FREAKY.get(), 1f, 1f)
        }

        override fun tick() {
            ticks++
            if (ticks == FREAKY_START_TICKS + FREAKY_LOOP_TICKS) setAction(Action.NONE)
        }

        override fun stop() {
            if (action == Action.UPRIGHT) setAction(Action.NONE)
        }
    }

    // A player inside FLEE_DISTANCE makes the deer run, so it must not stare at that player.
    private inner class WatchDistantPlayerGoal :
        LookAtPlayerGoal(this@ShilleagkDeerEntity, Player::class.java, LOOK_DISTANCE) {
        override fun canUse(): Boolean = super.canUse() && isDistant()

        override fun canContinueToUse(): Boolean = super.canContinueToUse() && isDistant()

        private fun isDistant(): Boolean =
            lookAt?.let { distanceToSqr(it) >= FLEE_DISTANCE * FLEE_DISTANCE } ?: false
    }

    /** [left] and [back] are in blocks from the body origin; [phase] is the touch-down point in the walk cycle. */
    private class Foot(val phase: Float, val left: Float, val back: Float)

    companion object {
        const val ID_PATH = "shilleagk_deer"
        const val EATEN_TAG = "enderkinesis.eaten"

        private val DATA_ACTION: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(ShilleagkDeerEntity::class.java, EntityDataSerializers.BYTE)

        val GRAZES_ON: TagKey<Block> = TagKey.create(Registries.BLOCK, EnderkinesisMod.id("shilleagk_deer_grazes_on"))
        val PREY: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, EnderkinesisMod.id("shilleagk_deer_prey"))
        val PREDATORS: TagKey<EntityType<*>> =
            TagKey.create(Registries.ENTITY_TYPE, EnderkinesisMod.id("shilleagk_deer_predators"))

        const val WIDTH = 0.9f
        const val HEIGHT = 2.0f
        private val UPRIGHT_DIMENSIONS: EntityDimensions = EntityDimensions.scalable(WIDTH, 2.7f)

        private const val BASE_SPEED = 0.28
        private const val WALK_SPEED = 1.0
        private const val RUN_SPEED = 1.4
        private const val FLEE_DISTANCE = 12f
        private const val FLEE_GAP_TICKS = 60
        // Mob default; Animal raises it to 120.
        private const val AMBIENT_SOUND_INTERVAL = 80

        // Sprint stamina: 15 s of flight, back to full in 60 s of rest.
        private const val MAX_SPRINT_STAMINA = 300
        private const val TIRED_STAMINA = 90
        private const val SPRINT_RECOVER_INTERVAL = 4
        // Flee and panic goals tick every other tick.
        private const val SPRINT_ACTIVE_TICKS = 2
        // About 0.2 blocks/tick, under the goat's trot.
        private const val TIRED_RUN_SPEED = 1.1

        private const val TRAIL_SAMPLES = 8
        private const val TRAIL_SAMPLE_TICKS = 10

        private const val REFUGE_DEPTH = 2
        private const val REFUGE_SCAN_RADIUS = 12
        private const val REFUGE_SCAN_STEP = 2
        private const val REFUGE_SCAN_HEIGHT = 3

        private const val WELL_FED_TICKS = 2_400
        private const val BREED_CHANCE = 400
        private const val BREED_SCAN_RANGE = 32.0
        private const val BREED_MIN_DEER = 2
        private const val BREED_TARGET_DEER = 8
        private const val BREED_PARTNER_RANGE = 12.0
        private const val BREED_REACH = 2.0
        private const val BREED_TIMEOUT_TICKS = 400
        private const val BREED_COOLDOWN_TICKS = 12_000
        private const val FOLLOW_PARENT_SPEED = 1.1
        private const val STEP_VOLUME = 0.5f
        private const val LOOK_DISTANCE = 32f

        // Mob ground speed settles near 2.16 × (attribute × modifier)² blocks per tick.
        const val WALK_GROUND_SPEED: Float = (2.16 * BASE_SPEED * WALK_SPEED * BASE_SPEED * WALK_SPEED).toFloat()
        const val RUN_GROUND_SPEED: Float = (2.16 * BASE_SPEED * RUN_SPEED * BASE_SPEED * RUN_SPEED).toFloat()
        const val LIMB_SWING_PER_BLOCK = 4f
        private const val MAX_LIMB_SWING_SPEED = 2f
        private const val LIMB_SWING_SMOOTHING = 0.4f
        private const val FLOAT_DAMPING = 0.5
        private const val FLOAT_RISE = 0.05

        private const val FREAKY_START_TICKS = 10
        private const val FREAKY_LOOP_TICKS = 102
        private const val FREAKY_END_TICKS = 11
        private const val FREAKY_TOTAL_TICKS = FREAKY_START_TICKS + FREAKY_LOOP_TICKS + FREAKY_END_TICKS
        // Goals are polled every other tick, so this is about one stand per 2 minutes of idle.
        private const val FREAKY_CHANCE = 1200

        private const val STRIKE_SCAN_RANGE = 3.0
        private const val STRIKE_TURN_TICKS = 3
        private const val STRIKE_COOLDOWN_TICKS = 20
        private const val ANTLER_TICKS = 12
        // attackAntler tosses the head up at 0.25 s; attackBite shuts the jaw at 0.21 s.
        private const val ANTLER_HIT_TICK = 5
        private const val BITE_TICKS = 10
        private const val BITE_HIT_TICK = 4
        private const val ANTLER_DAMAGE = 3f
        private const val BITE_DAMAGE = 5f
        private const val ANTLER_KNOCKBACK = 1.0

        // One walk loop of ShilleagkDeerAnimations.walk (0.5 s) at the design walk speed, in limb swing.
        private const val WALK_CYCLE_SWING = 0.5f * TICKS_PER_SECOND * WALK_GROUND_SPEED * LIMB_SWING_PER_BLOCK
        // Touch-down is each leg's lowest walk keyframe; offsets are the leg pivots in ShilleagkDeerModel.
        private val FEET = listOf(
            Foot(0.833f, 2.5f / 16f, -4.5f / 16f),
            Foot(0f, -2.5f / 16f, -4.5f / 16f),
            Foot(0f, 2.5f / 16f, 10.5f / 16f),
            Foot(0.917f, -2.5f / 16f, 10.5f / 16f),
        )
        private const val RIPPLE_LIFT = 0.01
        private const val RIPPLE_MAX_DEPTH_BLOCKS = 2

        private const val IDLE_CHANCE = 160
        private const val IDLE_MAX_SWING_SPEED = 0.05f
        private const val IDLE_LOOK_BACK_TICKS = 25
        private const val IDLE_TAIL_FLICK_TICKS = 10
        private const val IDLE_STAMP_TICKS = 9

        private const val GRAZE_TICKS = 40
        private const val GRAZE_BITE_TICK = 36
        private const val FISH_EAT_TICKS = 39
        // grazeBite shuts the jaw at 1.5 s.
        private const val FISH_BITE_TICK = 30
        private const val FISH_SPOT_EAT_TICKS = 60
        private const val GRAZE_RADIUS = 6
        private const val GRAZE_HEIGHT = 2
        private const val HUNT_RANGE = 16.0
        private const val HUNT_HEIGHT = 4.0
        private const val SHALLOW_WATER_DEPTH = 2
        private const val MOUTH_REACH = 1.6
        private const val MOUTH_DROP = 2.0
        private const val CREEP_SPEED = 0.35
        // The snout tip sits about 0.8 blocks in front of the body origin.
        private const val CREEP_STOP = 0.8
        private const val MOUTH_RISE = 1.0
        private const val MEAL_CHANCE = 100
        private const val MEAL_TIMEOUT_TICKS = 300
        private const val MEAL_HEAL = 2f
        // Vanilla Ocelot tempt speed.
        private const val TEMPT_SPEED = 0.6
        private const val TRUST_TICKS = 1_200L
        private const val MEAL_COOLDOWN_MIN = 600
        private const val MEAL_COOLDOWN_SPREAD = 600
        private const val FAILED_MEAL_COOLDOWN = 100
        private const val REPATH_TICKS = 10

        private const val VINE_SCAN_TICKS = 40
        private const val VINE_SCAN_RADIUS = 16.0
        private const val VINE_SCAN_HEIGHT = 6.0
        private const val VINE_PATH_ACCURACY = 1
        /** Node height over the ground under it. */
        private const val NODE_MIN_HEIGHT = 0.0
        private const val NODE_MAX_HEIGHT = 2.6
        private const val ATTACK_BITE_MIN_RISE = 1.3
        private const val GROUND_PROBE_BLOCKS = 4
        private const val VINE_BITE_REACH = 1.2
        /** The deer may stand on a slope or step next to the ground under the tip. */
        private const val VINE_BITE_RISE_SLACK = 0.3
        private const val VINE_CHEW_TICKS = 50
        private const val PIN_EASE_TICKS = 4.0

        // ShilleagkDeerModel rest pose: HEAD pivot on the body, JustHead pivot on HEAD, jaw tip on JustHead.
        private const val PX_PER_BLOCK = 16.0
        private const val NECK_FORWARD_PX = 6.0
        private const val NECK_UP_PX = 18.0
        private const val HEAD_FORWARD_PX = 1.0
        private const val HEAD_UP_PX = 8.0
        private const val MOUTH_FORWARD_PX = 5.5
        private const val MOUTH_UP_PX = 1.0

        private val LOG = LogUtils.getLogger()
        private const val LEVEL_EVENT_BLOCK_BREAK = 2001

        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 16.0)
            .add(Attributes.MOVEMENT_SPEED, BASE_SPEED)
            .add(Attributes.FOLLOW_RANGE, 24.0)
    }
}
