package org.shipwrights.enderkinesis.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import org.shipwrights.enderkinesis.spell.GasField
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKEntities
import java.util.UUID

/** Do not remove: older worlds save these clouds. Each one moves its gas into [GasField] on its first tick. */
class MiasmaEntity(type: EntityType<out MiasmaEntity>, level: Level) : Entity(type, level) {

    var ownerId: UUID? = null
        private set

    init {
        noPhysics = true
    }

    constructor(level: Level, at: Vec3, radius: Float, duration: Int, owner: Entity?) : this(EKEntities.MIASMA.get(), level) {
        setPos(at)
        this.radius = radius
        this.duration = duration
        ownerId = owner?.uuid
    }

    var radius: Float
        get() = entityData.get(RADIUS)
        private set(value) = entityData.set(RADIUS, value)

    var duration: Int
        get() = entityData.get(DURATION)
        private set(value) = entityData.set(DURATION, value)

    val height: Float get() = radius * HEIGHT_RATIO

    fun contains(point: Vec3): Boolean {
        val dx = (point.x - x) / radius
        val dy = (point.y - y) / height
        val dz = (point.z - z) / radius
        return dy >= -0.2 && dx * dx + dy * dy + dz * dz <= 1.0
    }

    override fun defineSynchedData() {
        entityData.define(RADIUS, 2f)
        entityData.define(DURATION, 120)
    }

    override fun tick() {
        super.tick()
        val level = level() as? ServerLevel ?: return
        GasField.addDome(level, position(), radius.toDouble(), ((duration - tickCount) * OLD_DECAY_PER_TICK).coerceIn(0f, 1f))
        discard()
    }

    override fun getBoundingBoxForCulling(): AABB =
        AABB(x - radius, y - 0.5, z - radius, x + radius, y + height + 0.5, z + radius)

    override fun isPickable(): Boolean = false

    override fun readAdditionalSaveData(tag: CompoundTag) {
        radius = tag.getFloat(NBT_RADIUS).takeUnless { it.isNaN() }?.coerceIn(MIN_RADIUS, MAX_RADIUS) ?: MIN_RADIUS
        duration = tag.getInt(NBT_DURATION)
        tickCount = tag.getInt(NBT_AGE)
        ownerId = if (tag.hasUUID(NBT_OWNER)) tag.getUUID(NBT_OWNER) else null
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        tag.putFloat(NBT_RADIUS, radius)
        tag.putInt(NBT_DURATION, duration)
        tag.putInt(NBT_AGE, tickCount)
        ownerId?.let { tag.putUUID(NBT_OWNER, it) }
    }

    companion object {
        private val RADIUS: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(MiasmaEntity::class.java, EntityDataSerializers.FLOAT)
        private val DURATION: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(MiasmaEntity::class.java, EntityDataSerializers.INT)

        const val HEIGHT_RATIO = 0.7f
        /** The decay rate of the old clouds, used to find how much gas an old cloud has. */
        private const val OLD_DECAY_PER_TICK = 1f / 600f
        // The floor keeps the divide by radius in the dome test finite.
        private const val MIN_RADIUS = 0.5f
        private const val MAX_RADIUS = 16f
        private const val NBT_RADIUS = "Radius"
        private const val NBT_DURATION = "Duration"
        private const val NBT_AGE = "Age"
        private const val NBT_OWNER = "Owner"
    }
}
