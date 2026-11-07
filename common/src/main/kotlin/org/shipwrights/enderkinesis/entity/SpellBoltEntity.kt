package org.shipwrights.enderkinesis.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.ThrowableItemProjectile
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.UlderShardItem
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.ElementEffects
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellContext
import java.util.UUID
import org.shipwrights.enderkinesis.spell.SpellVfx

class SpellBoltEntity(type: EntityType<out SpellBoltEntity>, level: Level) : ThrowableItemProjectile(type, level) {

    private var spell: Spell = Spell(emptyList())

    /** Kept for claims when the caster is offline. */
    private var ownerUuid: UUID? = null
    private var castPoint: Vec3 = Vec3.ZERO

    private var power: Float = 1f

    /** False for bolts that another spell fires, because that spell counts its own fires. */
    var surfaceFire: Boolean = true

    constructor(level: Level, caster: LivingEntity, spell: Spell, origin: Vec3, power: Float) : this(EKEntities.SPELL_BOLT.get(), level) {
        setOwner(caster)
        setPos(origin)
        this.spell = spell
        this.power = power
        ownerUuid = caster.uuid
        castPoint = position()
        spell.shapeElement?.let { item = EKItems.glyphStack(it) }
        entityData.set(ELEMENTS, SpellVfx.elementBits(spell.magnitudes.keys))
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(ELEMENTS, 0)
    }

    fun elements(): List<Element> = SpellVfx.elementsOf(entityData.get(ELEMENTS))

    override fun getDefaultItem(): Item = EKItems.ULDER_SHARD.get()

    override fun tick() {
        super.tick()
        if (isRemoved) return
        if (level().isClientSide) return
        val level = level() as? ServerLevel ?: return
        if (tickCount >= MAX_LIFETIME_TICKS) {
            detonate(level, position(), null)
            return
        }
        pullAlongPath(level)
    }

    fun element(): Element? {
        val stack = item
        return (stack.item as? UlderShardItem)?.element
            ?: stack.tag?.getString(EKItems.NBT_SHARD_ELEMENT)?.let(Element::byId)
    }

    private fun pullAlongPath(level: ServerLevel) {
        val owner = owner
        for (entity in level.getEntities(this, boundingBox.inflate(PULL_RADIUS))) {
            if (entity === owner || entity.isSpectator || !entity.isAlive) continue
            val toBolt = position().subtract(entity.position())
            val dist = toBolt.length()
            if (dist < 1.0e-3 || dist > PULL_RADIUS) continue
            entity.deltaMovement = entity.deltaMovement.add(toBolt.scale(PULL_STRENGTH / dist))
            entity.hurtMarked = true
        }
    }

    override fun onHit(result: HitResult) {
        super.onHit(result)
        val level = level() as? ServerLevel ?: return
        if (isRemoved) return
        detonate(level, result.location, (result as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos)
    }

    private fun detonate(level: ServerLevel, at: Vec3, struck: net.minecraft.core.BlockPos?) {
        // A Staff of Aegis reflection makes its holder the owner, also for claims.
        val caster = owner as? LivingEntity
        val ctx = SpellContext(level, caster, castPoint, caster?.uuid ?: ownerUuid, power = power, surfaceFire = surfaceFire)
        SpellCasting.burst(ctx, spell, at, area = false)
        struck?.let { ElementEffects.dropBlock(ctx, it) }
        discard()
    }

    override fun canHitEntity(target: Entity): Boolean = super.canHitEntity(target) && target !is SpellBoltEntity

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.put(Spell.NBT_GLYPHS, spell.save())
        ownerUuid?.let { tag.putUUID(NBT_OWNER, it) }
        tag.putDouble(NBT_CAST_X, castPoint.x)
        tag.putDouble(NBT_CAST_Y, castPoint.y)
        tag.putDouble(NBT_CAST_Z, castPoint.z)
        tag.putFloat(NBT_POWER, power)
        tag.putBoolean(NBT_SURFACE_FIRE, surfaceFire)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        spell = Spell.load(tag, Spell.NBT_GLYPHS) ?: Spell(emptyList())
        entityData.set(ELEMENTS, SpellVfx.elementBits(spell.magnitudes.keys))
        ownerUuid = if (tag.hasUUID(NBT_OWNER)) tag.getUUID(NBT_OWNER) else null
        castPoint = Vec3(tag.getDouble(NBT_CAST_X), tag.getDouble(NBT_CAST_Y), tag.getDouble(NBT_CAST_Z))
        power = tag.getFloat(NBT_POWER)
        surfaceFire = tag.getBoolean(NBT_SURFACE_FIRE)
    }

    companion object {
        const val MAX_LIFETIME_TICKS = 200
        private val ELEMENTS: EntityDataAccessor<Int> = SynchedEntityData.defineId(SpellBoltEntity::class.java, EntityDataSerializers.INT)
        private const val PULL_RADIUS = 2.5
        private const val PULL_STRENGTH = 0.08

        /** Blocks per tick at full charge. */
        fun maxSpeed(magnitude: Float): Float = 1.0f + 0.25f * magnitude

        private const val NBT_OWNER = "SpellOwner"
        private const val NBT_CAST_X = "CastX"
        private const val NBT_CAST_Y = "CastY"
        private const val NBT_CAST_Z = "CastZ"
        private const val NBT_POWER = "Power"
        private const val NBT_SURFACE_FIRE = "SurfaceFire"
    }
}
