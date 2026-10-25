package org.shipwrights.enderkinesis.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * Void's Singularity: a short-lived vortex. Dropped items and experience within [radius] are
 * drawn in, and whatever reaches the centre vanishes into the Abyss for good.
 */
class SingularityEntity(type: EntityType<out SingularityEntity>, level: Level) : Entity(type, level) {

    private var radius = 3.0
    private var lifetime = 60

    init {
        noPhysics = true
    }

    constructor(level: Level, centre: Vec3, radius: Double, lifetime: Int) : this(EKEntities.SINGULARITY.get(), level) {
        setPos(centre)
        this.radius = radius
        this.lifetime = lifetime
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
        level.sendParticles(EKParticles.voidMote(), centre.x, centre.y, centre.z, 6, radius * 0.3, radius * 0.3, radius * 0.3, 0.02)
        if (tickCount % HUM_TICKS == 0) {
            level.playSound(null, centre.x, centre.y, centre.z, EKSounds.SINGULARITY_HUM.get(), SoundSource.NEUTRAL, 0.6f, 0.5f)
        }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        radius = tag.getDouble(NBT_RADIUS)
        lifetime = tag.getInt(NBT_LIFETIME)
        tickCount = tag.getInt(NBT_AGE)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        tag.putDouble(NBT_RADIUS, radius)
        tag.putInt(NBT_LIFETIME, lifetime)
        tag.putInt(NBT_AGE, tickCount)
    }

    private companion object {
        const val SWALLOW_DISTANCE = 0.6
        const val PULL = 0.35
        const val HUM_TICKS = 20
        const val NBT_RADIUS = "Radius"
        const val NBT_LIFETIME = "Lifetime"
        const val NBT_AGE = "Age"
    }
}
