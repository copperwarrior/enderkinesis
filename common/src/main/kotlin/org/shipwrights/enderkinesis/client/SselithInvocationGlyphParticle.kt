package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity

class SselithInvocationGlyphParticle(
    level: ClientLevel,
    private val startX: Double, private val startY: Double, private val startZ: Double,
    private val target: Entity,
) : SselithGlyphParticle(level, startX, startY, startZ) {

    private val startAngle = random.nextFloat() * Mth.TWO_PI
    private val orbitHeight = random.nextFloat() * target.bbHeight

    init {
        lifetime = LIFETIME_TICKS
        xd = 0.0
        yd = 0.0
        zd = 0.0
    }

    override fun tick() {
        xo = x
        yo = y
        zo = z
        if (age++ >= lifetime || target.isRemoved) {
            remove()
            return
        }
        val approach = Mth.clamp(age / APPROACH_TICKS, 0f, 1f)
        val blend = (approach * approach * (3f - 2f * approach)).toDouble()
        val angle = startAngle + age * ORBIT_RAD_PER_TICK
        setPos(
            Mth.lerp(blend, startX, target.x + ORBIT_RADIUS * Mth.cos(angle)),
            Mth.lerp(blend, startY, target.y + orbitHeight),
            Mth.lerp(blend, startZ, target.z + ORBIT_RADIUS * Mth.sin(angle)),
        )
        setAlpha(Mth.clamp((lifetime - age) / FADE_TICKS, 0f, 1f))
    }

    // The provider reads the target entity id from the xd argument. SimpleParticleType has no data channel.
    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle? {
            val target = level.getEntity(xd.toInt()) ?: return null
            val p = SselithInvocationGlyphParticle(level, x, y, z, target)
            p.pickSprite(sprites)
            return p
        }
    }

    private companion object {
        const val LIFETIME_TICKS = 90
        const val APPROACH_TICKS = 35f
        const val FADE_TICKS = 15f
        const val ORBIT_RADIUS = 0.9
        const val ORBIT_RAD_PER_TICK = 0.12f
    }
}
