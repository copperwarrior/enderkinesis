package org.shipwrights.enderkinesis.entity

import dev.architectury.registry.registries.RegistrySupplier
import java.util.EnumSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.sounds.SoundEvent
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.AnimationState
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.PanicGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.pathfinder.BlockPathTypes
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.util.TICKS_PER_SECOND

/** Wohlon omnivore. It always flees players and predators, grazes Wohlon plants and wades into shallows for fish. */
class ShilleagkDeerEntity(type: EntityType<out ShilleagkDeerEntity>, level: Level) : PathfinderMob(type, level) {

    enum class Action { NONE, GRAZE, UPRIGHT, FISH, ATTACK_ANTLER, ATTACK_BITE }

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
    private var clientIdle: AnimationState? = null
    private var clientIdleEndTick: Int = -1

    private var mealCooldown: Int = 0
    private var lastFleeTick: Int = Int.MIN_VALUE / 2

    private var lastWalkPhase: Float = 0f
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
        goalSelector.addGoal(1, PanicGoal(this, RUN_SPEED))
        goalSelector.addGoal(2, FleeGoal(Player::class.java) { true })
        goalSelector.addGoal(2, FleeGoal(LivingEntity::class.java) { it.type.`is`(PREDATORS) })
        goalSelector.addGoal(3, HuntFishGoal())
        goalSelector.addGoal(4, GrazeGoal())
        goalSelector.addGoal(5, FreakyIdleGoal())
        goalSelector.addGoal(6, WaterAvoidingRandomStrollGoal(this, WALK_SPEED))
        goalSelector.addGoal(7, WatchDistantPlayerGoal())
        goalSelector.addGoal(8, RandomLookAroundGoal(this))
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

    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    override fun getDimensions(pose: Pose): EntityDimensions =
        if (action == Action.UPRIGHT) UPRIGHT_DIMENSIONS else super.getDimensions(pose)

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
        } else if (mealCooldown > 0) {
            mealCooldown--
        }
    }

    private fun onClientActionChanged() {
        val previous = clientAction
        val next = action
        clientAction = next
        stopClientIdle()
        if (previous == Action.GRAZE) grazeAnimationState.stop()
        if (previous == Action.FISH) grazeBiteAnimationState.stop()
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
            Action.NONE -> Unit
        }
    }

    private fun tickClientAnimations() {
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

    private fun faceToward(target: Vec3) {
        val yaw = (Mth.atan2(target.z - z, target.x - x) * Mth.RAD_TO_DEG).toFloat() - 90f
        yRot = yaw
        yBodyRot = yaw
        yHeadRot = yaw
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
    /** The alarm marks the start of a flight, not each re-path while the threat stays near. */
    private inner class FleeGoal<T : LivingEntity>(type: Class<T>, threat: (LivingEntity) -> Boolean) :
        AvoidEntityGoal<T>(this, type, FLEE_DISTANCE, RUN_SPEED, RUN_SPEED, threat) {
        override fun start() {
            super.start()
            if (tickCount - lastFleeTick > FLEE_GAP_TICKS) playSound(EKSounds.SHILLEAGK_DEER_ALARM.get(), 1f, 1f)
            lastFleeTick = tickCount
        }

        override fun tick() {
            super.tick()
            lastFleeTick = tickCount
        }
    }

    private abstract inner class MealGoal(private val approachSpeed: Double) : Goal() {
        private var ticks = 0
        private var eatTicks = -1
        private var ate = false

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)
        }

        protected abstract val mealAction: Action
        protected abstract val eatDurationTicks: Int
        protected abstract val biteTick: Int
        protected abstract val biteSound: RegistrySupplier<SoundEvent>
        protected open val creepsUntilBite: Boolean = false
        protected abstract fun findMeal(): Boolean
        protected abstract fun mealPosition(): Vec3
        protected abstract fun mealStillThere(): Boolean
        protected abstract fun consume(): Boolean

        override fun canUse(): Boolean =
            mealCooldown <= 0 && random.nextInt(MEAL_CHANCE) == 0 && findMeal()

        override fun canContinueToUse(): Boolean =
            ticks < MEAL_TIMEOUT_TICKS && eatTicks < eatDurationTicks && (eatTicks >= 0 || mealStillThere())

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
                if (eatTicks == biteTick && mealStillThere() && inMouthReach(mealPosition())) {
                    if (!consume()) return
                    heal(MEAL_HEAL)
                    playSound(biteSound.get(), 1f, 1f)
                    mealCooldown = MEAL_COOLDOWN_MIN + random.nextInt(MEAL_COOLDOWN_SPREAD)
                    ate = true
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
            if (action == mealAction) setAction(Action.NONE)
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

        override val mealAction: Action = Action.FISH
        override val eatDurationTicks: Int = FISH_EAT_TICKS
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

        // The default WATER malus keeps paths on land when there is a dry route; a hunt wades straight in.
        override fun start() {
            setPathfindingMalus(BlockPathTypes.WATER, 0f)
            super.start()
        }

        override fun stop() {
            super.stop()
            setPathfindingMalus(BlockPathTypes.WATER, BlockPathTypes.WATER.malus)
            prey = null
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

        private fun isThreat(entity: LivingEntity): Boolean = entity.isAlive && when {
            entity is Player -> EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(entity)
            entity.type.`is`(PREDATORS) -> true
            else -> entity === lastHurtByMob
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
        private const val MEAL_COOLDOWN_MIN = 600
        private const val MEAL_COOLDOWN_SPREAD = 600
        private const val FAILED_MEAL_COOLDOWN = 100
        private const val REPATH_TICKS = 10
        private const val LEVEL_EVENT_BLOCK_BREAK = 2001

        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 16.0)
            .add(Attributes.MOVEMENT_SPEED, BASE_SPEED)
            .add(Attributes.FOLLOW_RANGE, 24.0)
    }
}
