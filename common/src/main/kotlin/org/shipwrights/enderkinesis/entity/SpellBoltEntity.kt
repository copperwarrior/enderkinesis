package org.shipwrights.enderkinesis.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
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
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.registry.EKSounds

/**
 * A Bolt: the arcing projectile of a Forward cast whose shape is Bolt. It carries the whole
 * spell and detonates on hitting a block or entity, as a sphere per element around the
 * impact, like an Area cast. A block it lands on then falls, if it still qualifies after
 * the detonation's block effects. In flight it tugs nearby entities toward its path.
 *
 * Drawn as the shape element's shard. Fizzles if it flies for [MAX_LIFETIME_TICKS].
 */
class SpellBoltEntity(type: EntityType<out SpellBoltEntity>, level: Level) : ThrowableItemProjectile(type, level) {

    private var spell: Spell = Spell(emptyList())

    /** The caster's UUID for claims, kept even when the caster is offline. */
    private var ownerUuid: UUID? = null
    private var castPoint: Vec3 = Vec3.ZERO

    constructor(level: Level, caster: LivingEntity, spell: Spell) : this(EKEntities.SPELL_BOLT.get(), level) {
        setOwner(caster)
        setPos(caster.x, caster.eyeY - 0.1, caster.z)
        this.spell = spell
        ownerUuid = caster.uuid
        castPoint = position()
        spell.shapeElement?.let { item = EKItems.glyphStack(it) }
    }

    override fun getDefaultItem(): Item = EKItems.MYSTIC_SHARD.get()

    override fun tick() {
        super.tick()
        if (isRemoved) return
        if (level().isClientSide) {
            trail()
            return
        }
        val level = level() as? ServerLevel ?: return
        if (tickCount >= MAX_LIFETIME_TICKS) {
            discard()
            return
        }
        pullAlongPath(level)
    }

    /** Client: a dust trail in the shape element's colour, read from the shard it's drawn as. */
    private fun trail() {
        val stack = item
        val element = (stack.item as? MysticShardItem)?.element
            ?: stack.tag?.getString(EKItems.NBT_SHARD_ELEMENT)?.let(Element::byId)
            ?: return
        val dust = SpellCasting.dust(element)
        val from = Vec3(xo, yo, zo)
        val to = position()
        repeat(TRAIL_PARTICLES) { i ->
            val p = from.lerp(to, (i + 1).toDouble() / TRAIL_PARTICLES)
            level().addParticle(dust, p.x, p.y, p.z, 0.0, 0.0, 0.0)
        }
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
        // A Staff of Aegis reflection makes its holder the owner, for claims too.
        val caster = owner as? LivingEntity
        val ctx = SpellContext(level, caster, castPoint, caster?.uuid ?: ownerUuid)
        SpellCasting.burst(ctx, spell, result.location, area = false)
        level.playSound(null, result.location.x, result.location.y, result.location.z, EKSounds.BOLT_IMPACT.get(), SoundSource.NEUTRAL, 1f, 1f)
        if (result is BlockHitResult && result.type == HitResult.Type.BLOCK) ElementEffects.dropBlock(ctx, result.blockPos)
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
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        spell = Spell.load(tag, Spell.NBT_GLYPHS) ?: Spell(emptyList())
        ownerUuid = if (tag.hasUUID(NBT_OWNER)) tag.getUUID(NBT_OWNER) else null
        castPoint = if (tag.contains(NBT_CAST_X, Tag.TAG_DOUBLE.toInt())) {
            Vec3(tag.getDouble(NBT_CAST_X), tag.getDouble(NBT_CAST_Y), tag.getDouble(NBT_CAST_Z))
        } else position()
    }

    companion object {
        const val MAX_LIFETIME_TICKS = 200
        private const val TRAIL_PARTICLES = 3
        private const val PULL_RADIUS = 2.5
        private const val PULL_STRENGTH = 0.08

        /** Launch speed in blocks per tick at full charge: rises with the shape element's
         *  magnitude. */
        fun maxSpeed(magnitude: Float): Float = 1.0f + 0.25f * magnitude

        private const val NBT_OWNER = "SpellOwner"
        private const val NBT_CAST_X = "CastX"
        private const val NBT_CAST_Y = "CastY"
        private const val NBT_CAST_Z = "CastZ"
    }
}
