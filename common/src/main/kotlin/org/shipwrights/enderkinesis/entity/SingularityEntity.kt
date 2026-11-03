package org.shipwrights.enderkinesis.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.Absence
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.SpellContext
import org.shipwrights.enderkinesis.spell.SpellSounds
import org.shipwrights.enderkinesis.spell.SpellVfx
import java.util.UUID

/** [banished] makes sure each creature goes Absent only one time per vortex. */
class SingularityEntity(type: EntityType<out SingularityEntity>, level: Level) : Entity(type, level) {

    private var radius = 3.0
    private var lifetime = 60
    private var owner: UUID? = null
    private var absentTicks = 0
    private val banished = HashSet<UUID>()

    init {
        noPhysics = true
    }

    constructor(level: Level, centre: Vec3, radius: Double, lifetime: Int, owner: UUID?, absentTicks: Int) : this(EKEntities.SINGULARITY.get(), level) {
        setPos(centre)
        this.radius = radius
        this.lifetime = lifetime
        this.owner = owner
        this.absentTicks = absentTicks
    }

    override fun defineSynchedData() {}

    override fun tick() {
        super.tick()
        val level = level() as? ServerLevel ?: return
        if (tickCount >= lifetime) {
            SpellSounds.play(level, position(), EKSounds.SPELL_VOID_SINGULARITY_COLLAPSE)
            discard()
            return
        }
        val centre = position()
        var swallowed = false
        for (entity in level.getEntities(this, boundingBox.inflate(radius)) { it is ItemEntity || it is ExperienceOrb }) {
            val toCentre = centre.subtract(entity.position())
            val dist = toCentre.length()
            if (dist > radius) continue
            if (dist < SWALLOW_DISTANCE) {
                entity.discard()
                // A broken chest can feed in many items in one tick.
                if (!swallowed) SpellSounds.play(level, centre, EKSounds.SPELL_VOID_SWALLOW)
                swallowed = true
                continue
            }
            entity.deltaMovement = toCentre.scale(PULL / dist)
            entity.hurtMarked = true
        }
        if (absentTicks > 0) drawCreatures(level, centre)
        level.sendParticles(EKParticles.voidMote(), centre.x, centre.y, centre.z, 3, radius * 0.3, radius * 0.3, radius * 0.3, 0.02)
    }

    private fun drawCreatures(level: ServerLevel, centre: Vec3) {
        val absent = EKEffects.ABSENT.get()
        for (living in level.getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(radius)) {
            it.isAlive && !it.isSpectator && it.uuid != owner && it.uuid !in banished && !SpellContext.isBoss(it) && !Absence.isAbsent(it) &&
                !it.hasEffect(EKEffects.ANCHORED.get())
        }) {
            // Measure to the middle, so a creature that stands below the core can still reach it.
            val toCentre = centre.subtract(living.position().add(0.0, living.bbHeight * 0.5, 0.0))
            val dist = toCentre.length()
            if (dist > radius) continue
            if (dist < BANISH_DISTANCE) {
                banished += living.uuid
                living.addEffect(MobEffectInstance(absent, absentTicks))
                continue
            }
            living.deltaMovement = living.deltaMovement.add(toCentre.scale(CREATURE_PULL / dist))
            living.hurtMarked = true
            if (tickCount % TETHER_EVERY == 0) SpellVfx.voidTether(level, centre, living, TETHER_EVERY + 2)
        }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        radius = tag.getDouble(NBT_RADIUS).takeUnless { it.isNaN() }?.coerceIn(0.0, MAX_RADIUS) ?: 0.0
        lifetime = tag.getInt(NBT_LIFETIME)
        tickCount = tag.getInt(NBT_AGE)
        owner = if (tag.hasUUID(NBT_OWNER)) tag.getUUID(NBT_OWNER) else null
        absentTicks = tag.getInt(NBT_ABSENT)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        tag.putDouble(NBT_RADIUS, radius)
        tag.putInt(NBT_LIFETIME, lifetime)
        tag.putInt(NBT_AGE, tickCount)
        owner?.let { tag.putUUID(NBT_OWNER, it) }
        tag.putInt(NBT_ABSENT, absentTicks)
    }

    private companion object {
        const val SWALLOW_DISTANCE = 0.6
        const val PULL = 0.35
        /** With ground friction this gives about 3.5 blocks a second: a creature can walk out, but not slowly. */
        const val CREATURE_PULL = 0.08
        const val BANISH_DISTANCE = 1.0
        const val TETHER_EVERY = 4
        const val HUM_TICKS = 20
        /** Casts make about 7 at most. This only stops a bad save from a large scan. */
        const val MAX_RADIUS = 16.0
        const val NBT_RADIUS = "Radius"
        const val NBT_LIFETIME = "Lifetime"
        const val NBT_AGE = "Age"
        const val NBT_OWNER = "Owner"
        const val NBT_ABSENT = "AbsentTicks"
    }
}
