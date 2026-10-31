package org.shipwrights.enderkinesis.spell

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKEffects

/**
 * Discord unsettles ender travel: an enderman under it can't teleport, and an ender pearl thrown
 * by someone under it fizzles where it lands. Each failed jump sputters out in portal particles
 * and Chaos's glyphs.
 */
object DiscordTeleports {
    /** Whether [entity]'s ender travel fails. */
    @JvmStatic
    fun disrupted(entity: Entity?): Boolean = entity is LivingEntity && entity.hasEffect(EKEffects.DISCORD.get())

    /** A jump that went nowhere, at [at]. */
    @JvmStatic
    fun fizzle(level: Level, at: Vec3) {
        val server = level as? ServerLevel ?: return
        server.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 24, 0.3, 0.5, 0.3, 0.05)
        server.sendParticles(org.shipwrights.enderkinesis.registry.EKParticles.mote(SpellMote.CHAOS_GLYPH), at.x, at.y, at.z, 6, 0.3, 0.4, 0.3, 0.02)
    }
}
