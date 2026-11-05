package org.shipwrights.enderkinesis.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.animal.AbstractFish
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import org.shipwrights.enderkinesis.registry.EKItems

class KekhekhEntity(type: EntityType<out KekhekhEntity>, level: Level) : AbstractFish(type, level) {

    val size: Float
        get() = entityData.get(DATA_SIZE)

    private fun setSize(value: Float) = entityData.set(DATA_SIZE, value.coerceIn(MIN_SIZE, MAX_SIZE))

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

    override fun getAmbientSound(): SoundEvent = SoundEvents.SALMON_AMBIENT

    override fun getDeathSound(): SoundEvent = SoundEvents.SALMON_DEATH

    override fun getHurtSound(source: DamageSource): SoundEvent = SoundEvents.SALMON_HURT

    override fun getFlopSound(): SoundEvent = SoundEvents.SALMON_FLOP

    companion object {
        const val ID_PATH = "kekhekh"
        const val WIDTH = 0.7f
        const val HEIGHT = 0.3f
        private const val MIN_SIZE = 0.7f
        private const val MAX_SIZE = 1.3f
        private const val SIZE_KEY = "Size"

        private val DATA_SIZE: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(KekhekhEntity::class.java, EntityDataSerializers.FLOAT)
    }
}
