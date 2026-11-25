package org.shipwrights.enderkinesis.entity

import java.util.EnumSet
import java.util.function.Predicate
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.sounds.SoundEvent
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.animal.AbstractFish
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.Block
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds

class KekhekhEntity(type: EntityType<out KekhekhEntity>, level: Level) : AbstractFish(type, level) {

    val size: Float
        get() = entityData.get(DATA_SIZE)

    private fun setSize(value: Float) = entityData.set(DATA_SIZE, value.coerceIn(MIN_SIZE, MAX_SIZE))

    private var feedCooldown = 0

    private val huntsMe = Predicate<EauhorranaEntity> { it.isHunting(this) }

    override fun registerGoals() {
        super.registerGoals()
        goalSelector.addGoal(1, RefugeGoal())
        goalSelector.addGoal(
            3, EatLarvaeGoal(this, LARVAE_RANGE, LARVAE_SPEED, { feedCooldown <= 0 }, { feedCooldown = FEED_COOLDOWN_TICKS }),
        )
    }

    override fun aiStep() {
        super.aiStep()
        if (!level().isClientSide && feedCooldown > 0) feedCooldown--
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_SIZE, 1f)
    }

    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(key)
        if (key == DATA_SIZE) refreshDimensions()
    }

    override fun getDimensions(pose: Pose): EntityDimensions = super.getDimensions(pose).scale(size)

    override fun finalizeSpawn(
        level: ServerLevelAccessor, difficulty: DifficultyInstance, spawnType: MobSpawnType,
        spawnData: SpawnGroupData?, tag: CompoundTag?,
    ): SpawnGroupData? {
        setSize(MIN_SIZE + random.nextFloat() * (MAX_SIZE - MIN_SIZE))
        return super.finalizeSpawn(level, difficulty, spawnType, spawnData, tag)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putFloat(SIZE_KEY, size)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        if (tag.contains(SIZE_KEY)) setSize(tag.getFloat(SIZE_KEY))
    }

    override fun saveToBucketTag(stack: ItemStack) {
        super.saveToBucketTag(stack)
        stack.orCreateTag.putFloat(SIZE_KEY, size)
    }

    // MobBucketItem runs finalizeSpawn first, so the bucket's size overrides the random roll.
    override fun loadFromBucketTag(tag: CompoundTag) {
        super.loadFromBucketTag(tag)
        if (tag.contains(SIZE_KEY)) setSize(tag.getFloat(SIZE_KEY))
    }

    override fun getBucketItemStack(): ItemStack = ItemStack(EKItems.KEKHEKH_BUCKET.get())

    override fun getAmbientSound(): SoundEvent? = null

    override fun getDeathSound(): SoundEvent = EKSounds.KEKHEKH_DEATH.get()

    override fun getHurtSound(source: DamageSource): SoundEvent = EKSounds.KEKHEKH_HURT.get()

    override fun getFlopSound(): SoundEvent = EKSounds.KEKHEKH_FLOP.get()

    /** Flees an Eauhorrana that hunts this fish into the nearest refuge cell, then waits there. */
    private inner class RefugeGoal : Goal() {
        private var refuge: BlockPos? = null
        private var checkCooldown = 0
        private var fleeTicks = 0
        private var holdTicks = 0

        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            if (--checkCooldown > 0) return false
            checkCooldown = THREAT_CHECK_TICKS
            if (level().getEntitiesOfClass(EauhorranaEntity::class.java, boundingBox.inflate(THREAT_RANGE), huntsMe).isEmpty()) {
                return false
            }
            refuge = findRefuge()
            return refuge != null
        }

        override fun canContinueToUse(): Boolean = refuge != null && fleeTicks < FLEE_TICKS && holdTicks < REFUGE_HOLD_TICKS

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            val at = refuge ?: return
            fleeTicks = 0
            holdTicks = 0
            // Return value ignored: with no path the flee times out after FLEE_TICKS.
            navigation.moveTo(at.x + HALF, at.y + HALF, at.z + HALF, FLEE_SPEED)
        }

        override fun tick() {
            val at = refuge ?: return
            if (blockPosition() == at || distanceToSqr(at.x + HALF, at.y + HALF, at.z + HALF) < ARRIVE_DISTANCE_SQ) {
                navigation.stop()
                holdTicks++
            } else {
                fleeTicks++
            }
        }

        override fun stop() {
            refuge = null
            navigation.stop()
        }

        private fun findRefuge(): BlockPos? {
            val origin = blockPosition()
            val cursor = BlockPos.MutableBlockPos()
            val scratch = BlockPos.MutableBlockPos()
            var best: BlockPos? = null
            var bestDistance = Int.MAX_VALUE
            for (dy in -REFUGE_REACH_Y..REFUGE_REACH_Y) {
                for (dx in -REFUGE_REACH..REFUGE_REACH) {
                    for (dz in -REFUGE_REACH..REFUGE_REACH) {
                        val distance = dx * dx + dy * dy + dz * dz
                        if (distance >= bestDistance) continue
                        cursor.setWithOffset(origin, dx, dy, dz)
                        if (!isRefuge(level(), cursor, scratch)) continue
                        bestDistance = distance
                        best = cursor.immutable()
                    }
                }
            }
            return best
        }
    }

    companion object {
        const val ID_PATH = "kekhekh"
        const val WIDTH = 0.7f
        const val HEIGHT = 0.3f
        private const val MIN_SIZE = 0.7f
        private const val MAX_SIZE = 1.3f
        private const val SIZE_KEY = "Size"

        /** Blocks a Kekhekh hides under or among; the Eauhorrana gives up on a Kekhekh in such a cell. */
        val REFUGE_COVER: TagKey<Block> = TagKey.create(Registries.BLOCK, EnderkinesisMod.id("kekhekh_refuge"))

        private const val THREAT_CHECK_TICKS = 10
        private const val THREAT_RANGE = 10.0
        private const val REFUGE_REACH = 6
        private const val REFUGE_REACH_Y = 2
        private const val FLEE_SPEED = 1.6
        private const val FLEE_TICKS = 100
        private const val REFUGE_HOLD_TICKS = 100
        private const val ARRIVE_DISTANCE_SQ = 0.5
        private const val HALF = 0.5
        private const val LARVAE_RANGE = 12.0
        private const val LARVAE_SPEED = 1.0
        private const val FEED_COOLDOWN_TICKS = 600

        /** Water one block deep, or water in or under [REFUGE_COVER] such as roots, leaves and plants. */
        fun isRefuge(level: BlockGetter, pos: BlockPos, scratch: BlockPos.MutableBlockPos): Boolean {
            if (!level.getFluidState(pos).`is`(FluidTags.WATER)) return false
            if (level.getBlockState(pos).`is`(REFUGE_COVER)) return true
            scratch.setWithOffset(pos, 0, 1, 0)
            if (level.getBlockState(scratch).`is`(REFUGE_COVER)) return true
            val waterAbove = level.getFluidState(scratch).`is`(FluidTags.WATER)
            scratch.setWithOffset(pos, 0, -1, 0)
            return !waterAbove && !level.getFluidState(scratch).`is`(FluidTags.WATER)
        }

        private val DATA_SIZE: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(KekhekhEntity::class.java, EntityDataSerializers.FLOAT)
    }
}
