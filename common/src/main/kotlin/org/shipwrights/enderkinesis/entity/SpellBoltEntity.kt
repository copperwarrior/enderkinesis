package org.shipwrights.enderkinesis.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
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
import org.shipwrights.enderkinesis.item.MysticShardItem
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.ElementEffects
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellContext
import java.util.UUID
import org.shipwrights.enderkinesis.spell.SpellVfx

/**
 * A Bolt: the arcing projectile of a Forward cast whose shape is Bolt. It carries the whole
 * spell and detonates on hitting a block or entity, as a sphere per element around the
 * impact, like an Area cast, its damage scaled by its [power] (how far it was drawn; see
 * [SpellCasting.boltPower]). A block it lands on then falls, if it still qualifies after the
 * detonation's block effects. In flight it tugs nearby entities toward its path.
 *
 * Drawn as a glowing knot of light in the spell's colour (see `SpellBoltRenderer`), trailing
 * each of its elements' particles (see `SpellVfxClient`). Fizzles if it flies for
 * [MAX_LIFETIME_TICKS].
 */
class SpellBoltEntity(type: EntityType<out SpellBoltEntity>, level: Level) : ThrowableItemProjectile(type, level) {

    private var spell: Spell = Spell(emptyList())

    /** The caster's UUID for claims, kept even when the caster is offline. */
    private var ownerUuid: UUID? = null
    private var castPoint: Vec3 = Vec3.ZERO

    /** The burst's damage multiplier, from the draw ([SpellContext.power]). */
    private var power: Float = 1f

    /** Whether its burst may light fire on the ground ([SpellContext.surfaceFire]): false for
     *  bolts another spell fires, which counts its own fires. */
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

    /** The spell's elements (synced to clients), for its colour and trail. */
    fun elements(): List<Element> = SpellVfx.elementsOf(entityData.get(ELEMENTS))

    override fun getDefaultItem(): Item = EKItems.MYSTIC_SHARD.get()

    override fun tick() {
        super.tick()
        if (isRemoved) return
        if (level().isClientSide) return
        val level = level() as? ServerLevel ?: return
        if (tickCount >= MAX_LIFETIME_TICKS) {
            // Out of flight: it goes off where it is rather than vanishing with its payload.
            detonate(level, position(), null)
            return
        }
        pullAlongPath(level)
    }

    /** The shape element, read from the shard the bolt carries (synced to clients). */
    fun element(): Element? {
        val stack = item
        return (stack.item as? MysticShardItem)?.element
            ?: stack.tag?.getString(EKItems.NBT_SHARD_ELEMENT)?.let(Element::byId)
    }

    /** Gravity's pull along the flight path: everything near the bolt drifts toward it. */
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

    /** The spell bursts at [at]; a struck block at [struck] then falls if it qualifies. */
    private fun detonate(level: ServerLevel, at: Vec3, struck: net.minecraft.core.BlockPos?) {
        // A Staff of Aegis reflection makes its holder the owner, for claims too.
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
        castPoint = if (tag.contains(NBT_CAST_X, Tag.TAG_DOUBLE.toInt())) {
            Vec3(tag.getDouble(NBT_CAST_X), tag.getDouble(NBT_CAST_Y), tag.getDouble(NBT_CAST_Z))
        } else position()
        power = if (tag.contains(NBT_POWER, Tag.TAG_FLOAT.toInt())) tag.getFloat(NBT_POWER) else 1f
        surfaceFire = !tag.contains(NBT_SURFACE_FIRE) || tag.getBoolean(NBT_SURFACE_FIRE)
    }

    companion object {
        const val MAX_LIFETIME_TICKS = 200
        private val ELEMENTS: EntityDataAccessor<Int> = SynchedEntityData.defineId(SpellBoltEntity::class.java, EntityDataSerializers.INT)
        private const val PULL_RADIUS = 2.5
        private const val PULL_STRENGTH = 0.08

        /** Launch speed in blocks per tick at full charge: rises with the shape element's
         *  magnitude. */
        fun maxSpeed(magnitude: Float): Float = 1.0f + 0.25f * magnitude

        private const val NBT_OWNER = "SpellOwner"
        private const val NBT_CAST_X = "CastX"
        private const val NBT_CAST_Y = "CastY"
        private const val NBT_CAST_Z = "CastZ"
        private const val NBT_POWER = "Power"
        private const val NBT_SURFACE_FIRE = "SurfaceFire"
    }
}
