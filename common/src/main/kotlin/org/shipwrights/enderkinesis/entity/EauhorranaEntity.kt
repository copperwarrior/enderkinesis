package org.shipwrights.enderkinesis.entity

import java.util.EnumSet
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.Difficulty
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.AnimationState
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.behavior.BehaviorUtils
import net.minecraft.world.entity.ai.control.SmoothSwimmingLookControl
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.RandomSwimmingGoal
import net.minecraft.world.entity.ai.goal.TryFindWaterGoal
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation
import net.minecraft.world.entity.animal.WaterAnimal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Wohlon predator fish. When hungry it hunts anything edible in the water: small fish go down in one bite,
 * larger prey gets a latched bite that chews until the fish is hit off or stays dry too long.
 */
class EauhorranaEntity(type: EntityType<out EauhorranaEntity>, level: Level) : WaterAnimal(type, level) {

    val biteAnimationState: AnimationState = AnimationState()

    private var hungerTicks = random.nextInt(HUNGER_MAX_TICKS)
    private var biteCooldown = 0
    private var latchTicks = 0
    private var dryTicks = 0

    val isLatched: Boolean get() = entityData.get(DATA_LATCH_ID) != NO_LATCH

    private val isHungry: Boolean get() = hungerTicks <= 0

    init {
        moveControl = SmoothSwimmingMoveControl(this, MAX_TURN_X, MAX_TURN_Y, WATER_SPEED, LAND_SPEED, true)
        lookControl = SmoothSwimmingLookControl(this, MAX_LOOK_Y)
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_LATCH_ID, NO_LATCH)
        entityData.define(DATA_LATCH_YAW, 0f)
        entityData.define(DATA_LATCH_HEIGHT, 0f)
    }

    override fun createNavigation(level: Level): PathNavigation = WaterBoundPathNavigation(this, level)

    override fun registerGoals() {
        goalSelector.addGoal(0, TryFindWaterGoal(this))
        goalSelector.addGoal(1, HuntGoal())
        goalSelector.addGoal(2, DeepSwimGoal())
    }

    fun latchedTarget(): LivingEntity? {
        val id = entityData.get(DATA_LATCH_ID)
        return if (id == NO_LATCH) null else level().getEntity(id) as? LivingEntity
    }

    /** Where the latched fish sits on [target]; the renderer reads this with the frame's partial tick. */
    fun latchPoint(target: LivingEntity, partialTick: Float): Vec3 {
        val yaw = Mth.rotLerp(partialTick, target.yBodyRotO, target.yBodyRot) + entityData.get(DATA_LATCH_YAW)
        val reach = target.bbWidth / 2 + MOUTH_REACH
        val rad = yaw * Mth.DEG_TO_RAD
        return Vec3(
            Mth.lerp(partialTick.toDouble(), target.xo, target.x) - Mth.sin(rad) * reach,
            Mth.lerp(partialTick.toDouble(), target.yo, target.y) +
                entityData.get(DATA_LATCH_HEIGHT) * target.bbHeight - bbHeight / 2,
            Mth.lerp(partialTick.toDouble(), target.zo, target.z) + Mth.cos(rad) * reach,
        )
    }

    override fun tick() {
        super.tick()
        val id = entityData.get(DATA_LATCH_ID)
        if (id == NO_LATCH) return
        val target = level().getEntity(id) as? LivingEntity
        if (target == null) {
            if (!level().isClientSide) release()
            return
        }
        holdOn(target)
        if (!level().isClientSide) chew(target)
    }

    private fun holdOn(target: LivingEntity) {
        val at = latchPoint(target, 1f)
        setPos(at.x, at.y, at.z)
        deltaMovement = Vec3.ZERO
        fallDistance = 0f
        val facing = Mth.wrapDegrees(target.yBodyRot + entityData.get(DATA_LATCH_YAW) + HALF_TURN)
        yRot = facing
        yBodyRot = facing
        yHeadRot = facing
        xRot = 0f
    }

    private fun chew(target: LivingEntity) {
        val dry = if (isInWater) 0 else dryTicks + 1
        dryTicks = dry
        if (!target.isAlive || dry > DRY_RELEASE_TICKS || !isEdible(target, latched = true)) {
            if (!target.isAlive) sate()
            release()
            return
        }
        if (++latchTicks % CHEW_INTERVAL_TICKS != 0) return
        // Return value ignored: a chew into invulnerability frames just misses this second.
        target.hurt(damageSources().mobAttack(this), CHEW_DAMAGE)
        if (!target.isAlive) {
            sate()
            release()
        }
    }

    private fun latch(target: LivingEntity) {
        val toFish = Mth.atan2(z - target.z, x - target.x).toFloat() * Mth.RAD_TO_DEG - QUARTER_TURN
        entityData.set(DATA_LATCH_YAW, Mth.wrapDegrees(toFish - target.yBodyRot))
        val height = ((y + bbHeight / 2 - target.y) / target.bbHeight).toFloat()
        entityData.set(DATA_LATCH_HEIGHT, height.coerceIn(MIN_LATCH_HEIGHT, MAX_LATCH_HEIGHT))
        latchTicks = 0
        dryTicks = 0
        entityData.set(DATA_LATCH_ID, target.id)
    }

    private fun release() {
        entityData.set(DATA_LATCH_ID, NO_LATCH)
        biteCooldown = RELATCH_COOLDOWN_TICKS
    }

    private fun sate() {
        hungerTicks = HUNGER_MIN_TICKS + random.nextInt(HUNGER_MAX_TICKS - HUNGER_MIN_TICKS)
    }

    private fun isEdible(other: LivingEntity, latched: Boolean = false): Boolean =
        other !== this && other.isAlive && (latched || other.isInWater) &&
            (other is Mob || other is Player) && other !is EauhorranaEntity && !other.type.`is`(INEDIBLE) &&
            EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(other) &&
            !(other is Player && level().difficulty == Difficulty.PEACEFUL)

    override fun hurt(source: DamageSource, amount: Float): Boolean {
        val hurt = super.hurt(source, amount)
        if (hurt && isLatched && source.entity != null && !level().isClientSide) release()
        return hurt
    }

    override fun isPushable(): Boolean = !isLatched && super.isPushable()

    override fun pushEntities() {
        if (!isLatched) super.pushEntities()
    }

    override fun travel(input: Vec3) {
        if (isControlledByLocalInstance && isInWater) {
            moveRelative(speed, input)
            move(MoverType.SELF, deltaMovement)
            deltaMovement = deltaMovement.scale(WATER_DRAG)
            if (target == null) deltaMovement = deltaMovement.add(0.0, SINK_SPEED, 0.0)
        } else {
            super.travel(input)
        }
    }

    override fun aiStep() {
        if (!isInWater && onGround() && verticalCollision && !isLatched) {
            deltaMovement = deltaMovement.add(
                (random.nextFloat() * 2 - 1) * FLOP_SIDE.toDouble(),
                FLOP_UP,
                (random.nextFloat() * 2 - 1) * FLOP_SIDE.toDouble(),
            )
            setOnGround(false)
            hasImpulse = true
            playSound(SoundEvents.SALMON_FLOP, soundVolume, voicePitch)
        }
        super.aiStep()
        if (level().isClientSide) return
        if (hungerTicks > 0) hungerTicks--
        if (biteCooldown > 0) biteCooldown--
    }

    override fun handleEntityEvent(id: Byte) {
        if (id == EntityEvent.START_ATTACKING) biteAnimationState.start(tickCount) else super.handleEntityEvent(id)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putInt(HUNGER_KEY, hungerTicks)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        if (tag.contains(HUNGER_KEY)) hungerTicks = tag.getInt(HUNGER_KEY)
    }

    override fun getDeathSound(): SoundEvent = SoundEvents.SALMON_DEATH

    override fun getHurtSound(source: DamageSource): SoundEvent = SoundEvents.SALMON_HURT

    private inner class HuntGoal : Goal() {
        private var prey: LivingEntity? = null
        private var searchCooldown = 0
        private var repathTicks = 0
        private var chaseTicks = 0
        private var biteTicks = NOT_BITING

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean {
            if (!isHungry || isLatched || --searchCooldown > 0) return false
            searchCooldown = SEARCH_INTERVAL_TICKS
            prey = level().getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(HUNT_RANGE)) {
                isEdible(it) && hasLineOfSight(it)
            }.minByOrNull { it.distanceToSqr(this@EauhorranaEntity) }
            return prey != null
        }

        override fun canContinueToUse(): Boolean {
            if (isLatched) return true
            val target = prey ?: return false
            return isHungry && chaseTicks < CHASE_TICKS && isEdible(target) &&
                target.distanceToSqr(this@EauhorranaEntity) < HUNT_RANGE * HUNT_RANGE
        }

        override fun start() {
            repathTicks = 0
            chaseTicks = 0
            biteTicks = NOT_BITING
        }

        override fun stop() {
            prey = null
            navigation.stop()
        }

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun tick() {
            val target = prey ?: return
            if (isLatched) {
                chaseTicks = 0
                return
            }
            lookControl.setLookAt(target, MAX_LOOK_Y.toFloat(), MAX_LOOK_Y.toFloat())
            if (biteTicks != NOT_BITING) {
                if (++biteTicks == BITE_HIT_TICK) bite(target)
                if (biteTicks >= BITE_TICKS) biteTicks = NOT_BITING
                return
            }
            chaseTicks++
            if (biteCooldown == 0 && inReach(target)) {
                navigation.stop()
                biteTicks = 0
                level().broadcastEntityEvent(this@EauhorranaEntity, EntityEvent.START_ATTACKING)
            } else if (--repathTicks <= 0) {
                repathTicks = REPATH_TICKS
                navigation.moveTo(target, CHASE_SPEED)
            }
        }

        private fun inReach(target: LivingEntity): Boolean =
            boundingBox.inflate(BITE_REACH).intersects(target.boundingBox)

        private fun bite(target: LivingEntity) {
            if (!target.isAlive || !inReach(target)) return
            chaseTicks = 0
            if (!target.type.`is`(SWALLOWS)) {
                latch(target)
            } else if (target.eatWhole(this@EauhorranaEntity)) {
                sate()
            }
        }
    }

    private inner class DeepSwimGoal : RandomSwimmingGoal(this, 1.0, WANDER_INTERVAL) {
        override fun canUse(): Boolean = !isLatched && super.canUse()

        override fun getPosition(): Vec3? {
            var fallback: Vec3? = null
            repeat(WANDER_TRIES) {
                val spot = BehaviorUtils.getRandomSwimmablePos(mob, WANDER_XZ, WANDER_Y) ?: return@repeat
                if (isHabitat(level(), BlockPos.containing(spot))) return spot
                if (fallback == null) fallback = spot
            }
            return fallback
        }
    }

    companion object {
        const val ID_PATH = "eauhorrana"
        const val WIDTH = 0.9f
        const val HEIGHT = 0.7f

        /** Prey swallowed in one bite; anything else edible gets latched onto. */
        val SWALLOWS: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, EnderkinesisMod.id("eauhorrana_swallows"))
        val INEDIBLE: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, EnderkinesisMod.id("eauhorrana_inedible"))

        private val DATA_LATCH_ID: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(EauhorranaEntity::class.java, EntityDataSerializers.INT)
        private val DATA_LATCH_YAW: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(EauhorranaEntity::class.java, EntityDataSerializers.FLOAT)
        private val DATA_LATCH_HEIGHT: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(EauhorranaEntity::class.java, EntityDataSerializers.FLOAT)

        private const val NO_LATCH = -1
        private const val HUNGER_KEY = "HungerTicks"

        private const val MAX_TURN_X = 85
        private const val MAX_TURN_Y = 10
        private const val MAX_LOOK_Y = 10
        private const val WATER_SPEED = 0.02f
        private const val LAND_SPEED = 0.1f
        private const val WATER_DRAG = 0.9
        private const val SINK_SPEED = -0.005
        private const val FLOP_SIDE = 0.05f
        private const val FLOP_UP = 0.4

        private const val DEEP_WATER = 4
        private const val ROOFED_WATER = 2
        private const val DEPTH_SCAN = 16
        private const val WANDER_INTERVAL = 40
        private const val WANDER_TRIES = 10
        private const val WANDER_XZ = 10
        private const val WANDER_Y = 7

        private const val HUNGER_MIN_TICKS = 1200
        private const val HUNGER_MAX_TICKS = 3600
        private const val SEARCH_INTERVAL_TICKS = 20
        private const val HUNT_RANGE = 16.0
        private const val CHASE_TICKS = 200
        private const val CHASE_SPEED = 1.6
        private const val REPATH_TICKS = 10
        private const val BITE_REACH = 0.3
        // Bite: the jaws close at 0.21 s; the clip is 0.375 s.
        private const val BITE_HIT_TICK = 4
        private const val BITE_TICKS = 8
        private const val NOT_BITING = -1
        private const val RELATCH_COOLDOWN_TICKS = 60

        // Body centre to the jaws, in blocks.
        private const val MOUTH_REACH = 0.6f
        private const val MIN_LATCH_HEIGHT = 0.25f
        private const val MAX_LATCH_HEIGHT = 0.85f
        private const val CHEW_INTERVAL_TICKS = 20
        private const val CHEW_DAMAGE = 2f
        private const val DRY_RELEASE_TICKS = 60
        private const val QUARTER_TURN = 90f
        private const val HALF_TURN = 180f

        /** Deep open water, or roofed water such as the flooded root tunnels, which are often under 4 tall. */
        fun isHabitat(level: BlockGetter, pos: BlockPos): Boolean {
            val cursor = pos.mutable()
            var depth = 0
            while (depth < DEPTH_SCAN && level.getFluidState(cursor).`is`(FluidTags.WATER)) {
                depth++
                cursor.move(0, 1, 0)
            }
            val roofed = depth < DEPTH_SCAN && level.getBlockState(cursor).isCollisionShapeFullBlock(level, cursor)
            cursor.set(pos).move(0, -1, 0)
            while (depth < DEPTH_SCAN && level.getFluidState(cursor).`is`(FluidTags.WATER)) {
                depth++
                cursor.move(0, -1, 0)
            }
            return depth >= DEEP_WATER || roofed && depth >= ROOFED_WATER
        }

        @Suppress("UNUSED_PARAMETER") // Shape fixed by SpawnPlacements.SpawnPredicate.
        fun canSpawn(
            type: EntityType<Mob>,
            level: ServerLevelAccessor,
            spawnType: MobSpawnType,
            pos: BlockPos,
            random: RandomSource,
        ): Boolean = isHabitat(level, pos)

        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 12.0)
            .add(Attributes.MOVEMENT_SPEED, 1.2)
            .add(Attributes.FOLLOW_RANGE, HUNT_RANGE)
    }
}
