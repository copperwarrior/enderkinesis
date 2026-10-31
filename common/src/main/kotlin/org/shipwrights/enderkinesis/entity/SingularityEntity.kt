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
import org.shipwrights.enderkinesis.spell.SpellContext
import org.shipwrights.enderkinesis.spell.SpellVfx
import java.util.UUID

/**
 * Void's Singularity: a short-lived vortex. Dropped items and experience within [radius] are
 * drawn in, and whatever reaches the centre vanishes into the Abyss for good. Creatures are
 * drawn in too, more gently, and one reaching the core goes Absent for [absentTicks], once
 * each vortex: it reappears there with the vortex still turning, and isn't taken again. The
 * [owner] (the caster) is never drawn in.
 */
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
            discard()
            return
        }
        val centre = position()
        for (entity in level.getEntities(this, boundingBox.inflate(radius)) { it is ItemEntity || it is ExperienceOrb }) {
            val toCentre = centre.subtract(entity.position())
            val dist = toCentre.length()
            if (dist > radius) continue
            if (dist < SWALLOW_DISTANCE) {
                entity.discard()
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
            // Measured to its middle, so a creature standing under the core still reaches it.
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
            // Strands of the void hold it to the core while it's drawn in.
            if (tickCount % TETHER_EVERY == 0) SpellVfx.voidTether(level, centre, living, TETHER_EVERY + 2)
        }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        radius = tag.getDouble(NBT_RADIUS)
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
        /** Added to a creature's motion each tick. Against ground friction that settles near
         *  3.5 blocks a second: a creature can walk out of it, but not idly. */
        const val CREATURE_PULL = 0.08
        const val BANISH_DISTANCE = 1.0
        /** Ticks between the tethers sent for each creature being drawn in. */
        const val TETHER_EVERY = 4
        const val HUM_TICKS = 20
        const val NBT_RADIUS = "Radius"
        const val NBT_LIFETIME = "Lifetime"
        const val NBT_AGE = "Age"
        const val NBT_OWNER = "Owner"
        const val NBT_ABSENT = "AbsentTicks"
    }
}
