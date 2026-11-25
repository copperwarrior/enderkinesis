package org.shipwrights.enderkinesis.entity

import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.sqrt
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.Difficulty
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.AnimationState
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.FollowParentGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.TargetGoal
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.ai.targeting.TargetingConditions
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.WaterAnimal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.InstrumentItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.BlockPathTypes
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds

/** Wohlon predator of the Shilleagk deer. It hunts only when hungry, eats and rests after a kill, and keeps a home range. */
class BlessedGoatEntity(type: EntityType<out BlessedGoatEntity>, level: Level) : Animal(type, level) {

    enum class Action { NONE, CHARGE, FEED, BITE }

    val idleStampAnimationState: AnimationState = AnimationState()
    val idleHeadShakeAnimationState: AnimationState = AnimationState()
    val idleEarFlickAnimationState: AnimationState = AnimationState()
    val feedAnimationState: AnimationState = AnimationState()

    private var clientAction: Action = Action.NONE
    private var clientIdle: AnimationState? = null
    private var clientIdleEndTick: Int = -1
    private var clientFeedRestartTick: Int = -1

    // Staggered so goats that spawn together do not all get hungry on the same tick.
    private var satietyTicks: Int = random.nextInt(SATED_TICKS)
    private var feedTicks: Int = 0
    private var restTicks: Int = 0
    private var huntCooldown: Int = 0
    private var stamina: Int = MAX_STAMINA
    private var endurance: Int = MAX_ENDURANCE
    private var pursuing: Boolean = false
    private var eatingKill: Boolean = false
    // Where the kill was; NaN when the meal has no spot (a hand-fed snack, or after a reload).
    private var mealX: Double = Double.NaN
    private var mealY: Double = 0.0
    private var mealZ: Double = 0.0
    private var mealPausedTick: Long = -1L
    private var nextBiteTick: Int = 0
    private var biteEndTick: Int = 0
    private var longJumpTicks: Int = 0
    private var longJumpCooldown: Int = 0
    private var huntJumpCooldown: Int = 0
    private val jumpCandidates = LongArray(JUMP_CANDIDATES)
    private val jumpCandidateScores = DoubleArray(JUMP_CANDIDATES)
    private val jumpScratch = BlockPos.MutableBlockPos()

    private val isLongJumping: Boolean get() = longJumpTicks > 0
    private val hornCheckPos = BlockPos.MutableBlockPos()

    val action: Action
        get() = Action.entries.getOrElse(entityData.get(DATA_ACTION).toInt()) { Action.NONE }

    private fun setAction(next: Action) = entityData.set(DATA_ACTION, next.ordinal.toByte())

    val isHungry: Boolean get() = satietyTicks < HUNT_HUNGER_TICKS

    // A target while a meal is pending means the meal is paused for a fight.
    private val isEating: Boolean get() = feedTicks > 0 && target == null

    // Satiety keeps counting down past zero, so the depth below zero is how long the goat has been hungry.
    private val isFairlyHungry: Boolean get() = satietyTicks <= -FAIRLY_HUNGRY_TICKS

    fun hasLeftHorn(): Boolean = entityData.get(DATA_LEFT_HORN)

    fun hasRightHorn(): Boolean = entityData.get(DATA_RIGHT_HORN)

