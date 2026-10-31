package org.shipwrights.enderkinesis.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.SpellContext
import org.shipwrights.enderkinesis.spell.signature.SignatureBehaviours
import org.shipwrights.enderkinesis.spell.signature.SignatureCast

/** It moves by its own [getDeltaMovement] on both sides, so the client keeps up between server position updates. */
class SignatureEntity(type: EntityType<out SignatureEntity>, level: Level) : Entity(type, level) {

    constructor(cast: SignatureCast, at: Vec3, life: Int) : this(EKEntities.SIGNATURE.get(), cast.level) {
        setPos(at)
        xo = at.x; yo = at.y; zo = at.z
        ctx = cast.ctx()
        caster = cast.caster
        entityData.set(SIG, cast.signature.ordinal)
        entityData.set(OWNER, cast.caster.id)
        entityData.set(HAND, cast.hand.ordinal)
        entityData.set(LIFE, life)
        entityData.set(POWER, cast.power)
        entityData.set(SEED, cast.level.random.nextInt())
    }

    init {
        noPhysics = true
        isNoGravity = true
    }

    // Server only.
    var ctx: SpellContext? = null
    var caster: LivingEntity? = null

    /** Behaviour state is here, so that one behaviour object serves all casts. */
    var state: Any? = null

    // Client only.
    var clientState: Any? = null

    val signature: Signature get() = Signature.entries[entityData.get(SIG).coerceIn(0, Signature.entries.size - 1)]

    fun owner(): LivingEntity? = caster ?: level().getEntity(entityData.get(OWNER)) as? LivingEntity

    val hand: InteractionHand get() = InteractionHand.entries[entityData.get(HAND).coerceIn(0, 1)]

    var life: Int
        get() = entityData.get(LIFE)
        set(v) = entityData.set(LIFE, v)

    /** 0..1. */
    val power: Float get() = entityData.get(POWER)

    val seed: Int get() = entityData.get(SEED)

    var point: Vec3
        get() = entityData.get(POINT).let { Vec3(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) }
        set(v) = entityData.set(POINT, Vector3f(v.x.toFloat(), v.y.toFloat(), v.z.toFloat()))

    var targetId: Int
        get() = entityData.get(TARGET)
        set(v) = entityData.set(TARGET, v)

    fun target(): Entity? = targetId.takeIf { it >= 0 }?.let { level().getEntity(it) }

    var data: Int
        get() = entityData.get(DATA)
        set(v) = entityData.set(DATA, v)

    fun progress(partialTick: Float = 0f): Float = ((tickCount + partialTick) / life.coerceAtLeast(1)).coerceIn(0f, 1f)

    override fun defineSynchedData() {
        entityData.define(SIG, 0)
        entityData.define(OWNER, -1)
        entityData.define(HAND, 0)
        entityData.define(LIFE, 100)
        entityData.define(POWER, 1f)
        entityData.define(SEED, 0)
        entityData.define(POINT, Vector3f())
        entityData.define(TARGET, -1)
        entityData.define(DATA, 0)
    }

    override fun tick() {
        super.tick()
        val motion = deltaMovement
        if (motion.lengthSqr() > 0.0) setPos(position().add(motion))
        val level = level()
        if (level is ServerLevel) {
            if (ctx == null) { discard(); return }
            SignatureBehaviours.of(signature).tick(this)
            if (!isRemoved && tickCount >= life) {
                SignatureBehaviours.of(signature).end(this)
                discard()
            }
        } else clientTick?.invoke(this)
    }

    override fun shouldBeSaved(): Boolean = false
    override fun isPickable(): Boolean = false
    override fun isPushable(): Boolean = false
    override fun shouldRenderAtSqrDistance(distance: Double): Boolean = distance < RENDER_RANGE * RENDER_RANGE
    override fun readAdditionalSaveData(tag: CompoundTag) {}
    override fun addAdditionalSaveData(tag: CompoundTag) {}

    companion object {
        private val SIG: EntityDataAccessor<Int> = SynchedEntityData.defineId(SignatureEntity::class.java, EntityDataSerializers.INT)
        private val OWNER: EntityDataAccessor<Int> = SynchedEntityData.defineId(SignatureEntity::class.java, EntityDataSerializers.INT)
        private val HAND: EntityDataAccessor<Int> = SynchedEntityData.defineId(SignatureEntity::class.java, EntityDataSerializers.INT)
        private val LIFE: EntityDataAccessor<Int> = SynchedEntityData.defineId(SignatureEntity::class.java, EntityDataSerializers.INT)
        private val POWER: EntityDataAccessor<Float> = SynchedEntityData.defineId(SignatureEntity::class.java, EntityDataSerializers.FLOAT)
        private val SEED: EntityDataAccessor<Int> = SynchedEntityData.defineId(SignatureEntity::class.java, EntityDataSerializers.INT)
        private val POINT: EntityDataAccessor<Vector3f> = SynchedEntityData.defineId(SignatureEntity::class.java, EntityDataSerializers.VECTOR3)
        private val TARGET: EntityDataAccessor<Int> = SynchedEntityData.defineId(SignatureEntity::class.java, EntityDataSerializers.INT)
        private val DATA: EntityDataAccessor<Int> = SynchedEntityData.defineId(SignatureEntity::class.java, EntityDataSerializers.INT)

        /** Client init sets this, because common code cannot reference client classes. */
        var clientTick: ((SignatureEntity) -> Unit)? = null

        const val RENDER_RANGE = 128.0
    }
}