    init {
        installWogorLeavesControls(this) { move, jump ->
            moveControl = move
            jumpControl = jump
        }
        // Vanilla gives WATER and WATER_BORDER a malus of 8, so in a swamp every path detours or runs out of nodes.
        setPathfindingMalus(BlockPathTypes.WATER, WATER_MALUS)
        setPathfindingMalus(BlockPathTypes.WATER_BORDER, WATER_BORDER_MALUS)
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_ACTION, Action.NONE.ordinal.toByte())
        entityData.define(DATA_LEFT_HORN, true)
        entityData.define(DATA_RIGHT_HORN, true)
    }

    override fun createNavigation(level: Level): PathNavigation = wogorLeavesNavigation(this, level)

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, FeedGoal())
        goalSelector.addGoal(2, ChargeGoal())
        goalSelector.addGoal(2, BiteGoal())
        goalSelector.addGoal(3, RestGoal())
        goalSelector.addGoal(5, FollowParentGoal(this, FOLLOW_PARENT_SPEED))
        goalSelector.addGoal(6, MoveTowardsRestrictionGoal(this, 1.0))
        goalSelector.addGoal(7, WanderLongJumpGoal())
        goalSelector.addGoal(7, WaterAvoidingRandomStrollGoal(this, 1.0))
        goalSelector.addGoal(8, LookAtPlayerGoal(this, Player::class.java, LOOK_DISTANCE))
        goalSelector.addGoal(9, RandomLookAroundGoal(this))
        targetSelector.addGoal(1, RetaliateGoal())
        targetSelector.addGoal(2, HuntPreyGoal())
    }

    override fun getAmbientSound(): SoundEvent =
        (if (isFairlyHungry || target != null) EKSounds.BLESSED_GOAT_GROWL else EKSounds.BLESSED_GOAT_AMBIENT).get()

    override fun getHurtSound(source: DamageSource): SoundEvent = EKSounds.BLESSED_GOAT_HURT.get()

    override fun getDeathSound(): SoundEvent = EKSounds.BLESSED_GOAT_DEATH.get()

    override fun playStepSound(pos: BlockPos, state: BlockState) = playSound(EKSounds.BLESSED_GOAT_STEP.get(), STEP_VOLUME, 1f)

    // Vanilla Goat: long jumps drop it several blocks.
    override fun calculateFallDamage(fallDistance: Float, multiplier: Float): Int =
        super.calculateFallDamage(fallDistance, multiplier) - FALL_DAMAGE_REDUCTION

    // Hand feeding is in mobInteract; a vanilla food item would start love mode.
    override fun isFood(stack: ItemStack): Boolean = false

    override fun getBreedOffspring(level: ServerLevel, otherParent: AgeableMob): AgeableMob? = null

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (!stack.`is`(HAND_FEED_MEATS)) return super.mobInteract(player, hand)
        if (level().isClientSide) return InteractionResult.SUCCESS
        if (satietyTicks >= SATED_TICKS && health >= maxHealth) return InteractionResult.PASS
        player.useOneHeld(hand)
        satietyTicks = (maxOf(satietyTicks, 0) + HAND_FED_SATIETY_TICKS).coerceAtMost(SATED_TICKS)
        heal(HAND_FED_HEAL)
        playSound(EKSounds.BLESSED_GOAT_EAT.get(), 1f, 1f)
        if (feedTicks <= 0) feedTicks = HAND_FEED_TICKS
        return InteractionResult.CONSUME
    }

    // Vanilla Goat.ageBoundaryReached: kids have no horns and grow both as adults.
    override fun ageBoundaryReached() {
        super.ageBoundaryReached()
        entityData.set(DATA_LEFT_HORN, !isBaby)
        entityData.set(DATA_RIGHT_HORN, !isBaby)
    }

    // Vanilla caps the swing speed at 1 (0.25 blocks/tick), which the charge passes.
    override fun updateWalkAnimation(distance: Float) {
        walkAnimation.update(minOf(distance * LIMB_SWING_PER_BLOCK, MAX_LIMB_SWING_SPEED), LIMB_SWING_SMOOTHING)
    }

    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(key)
        if (key == DATA_ACTION && level().isClientSide) onClientActionChanged()
    }

    override fun aiStep() {
        super.aiStep()
        if (level().isClientSide) {
            tickClientAnimations()
            return
        }
        // Mob.restrictTo is not saved, and spawn eggs and conversions skip finalizeSpawn.
        if (!hasRestriction()) restrictTo(blockPosition(), HOME_RANGE)
        if (satietyTicks > -FAIRLY_HUNGRY_TICKS) {
            satietyTicks--
        }
        if (action == Action.BITE && tickCount >= biteEndTick) setAction(Action.NONE)
        if (longJumpCooldown > 0) longJumpCooldown--
        if (huntJumpCooldown > 0) huntJumpCooldown--
        if (isLongJumping) tickLongJump()
        tickBankHop()
        if (restTicks > 0) restTicks--
        if (huntCooldown > 0) huntCooldown--
        if (action != Action.CHARGE && stamina < MAX_STAMINA && tickCount % STAMINA_REGEN_INTERVAL == 0) stamina++
        if (!pursuing && endurance < MAX_ENDURANCE) endurance = minOf(MAX_ENDURANCE, endurance + ENDURANCE_REGEN_PER_TICK)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putInt(TAG_SATIETY, satietyTicks)
        tag.putInt(TAG_FEED, feedTicks)
        tag.putInt(TAG_REST, restTicks)
        tag.putInt(TAG_HUNT_COOLDOWN, huntCooldown)
        tag.putInt(TAG_STAMINA, stamina)
        tag.putInt(TAG_ENDURANCE, endurance)
        tag.putBoolean(TAG_EATING_KILL, eatingKill)
        tag.putBoolean(TAG_LEFT_HORN, hasLeftHorn())
        tag.putBoolean(TAG_RIGHT_HORN, hasRightHorn())
        if (hasRestriction()) tag.put(TAG_HOME, NbtUtils.writeBlockPos(restrictCenter))
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        satietyTicks = tag.getInt(TAG_SATIETY)
        feedTicks = tag.getInt(TAG_FEED)
        restTicks = tag.getInt(TAG_REST)
        huntCooldown = tag.getInt(TAG_HUNT_COOLDOWN)
        stamina = tag.getInt(TAG_STAMINA)
        endurance = tag.getInt(TAG_ENDURANCE)
        eatingKill = tag.getBoolean(TAG_EATING_KILL)
        entityData.set(DATA_LEFT_HORN, tag.getBoolean(TAG_LEFT_HORN))
        entityData.set(DATA_RIGHT_HORN, tag.getBoolean(TAG_RIGHT_HORN))
        if (tag.contains(TAG_HOME)) restrictTo(NbtUtils.readBlockPos(tag.getCompound(TAG_HOME)), HOME_RANGE)
    }

    private fun onClientActionChanged() {
        val previous = clientAction
        clientAction = action
        stopClientIdle()
        if (previous == Action.FEED || previous == Action.BITE) {
            feedAnimationState.stop()
            clientFeedRestartTick = -1
        }
        if (clientAction == Action.BITE) feedAnimationState.start(tickCount)
    }

    private fun tickClientAnimations() {
        // The Bite clip is exported play-once, so feeding restarts it to loop.
        if (action == Action.FEED && (!feedAnimationState.isStarted || tickCount >= clientFeedRestartTick)) {
            feedAnimationState.start(tickCount)
            clientFeedRestartTick = tickCount + BITE_TICKS
            playLocalSound(EKSounds.BLESSED_GOAT_EAT.get())
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
            0 -> idleStampAnimationState to IDLE_STAMP_TICKS
            1 -> idleHeadShakeAnimationState to IDLE_HEAD_SHAKE_TICKS
            else -> idleEarFlickAnimationState to IDLE_EAR_FLICK_TICKS
        }
        state.start(tickCount)
        when (state) {
            idleStampAnimationState -> playLocalSound(EKSounds.BLESSED_GOAT_STAMP.get())
            idleHeadShakeAnimationState -> playLocalSound(EKSounds.BLESSED_GOAT_HEAD_SHAKE.get(), voicePitch)
        }
        clientIdle = state
        clientIdleEndTick = tickCount + ticks
    }

    private fun playLocalSound(sound: SoundEvent, pitch: Float = 1f) {
        if (!isSilent) level().playLocalSound(x, y, z, sound, SoundSource.NEUTRAL, 1f, pitch, false)
    }

    private fun stopClientIdle() {
        clientIdle?.stop()
        clientIdle = null
        clientIdleEndTick = -1
    }

    private fun isPrey(entity: LivingEntity): Boolean = entity.type.`is`(PREY)

    // A Moskuo or a fish is too small and quick to ram, so its attack gets a bite back.
    private fun isBiteTarget(entity: LivingEntity?): Boolean = entity is MoskuoEntity || isReachOnlyTarget(entity)

    // A fish is bitten only while it is in reach, so a fish bite never starts a chase into the water.
    private fun isReachOnlyTarget(entity: LivingEntity?): Boolean = entity is WaterAnimal

    private fun mayRetaliateAt(entity: LivingEntity?): Boolean =
        entity == null || !isReachOnlyTarget(entity) || inBiteReach(entity)

    private fun startFeeding(prey: LivingEntity) {
        feedTicks = FEED_TICKS
        eatingKill = true
        huntCooldown = 0
        mealX = prey.x
        mealY = prey.y
        mealZ = prey.z
        mealPausedTick = -1L
    }

    // A hand-fed snack gave its satiety when the player fed it.
    private fun finishMeal(fullMeal: Boolean) {
        feedTicks = 0
        mealX = Double.NaN
        mealPausedTick = -1L
        if (!eatingKill) return
        eatingKill = false
        satietyTicks = (maxOf(satietyTicks, 0) + KILL_SATIETY_TICKS).coerceAtMost(SATED_TICKS)
        restTicks = if (fullMeal) REST_TICKS else 0
        if (fullMeal) heal(maxHealth)
    }

    // An attack ends the meal at once so the goat can answer it; the belly is full either way.
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        val hurt = super.hurt(source, amount)
        if (!hurt || level().isClientSide) return hurt
        if (source.entity is ShilleagkDeerEntity) takeStrikeCost()
        return hurt
    }

    // A landed deer strike is an injury: the goat slows and loses wind, so fighting a deer that stands has a price.
    private fun takeStrikeCost() {
        addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, STRIKE_SLOW_TICKS, STRIKE_SLOW_AMPLIFIER, false, false))
        stamina = maxOf(0, stamina - STRIKE_STAMINA_COST)
        endurance = maxOf(0, endurance - STRIKE_ENDURANCE_COST)
    }

    /** Lower is better: near, weak, injured or young prey first. */
    private fun preyScore(prey: LivingEntity): Double {
        val healthFraction = prey.health / prey.maxHealth
        val young = if (prey.isBaby) YOUNG_PREY_BONUS else 0.0
        return distanceTo(prey) + PREY_HEALTH_WEIGHT * healthFraction - young
    }

    private fun isEasyPrey(prey: LivingEntity): Boolean =
        prey.isBaby || prey.health < prey.maxHealth * EASY_PREY_HEALTH_FRACTION || (prey as? ShilleagkDeerEntity)?.isTired == true

    // Position delta, not deltaMovement: the server does not keep a player's deltaMovement.
    // A deer reports its own flight, as its move this tick may still be ahead of the goat's goals.
    private fun isFleeing(victim: LivingEntity): Boolean {
        if (victim is ShilleagkDeerEntity) return victim.isFleeing
        val dx = victim.x - victim.xo
        val dz = victim.z - victim.zo
        return dx * dx + dz * dz > FLEEING_SPEED * FLEEING_SPEED
    }

    private fun mayHuntPlayers(): Boolean = isFairlyHungry && level().difficulty != Difficulty.PEACEFUL

    // Vanilla RamTarget.hasRammedHornBreakingBlock, but along the facing: a collision zeroes the blocked velocity axis.
    private fun hasRammedHornBreakingBlock(): Boolean {
        val yaw = yRot * Mth.DEG_TO_RAD
        hornCheckPos.set(x - Mth.sin(yaw), y, z + Mth.cos(yaw))
        if (level().getBlockState(hornCheckPos).`is`(BlockTags.SNAPS_GOAT_HORN)) return true
        return level().getBlockState(hornCheckPos.move(0, 1, 0)).`is`(BlockTags.SNAPS_GOAT_HORN)
    }

    /** Vanilla Goat.dropHorn: one random remaining horn, with a random instrument from the tag. */
    private fun dropHorn(): Boolean {
        val left = hasLeftHorn()
        val right = hasRightHorn()
        if (!left && !right) return false
        val dropLeft = if (left && right) random.nextBoolean() else left
        entityData.set(if (dropLeft) DATA_LEFT_HORN else DATA_RIGHT_HORN, false)
        val instrument = BuiltInRegistries.INSTRUMENT.getTag(EKItems.BLESSED_GOAT_HORNS_TAG)
            .flatMap { it.getRandomElement(random) }
        val horn = instrument.map { InstrumentItem.create(EKItems.BLESSED_GOAT_HORN.get(), it) }
            .orElseGet { ItemStack(EKItems.BLESSED_GOAT_HORN.get()) }
        val dx = Mth.randomBetween(random, -HORN_TOSS, HORN_TOSS).toDouble()
        val dz = Mth.randomBetween(random, -HORN_TOSS, HORN_TOSS).toDouble()
        level().addFreshEntity(ItemEntity(level(), x, y, z, horn, dx, HORN_TOSS_UP, dz))
        return true
    }

    /** Plays the Bite clip and hits; true when the bite killed prey and the goat now eats it whole. */
    private fun bite(victim: LivingEntity): Boolean {
        nextBiteTick = tickCount + BITE_COOLDOWN_TICKS
        biteEndTick = tickCount + BITE_TICKS
        setAction(Action.BITE)
        playSound(EKSounds.BLESSED_GOAT_BITE.get(), 1f, voicePitch)
        val killingBite = isPrey(victim) && victim.health <= getAttributeValue(Attributes.ATTACK_DAMAGE)
        if (killingBite && victim.eatWhole(this)) {
            startFeeding(victim)
            target = null
            return true
        }
        // Return value ignored: a blocked bite still costs the cooldown.
        doHurtTarget(victim)
        return false
    }

    // Mob.isWithinMeleeAttackRange stops at the goat's own height, so a Moskuo just above the head is out of reach.
    private fun inBiteReach(victim: LivingEntity): Boolean {
        val mine = boundingBox
        val other = victim.boundingBox
        return other.maxX > mine.minX - BITE_REACH && other.minX < mine.maxX + BITE_REACH &&
            other.maxZ > mine.minZ - BITE_REACH && other.minZ < mine.maxZ + BITE_REACH &&
            other.maxY > mine.minY && other.minY < mine.maxY + BITE_REACH_UP
    }

    /** Paths to [victim]; a missing, partial, long or wet path tries a long jump. False when it can neither walk nor jump. */
    private fun approach(victim: LivingEntity, speed: Double): Boolean {
        // PathNavigation makes no path while the mob is in the air, so a goat knocked into the air waits to land.
        if (!onGround() && !isInWater && !isInLava) return true
        val walks = navigation.moveTo(victim, speed)
        val path = navigation.path
        // moveTo is also false for a partial path trimmed to no nodes, as when the prey stands on a ledge beside the goat.
        val pathIsGood = if (walks) !hasPoorPath() else path != null && path.canReach()
        if (pathIsGood) return true
        // During the cooldown a goat with no path waits for the next jump search and does not give up.
        if (huntJumpCooldown > 0) return true
        if (huntLongJump(victim)) return true
        return walks
    }

    private fun hasPoorPath(): Boolean {
        val path = navigation.path ?: return true
        if (!path.canReach()) return true
        if (path.nodeCount > path.target.distManhattan(blockPosition()) * DETOUR_FACTOR + DETOUR_SLACK) return true
        // Wading is normal in the swamp; only a swim in deep water away from the prey counts.
        val pathEnd = path.target
        for (i in 0 until path.nodeCount) {
            val node = path.getNode(i)
            if (node.type != BlockPathTypes.WATER || !isDeepWater(node.x, node.y, node.z)) continue
            if (abs(node.x - pathEnd.x) + abs(node.z - pathEnd.z) > WADE_RANGE) return true
        }
        return false
    }

    private fun isDeepWater(x: Int, y: Int, z: Int): Boolean {
        for (depth in 1..DEEP_WATER_DEPTH) {
            if (!level().getFluidState(jumpScratch.set(x, y - depth, z)).`is`(FluidTags.WATER)) return false
        }
        return true
    }

    // In water vanilla swaps the jump for a slow swim-up, so a goat that walks on the bottom stalls at a bank.
    private fun tickBankHop() {
        if (!isInWater || !onGround() || !horizontalCollision || isLongJumping) return
        val path = navigation.path ?: return
        if (path.isDone || path.nextNode.y <= blockPosition().y) return
        deltaMovement = deltaMovement.with(Direction.Axis.Y, BANK_HOP_SPEED)
    }

    private fun canStartLongJump(): Boolean = onGround() && !isInWater && !isInLava && !isLongJumping && !isPassenger

    /** Jumps to the walkable spot in reach that is nearest to [victim], if it is nearer than the goat is now; true when it jumps. */
    private fun huntLongJump(victim: LivingEntity): Boolean {
        if (isInWater || !canStartLongJump()) return false
        val progress = distanceTo(victim) - JUMP_MIN_PROGRESS
        if (progress <= 0.0) return false
        huntJumpCooldown = HUNT_JUMP_COOLDOWN_TICKS
        val count = collectHuntLandings(victim, progress * progress)
        for (i in 0 until count) {
            if (launchLongJump(jumpScratch.set(jumpCandidates[i]))) return true
        }
        return false
    }

    // A fixed-size best list, so the scan does not allocate.
    private fun collectHuntLandings(victim: LivingEntity, maxScore: Double): Int {
        val origin = blockPosition()
        var count = 0
        for (dx in -JUMP_MAX_HORIZONTAL..JUMP_MAX_HORIZONTAL) for (dz in -JUMP_MAX_HORIZONTAL..JUMP_MAX_HORIZONTAL) {
            val horizontalSq = dx * dx + dz * dz
            if (horizontalSq > JUMP_MAX_HORIZONTAL * JUMP_MAX_HORIZONTAL || horizontalSq < JUMP_MIN_HORIZONTAL_SQ) continue
            for (dy in JUMP_MAX_UP downTo -JUMP_MAX_DOWN) {
                jumpScratch.set(origin.x + dx, origin.y + dy, origin.z + dz)
                val score = victim.distanceToSqr(jumpScratch.x + 0.5, jumpScratch.y.toDouble(), jumpScratch.z + 0.5)
                if (score >= maxScore || (count == JUMP_CANDIDATES && score >= jumpCandidateScores[count - 1])) continue
                if (!isLandingSpot(jumpScratch, HUNT_LEASH * HUNT_LEASH)) continue
                count = insertCandidate(count, jumpScratch.asLong(), score)
            }
        }
        return count
    }

    private fun insertCandidate(count: Int, pos: Long, score: Double): Int {
        var i = minOf(count, JUMP_CANDIDATES - 1)
        while (i > 0 && jumpCandidateScores[i - 1] > score) {
            jumpCandidates[i] = jumpCandidates[i - 1]
            jumpCandidateScores[i] = jumpCandidateScores[i - 1]
            i--
        }
        jumpCandidates[i] = pos
        jumpCandidateScores[i] = score
        return minOf(count + 1, JUMP_CANDIDATES)
    }

    /** Vanilla Goat long jump while it wanders: a random walkable spot inside the home range. */
    private fun wanderLongJump(): Boolean {
        val origin = blockPosition()
        val homeSq = restrictRadius.toDouble() * restrictRadius
        for (i in 0 until WANDER_JUMP_TRIES) {
            val dx = random.nextIntBetweenInclusive(-JUMP_MAX_HORIZONTAL, JUMP_MAX_HORIZONTAL)
            val dz = random.nextIntBetweenInclusive(-JUMP_MAX_HORIZONTAL, JUMP_MAX_HORIZONTAL)
            val horizontalSq = dx * dx + dz * dz
            if (horizontalSq > JUMP_MAX_HORIZONTAL * JUMP_MAX_HORIZONTAL || horizontalSq < JUMP_MIN_HORIZONTAL_SQ) continue
            jumpScratch.set(origin.x + dx, origin.y + random.nextIntBetweenInclusive(-JUMP_MAX_DOWN, JUMP_MAX_UP), origin.z + dz)
            if (isLandingSpot(jumpScratch, homeSq) && launchLongJump(jumpScratch)) return true
        }
        return false
    }

    // The navigation's evaluator reads Wogor leaves as air, so the goat never aims to stand on leaves it falls through.
    private fun isLandingSpot(pos: BlockPos, homeSq: Double): Boolean {
        if (hasRestriction() && pos.distSqr(restrictCenter) > homeSq) return false
        return navigation.nodeEvaluator.getBlockPathType(level(), pos.x, pos.y, pos.z) == BlockPathTypes.WALKABLE
    }

    private fun launchLongJump(landing: BlockPos): Boolean {
        val velocity = longJumpVelocity(landing.x + 0.5, landing.y + 0.5, landing.z + 0.5, JUMP_MAX_VELOCITY) ?: return false
        navigation.stop()
        val yaw = (Mth.atan2(velocity.z, velocity.x) * Mth.RAD_TO_DEG).toFloat() - 90f
        yRot = yaw
        yBodyRot = yaw
        yHeadRot = yaw
        setDiscardFriction(true)
        deltaMovement = velocity
        hasImpulse = true
        longJumpTicks = 1
        playSound(EKSounds.BLESSED_GOAT_JUMP.get(), 1f, voicePitch)
        return true
    }

    private fun tickLongJump() {
        longJumpTicks++
        if ((onGround() && longJumpTicks > JUMP_LIFTOFF_TICKS) || isInWater || isInLava || longJumpTicks > JUMP_MAX_AIR_TICKS) {
            longJumpTicks = 0
            setDiscardFriction(false)
        }
    }

    // mustSee false: out of sight, ChargeGoal tracks a deer by its trail and decides when to give up.
    /** Sets the hunt target; the chase itself is [ChargeGoal]. */
    private inner class HuntPreyGoal : TargetGoal(this@BlessedGoatEntity, false) {
        private var prey: LivingEntity? = null

        // TargetGoal sets no flags; without TARGET this goal can replace the target that HurtByTargetGoal set.
        init {
            flags = EnumSet.of(Flag.TARGET)
        }

        override fun canUse(): Boolean {
            if (isBaby || feedTicks > 0 || restTicks > 0 || huntCooldown > 0) return false
            if (!isHungry && satietyTicks >= OPPORTUNITY_SATIETY_TICKS) return false
            if (endurance < HUNT_START_ENDURANCE || health < maxHealth * RETREAT_HEALTH_FRACTION) return false
            if (random.nextInt(reducedTickDelay(HUNT_SCAN_INTERVAL)) != 0) return false
            val nearby = level().getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(HUNT_RANGE, HUNT_HEIGHT, HUNT_RANGE)) {
                it.isAlive && isPrey(it)
            }
            // Where deer are scarce only real hunger sends the goat out, so the last deer get a chance to breed.
            val scarce = nearby.count { it is ShilleagkDeerEntity } < SCARCE_DEER_COUNT
            val hungry = if (scarce) satietyTicks <= 0 else isHungry
            if (!hungry && scarce) return false
            prey = nearby.filter {
                (hungry || (isEasyPrey(it) && distanceTo(it) <= OPPORTUNITY_RANGE)) && hasLineOfSight(it)
            }.minByOrNull(::preyScore)
            if (prey == null) prey = nearestPlayerToHunt()
            return prey != null
        }

        override fun canContinueToUse(): Boolean =
            super.canContinueToUse() && (mob.target !is Player || mayHuntPlayers())

        override fun getFollowDistance(): Double = GIVE_UP_DISTANCE

        // Deer come first; a player is food only when no deer is in sight.
        private fun nearestPlayerToHunt(): Player? =
            if (mayHuntPlayers()) level().getNearestPlayer(PLAYER_HUNT_CONDITIONS, this@BlessedGoatEntity) else null

        override fun start() {
            mob.target = prey
            super.start()
        }

        override fun stop() {
            super.stop()
            prey = null
        }
    }

    private enum class Pursuit { STALK, TROT, CHARGE }

    /** Stalks, then trots after the prey until it tires, and charges only to close in; out of sight it follows the deer's trail. */
    private inner class ChargeGoal : Goal() {
        private var chased: LivingEntity? = null
        private var ticks = 0
        private var unseenTicks = 0
        private var pursuit = Pursuit.STALK
        private var pathLost = false
        private var recoverUntil = 0
        private var repathDue = false
        private var lastProgressTick = 0
        private var progressDistance = 0.0
        private var progressPreyStamina = 0
        private var chargeTravel = 0.0
        private var lastX = 0.0
        private var lastZ = 0.0
        private var step = 0.0
        private var previousStep = 0.0
        private var refugeTicks = 0
        private var rolledClose = false
        private var missedClose = false
        private val trailPos = BlockPos.MutableBlockPos()

        // No JUMP flag: FloatGoal holds JUMP, so a shared flag let every wade end the chase.
        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean = target?.isAlive == true && !isBiteTarget(target) && !isEating

        override fun canContinueToUse(): Boolean {
            val victim = target ?: return false
            if (!victim.isAlive || isEating || isBiteTarget(victim)) return false
            return !shouldGiveUp(victim)
        }

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            val victim = target
            chased = victim
            ticks = 0
            unseenTicks = 0
            pursuit = Pursuit.STALK
            pathLost = false
            recoverUntil = 0
            repathDue = false
            refugeTicks = 0
            rolledClose = false
            missedClose = false
            pursuing = true
            if (victim != null) markProgress(victim)
        }

        override fun tick() {
            val victim = target ?: return
            ticks++
            if (pursuit != Pursuit.STALK) endurance--
            if (pursuit == Pursuit.CHARGE) {
                stamina--
                trackChargeTravel()
            }
            val seen = sensing.hasLineOfSight(victim)
            unseenTicks = if (seen) 0 else unseenTicks + 1
            if (seen) lookControl.setLookAt(victim, LOOK_TURN, LOOK_TURN)
            trackProgress(victim)
            if (isLongJumping || tickCount < recoverUntil) return
            val distance = distanceTo(victim)
            if (pursuit == Pursuit.STALK && shouldEngage(victim, distance)) pursuit = Pursuit.TROT
            if (pursuit == Pursuit.TROT && wantsCharge(victim, distance)) {
                if (!rollClose(victim)) return
                startCharge(victim)
            } else if (pursuit == Pursuit.CHARGE) {
                if (chargeEnds(victim, distance)) endCharge()
            }
            if (waitsAtRefuge(victim, distance)) return
            if ((ticks % REPATH_TICKS == 1 || repathDue) && !move(victim)) pathLost = true
            repathDue = false
            if (isLongJumping) return
            if (pursuit == Pursuit.CHARGE && horizontalCollision && isDistanceCharge() && hasRammedHornBreakingBlock()) {
                ramBlock()
                return
            }
            if (pursuit == Pursuit.STALK || tickCount < nextBiteTick || !inBiteReach(victim)) return
            if (pursuit == Pursuit.CHARGE) endCharge()
            if (!bite(victim)) markProgress(victim)
        }

        // Most real hunts fail; one roll at the first close on a deer decides whether this one can.
        private fun rollClose(victim: LivingEntity): Boolean {
            if (victim !is ShilleagkDeerEntity || rolledClose) return true
            rolledClose = true
            missedClose = random.nextFloat() >= HUNT_SUCCESS_CHANCE
            return !missedClose
        }

        // The goat does not swim after a deer in deep water; it waits on the bank unless the deer is in reach.
        private fun waitsAtRefuge(victim: LivingEntity, distance: Float): Boolean {
            if (victim !is ShilleagkDeerEntity || distance <= REFUGE_REACH || !victim.isInRefuge) {
                refugeTicks = 0
                return false
            }
            refugeTicks++
            if (!isInWater) return false
            if (pursuit == Pursuit.CHARGE) endCharge()
            navigation.stop()
            return true
        }

        // Out of sight, a deer is followed along the way it went, not straight to where it is now.
        private fun move(victim: LivingEntity): Boolean {
            val speed = chaseSpeed()
            if (unseenTicks <= TRACK_AFTER_UNSEEN_TICKS || victim !is ShilleagkDeerEntity) return approach(victim, speed)
            victim.trailPoint(SCENT_LAG_SAMPLES, trailPos)
            if (trailPos.distToCenterSqr(x, y, z) < TRAIL_REACHED * TRAIL_REACHED) victim.trailPoint(0, trailPos)
            if (navigation.moveTo(trailPos.x + 0.5, trailPos.y.toDouble(), trailPos.z + 0.5, speed)) return true
            return approach(victim, speed)
        }

        private fun chaseSpeed(): Double = when (pursuit) {
            Pursuit.CHARGE -> CHARGE_SPEED
            Pursuit.TROT -> TROT_SPEED
            Pursuit.STALK -> STALK_SPEED
        }

        private fun wantsCharge(victim: LivingEntity, distance: Float): Boolean {
            if (stamina < CHARGE_MIN_STAMINA) return false
            if (victim !is ShilleagkDeerEntity) return distance > CLOSE_RANGE || isFleeing(victim)
            val range = if (victim.isTired) CHARGE_RANGE else FRESH_CHARGE_RANGE
            return distance <= range && (distance > CLOSE_RANGE || isFleeing(victim))
        }

        private fun chargeEnds(victim: LivingEntity, distance: Float): Boolean =
            stamina <= 0 || (victim is ShilleagkDeerEntity && distance > CHARGE_BREAK_RANGE)

        private fun startCharge(victim: LivingEntity) {
            pursuit = Pursuit.CHARGE
            setAction(Action.CHARGE)
            playSound(EKSounds.BLESSED_GOAT_CHARGE.get(), 1f, voicePitch)
            chargeTravel = 0.0
            lastX = x
            lastZ = z
            step = 0.0
            previousStep = 0.0
            // Return value ignored: a lost path shows on the next repath.
            navigation.moveTo(victim, CHARGE_SPEED)
        }

        private fun endCharge() {
            pursuit = Pursuit.TROT
            if (action == Action.CHARGE) setAction(Action.NONE)
            repathDue = true
        }

        // Goals run before this tick's move, so the step measured now is the last tick's move, the one that hit.
        private fun trackChargeTravel() {
            val dx = x - lastX
            val dz = z - lastZ
            lastX = x
            lastZ = z
            previousStep = step
            step = sqrt(dx * dx + dz * dz)
            chargeTravel += step
        }

        // A collision cuts the hitting step short, so the step before it also counts as the impact speed.
        private fun isDistanceCharge(): Boolean =
            chargeTravel >= HORN_SNAP_CHARGE_DISTANCE && maxOf(step, previousStep) >= HORN_SNAP_MIN_SPEED

        // Vanilla RamTarget ends the ram on a horn-breaking block; here only the charge ends, and the hunt goes on.
        private fun ramBlock() {
            playSound(EKSounds.BLESSED_GOAT_RAM_IMPACT.get(), 1f, 1f)
            if (dropHorn()) level().playSound(null, this@BlessedGoatEntity, EKSounds.BLESSED_GOAT_HORN_BREAK.get(), SoundSource.NEUTRAL, 1f, 1f)
            endCharge()
            navigation.stop()
            recoverUntil = tickCount + HORN_SNAP_RECOVER_TICKS
        }

        override fun stop() {
            val victim = chased
            chased = null
            pursuing = false
            if (action == Action.CHARGE) setAction(Action.NONE)
            navigation.stop()
            if (!isEating) {
                if (victim != null && isPrey(victim)) huntCooldown = FAILED_HUNT_COOLDOWN
                // A Moskuo sting during the chase leaves its target for BiteGoal.
                if (!isBiteTarget(target)) target = null
            }
        }

        // Closing in and wearing the deer down both count as progress.
        private fun trackProgress(victim: LivingEntity) {
            val closer = distanceTo(victim) < progressDistance - PROGRESS_DISTANCE_STEP
            val wearing = victim is ShilleagkDeerEntity && victim.sprintStamina < progressPreyStamina - PROGRESS_STAMINA_STEP
            if (closer || wearing) markProgress(victim)
        }

        private fun markProgress(victim: LivingEntity) {
            lastProgressTick = ticks
            progressDistance = distanceTo(victim).toDouble()
            progressPreyStamina = (victim as? ShilleagkDeerEntity)?.sprintStamina ?: 0
        }

        private fun shouldEngage(victim: LivingEntity, distance: Float): Boolean =
            !isPrey(victim) || ticks >= STALK_MAX_TICKS || distance <= CHARGE_RANGE || isFleeing(victim) ||
                victim.lastHurtByMob === this@BlessedGoatEntity

        private fun shouldGiveUp(victim: LivingEntity): Boolean =
            pathLost || missedClose || refugeTicks >= REFUGE_GIVE_UP_TICKS || endurance <= 0 ||
                ticks - lastProgressTick >= NO_PROGRESS_TICKS ||
                (victim !is ShilleagkDeerEntity && unseenTicks >= LOST_SIGHT_TICKS) ||
                distanceTo(victim) > GIVE_UP_DISTANCE ||
                (hasRestriction() && victim.blockPosition().distSqr(restrictCenter) > HUNT_LEASH * HUNT_LEASH) ||
                (isPrey(victim) && health < maxHealth * RETREAT_HEALTH_FRACTION)
    }

    /** Drops a fish attacker as soon as it is out of bite reach; TargetGoal would otherwise keep setting it again. */
    private inner class RetaliateGoal : HurtByTargetGoal(this@BlessedGoatEntity) {
        override fun canUse(): Boolean = super.canUse() && mayRetaliateAt(mob.lastHurtByMob)

        override fun canContinueToUse(): Boolean = super.canContinueToUse() && mayRetaliateAt(mob.target)
    }

    /** Bites back at a Moskuo or fish that attacked it; it does not follow a Moskuo far or up, nor a fish at all. */
    private inner class BiteGoal : Goal() {
        private var ticks = 0
        private var pathLost = false

        // No JUMP flag, as in ChargeGoal: FloatGoal must not end the fight in water.
        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean = target?.isAlive == true && isBiteTarget(target) && !isEating

        override fun canContinueToUse(): Boolean {
            val victim = target ?: return false
            if (isReachOnlyTarget(victim)) return victim.isAlive && !isEating && inBiteReach(victim)
            return victim.isAlive && isBiteTarget(victim) && !isEating && !pathLost && ticks < BITE_CHASE_TICKS &&
                distanceToSqr(victim) < BITE_CHASE_RANGE * BITE_CHASE_RANGE && victim.y - y < BITE_MAX_RISE
        }

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            ticks = 0
            pathLost = false
        }

        override fun tick() {
            val victim = target ?: return
            ticks++
            lookControl.setLookAt(victim, LOOK_TURN, LOOK_TURN)
            if (isLongJumping) return
            if (inBiteReach(victim)) {
                navigation.stop()
                // Return value ignored: a Moskuo is never eaten whole.
                if (tickCount >= nextBiteTick) bite(victim)
            } else if (!isReachOnlyTarget(victim) && ticks % REPATH_TICKS == 1 && !approach(victim, RUN_SPEED)) {
                pathLost = true
            }
        }

        override fun stop() {
            navigation.stop()
            if (isBiteTarget(target)) target = null
        }
    }

    /** Vanilla Goat: now and then a wandering goat long-jumps onto a ledge or rock. */
    private inner class WanderLongJumpGoal : Goal() {
        init {
            flags = EnumSet.of(Flag.MOVE, Flag.JUMP)
        }

        override fun canUse(): Boolean = target == null && longJumpCooldown <= 0 && canStartLongJump()

        override fun canContinueToUse(): Boolean = isLongJumping

        override fun start() {
            longJumpCooldown = random.nextIntBetweenInclusive(WANDER_JUMP_COOLDOWN_MIN, WANDER_JUMP_COOLDOWN_MAX)
            // Return value ignored: with no spot in reach the goal ends and waits out the cooldown.
            wanderLongJump()
        }
    }

    /** Walks to where the kill was, stands over the spot facing it and eats; ends with a full belly, full health and a rest. */
    private inner class FeedGoal : Goal() {
        private var approachTicks = 0

        // No JUMP flag: FloatGoal must not break off a meal at the water's edge.
        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean = isEating

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            approachTicks = 0
            val paused = mealPausedTick
            mealPausedTick = -1L
            if (paused >= 0 && level().gameTime - paused > MEAL_SPOT_KEEP_TICKS) {
                finishMeal(fullMeal = false)
            }
        }

        override fun tick() {
            if (feedTicks <= 0) return
            val hasSpot = !mealX.isNaN()
            if (hasSpot && !inEatReach() && approachTicks < EAT_APPROACH_TICKS) {
                approachTicks++
                // Accuracy 0 so the path ends on the spot, not a block beside it.
                // Return value ignored: past EAT_APPROACH_TICKS the goat eats where it stands.
                if (approachTicks % REPATH_TICKS == 1) {
                    navigation.moveTo(navigation.createPath(BlockPos.containing(mealX, mealY, mealZ), 0), RUN_SPEED)
                }
                return
            }
            if (action != Action.FEED) {
                navigation.stop()
                setAction(Action.FEED)
            }
            if (hasSpot) faceMeal()
            feedTicks--
            if (feedTicks <= 0) finishMeal(fullMeal = true)
        }

        override fun stop() {
            if (action == Action.FEED) setAction(Action.NONE)
            navigation.stop()
            if (feedTicks > 0) mealPausedTick = level().gameTime
        }

        private fun inEatReach(): Boolean {
            val dx = mealX - x
            val dz = mealZ - z
            return dx * dx + dz * dz <= EAT_REACH * EAT_REACH
        }

        // Inside EAT_REACH the goat stands over the spot, so it faces the spot only when it is not right on top of it.
        private fun faceMeal() {
            val dx = mealX - x
            val dz = mealZ - z
            if (dx * dx + dz * dz > FACE_MIN_DISTANCE * FACE_MIN_DISTANCE) {
                val yaw = (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG).toFloat() - 90f
                yRot = yaw
                yBodyRot = yaw
                yHeadRot = yaw
            }
            lookControl.setLookAt(mealX, mealY, mealZ, LOOK_TURN, EAT_HEAD_PITCH_LIMIT)
        }
    }

    /** After a meal the goat stays put; idles still play. */
    private inner class RestGoal : Goal() {
        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean = restTicks > 0 && target == null

        override fun canContinueToUse(): Boolean = canUse()

        override fun start() = navigation.stop()
    }

    companion object {
        const val ID_PATH = "blessed_goat"


        private val DATA_ACTION: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(BlessedGoatEntity::class.java, EntityDataSerializers.BYTE)
        private val DATA_LEFT_HORN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(BlessedGoatEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_RIGHT_HORN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(BlessedGoatEntity::class.java, EntityDataSerializers.BOOLEAN)

        val PREY: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, EnderkinesisMod.id("blessed_goat_prey"))
        private val PLAYER_HUNT_CONDITIONS: TargetingConditions = TargetingConditions.forCombat().range(HUNT_RANGE)

        const val WIDTH = 0.9f
        const val HEIGHT = 1.5f

        private const val TAG_SATIETY = "Satiety"
        private const val TAG_FEED = "Feed"
        private const val TAG_REST = "Rest"
        private const val TAG_HUNT_COOLDOWN = "HuntCooldown"
        private const val TAG_STAMINA = "Stamina"
        private const val TAG_ENDURANCE = "Endurance"
        private const val TAG_HOME = "Home"
        private const val TAG_EATING_KILL = "EatingKill"
        private const val TAG_LEFT_HORN = "HasLeftHorn"
        private const val TAG_RIGHT_HORN = "HasRightHorn"

        private const val BASE_SPEED = 0.25
        private const val STALK_SPEED = 0.8
        private const val RUN_SPEED = 1.2
        // About 0.28 blocks/tick: under a fresh deer's 0.33 sprint, over a tired deer's 0.2.
        private const val TROT_SPEED = 1.45
        private const val CHARGE_SPEED = 2.0
        private const val FOLLOW_PARENT_SPEED = 1.1
        private const val STEP_VOLUME = 0.15f
        private const val LOOK_DISTANCE = 8f
        private const val LOOK_TURN = 30f

        // Mob ground speed settles near 2.16 × (attribute × modifier)² blocks per tick.
        const val WALK_GROUND_SPEED: Float = (2.16 * BASE_SPEED * BASE_SPEED).toFloat()
        const val CHARGE_GROUND_SPEED: Float = (2.16 * BASE_SPEED * CHARGE_SPEED * BASE_SPEED * CHARGE_SPEED).toFloat()
        const val LIMB_SWING_PER_BLOCK = 4f
        private const val MAX_LIMB_SWING_SPEED = CHARGE_GROUND_SPEED * LIMB_SWING_PER_BLOCK
        private const val LIMB_SWING_SMOOTHING = 0.4f

        // Hunger: the goat hunts below half a day of satiety, and a kill gives three quarters of a day.
        private const val SATED_TICKS = 24_000
        private const val HUNT_HUNGER_TICKS = 12_000
        private const val KILL_SATIETY_TICKS = 18_000
        private const val OPPORTUNITY_SATIETY_TICKS = 18_000
        private const val FEED_TICKS = 60
        private const val EAT_APPROACH_TICKS = 100
        private const val EAT_REACH = 1.0
        private const val FACE_MIN_DISTANCE = 0.2
        private const val MEAL_SPOT_KEEP_TICKS = 600
        private const val EAT_HEAD_PITCH_LIMIT = 90f
        private const val REST_TICKS = 1_200
        private const val FAILED_HUNT_COOLDOWN = 100
        // 5 min of hunger after satiety runs out before players count as food.
        private const val FAIRLY_HUNGRY_TICKS = 6_000
        private const val HAND_FED_SATIETY_TICKS = 6_000
        private const val HAND_FED_HEAL = 2f
        private const val HAND_FEED_TICKS = 40
        private const val HORN_TOSS = 0.2f
        private const val HORN_TOSS_UP = 0.3

        // Target choice.
        private const val HUNT_SCAN_INTERVAL = 20
        private const val HUNT_RANGE = 48.0
        private const val HUNT_HEIGHT = 6.0
        private const val PREY_HEALTH_WEIGHT = 16.0
        private const val YOUNG_PREY_BONUS = 8.0
        private const val OPPORTUNITY_RANGE = 8.0
        private const val SCARCE_DEER_COUNT = 3
        private const val HUNT_SUCCESS_CHANCE = 0.6f
        private const val EASY_PREY_HEALTH_FRACTION = 0.5f

        // Chase and give-up.
        private const val CHARGE_RANGE = 12.0
        private const val FRESH_CHARGE_RANGE = 6.0
        private const val CHARGE_BREAK_RANGE = 16.0
        private const val CLOSE_RANGE = 4.0
        // Blocks per tick; a deer walks near 0.17 and runs near 0.33.
        private const val FLEEING_SPEED = 0.25
        private const val STALK_MAX_TICKS = 100
        private const val GIVE_UP_DISTANCE = 128.0
        // Paths reach this far; past it a path is partial and the trail keeps the goat on course.
        private const val PATH_RANGE = 64.0
        private const val NO_PROGRESS_TICKS = 1_600
        private const val PROGRESS_DISTANCE_STEP = 2.0
        private const val PROGRESS_STAMINA_STEP = 20
        // Players only; a deer is tracked by its trail.
        private const val LOST_SIGHT_TICKS = 60
        private const val TRACK_AFTER_UNSEEN_TICKS = 10
        private const val SCENT_LAG_SAMPLES = 3
        private const val TRAIL_REACHED = 3.0
        private const val REFUGE_REACH = 2.5
        private const val REFUGE_GIVE_UP_TICKS = 400

        // Injury cost of a landed deer strike.
        private const val STRIKE_SLOW_TICKS = 60
        private const val STRIKE_SLOW_AMPLIFIER = 0
        private const val STRIKE_STAMINA_COST = 40
        private const val STRIKE_ENDURANCE_COST = 300
        private const val REPATH_TICKS = 10
        private const val RETREAT_HEALTH_FRACTION = 0.4f

        // Bites, and the Moskuo retaliation limits.
        private const val BITE_CHASE_RANGE = 8.0
        private const val BITE_CHASE_TICKS = 100
        private const val BITE_COOLDOWN_TICKS = 20
        private const val BITE_REACH = 0.8
        private const val BITE_REACH_UP = 0.5
        private const val BITE_MAX_RISE = 3.0

        // Long jump: vanilla Goat velocity, angles and wander cooldown; reach as asked.
        private const val JUMP_MAX_HORIZONTAL = 5
        private const val JUMP_MIN_HORIZONTAL_SQ = 4
        private const val JUMP_MAX_UP = 3
        private const val JUMP_MAX_DOWN = 6
        private const val JUMP_MAX_VELOCITY = 1.5
        private const val JUMP_MIN_PROGRESS = 1.0
        private const val JUMP_CANDIDATES = 8
        private const val JUMP_LIFTOFF_TICKS = 2
        private const val JUMP_MAX_AIR_TICKS = 60
        private const val HUNT_JUMP_COOLDOWN_TICKS = 40
        private const val HORN_SNAP_RECOVER_TICKS = 20
        private const val HORN_SNAP_CHARGE_DISTANCE = 6.0
        // Blocks per tick; a charge runs near 0.54, a trot near 0.28.
        private const val HORN_SNAP_MIN_SPEED = 0.4
        private const val WANDER_JUMP_COOLDOWN_MIN = 600
        private const val WANDER_JUMP_COOLDOWN_MAX = 1_200
        private const val WANDER_JUMP_TRIES = 20
        private const val DETOUR_FACTOR = 2
        private const val DETOUR_SLACK = 4
        private const val WADE_RANGE = 2
        private const val WATER_MALUS = 1f
        private const val WATER_BORDER_MALUS = 0f
        // Water below the node this many blocks down counts as a swim, not a wade.
        private const val DEEP_WATER_DEPTH = 2
        // Vanilla jump power.
        private const val BANK_HOP_SPEED = 0.42
        private const val FALL_DAMAGE_REDUCTION = 10

        // Stamina: 10 s of charge, back to full in 20 s.
        private const val MAX_STAMINA = 200
        private const val CHARGE_MIN_STAMINA = 60
        private const val STAMINA_REGEN_INTERVAL = 2
        // Endurance: 5 min of pursuit, back to full in 50 s.
        private const val MAX_ENDURANCE = 6_000
        private const val HUNT_START_ENDURANCE = 1_200
        private const val ENDURANCE_REGEN_PER_TICK = 6

        // Home range.
        private const val HOME_RANGE = 32
        private const val HUNT_LEASH = 160.0

        // Spawning.
        private const val SPAWN_SCAN_RANGE = 48.0
        private const val SPAWN_GOAT_CAP = 2
        private const val SPAWN_DEER_PER_GOAT = 3

        private const val IDLE_CHANCE = 200
        private const val IDLE_MAX_SWING_SPEED = 0.05f
        private const val IDLE_STAMP_TICKS = 22
        private const val IDLE_HEAD_SHAKE_TICKS = 21
        private const val IDLE_EAR_FLICK_TICKS = 9
        private const val BITE_TICKS = 12

        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, BASE_SPEED)
            .add(Attributes.ATTACK_DAMAGE, 6.0)
            // Only the vanilla hurt knockback, so bitten prey stays in reach.
            .add(Attributes.ATTACK_KNOCKBACK, 0.0)
            .add(Attributes.FOLLOW_RANGE, PATH_RANGE)

        /** Natural spawns keep goats sparse: under [SPAWN_GOAT_CAP] in range, and [SPAWN_DEER_PER_GOAT] deer for each goat after the spawn. */
        fun hasRoomToSpawn(level: net.minecraft.world.level.LevelAccessor, pos: BlockPos, checkDeer: Boolean): Boolean {
            val area = net.minecraft.world.phys.AABB(pos).inflate(SPAWN_SCAN_RANGE)
            val goats = level.getEntitiesOfClass(BlessedGoatEntity::class.java, area).size
            if (goats >= SPAWN_GOAT_CAP) return false
            if (!checkDeer) return true
            return level.getEntitiesOfClass(ShilleagkDeerEntity::class.java, area).size >= (goats + 1) * SPAWN_DEER_PER_GOAT
        }
    }
}
