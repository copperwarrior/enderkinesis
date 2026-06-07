package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.PortalParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType

/**
 * Ender-green recolour of vanilla [PortalParticle]. The motion math (`x = xStart + xd · f` where
 * f walks 1 → 1.125 → 0 — a small outward overshoot followed by convergence to xStart, plus the
 * `+(1 − age/lifetime)` y drift) is exactly what makes the Planar Anchor's spiral pull-to-centre
 * read, so we keep it; only the colour tint differs from vanilla's purple. Low red, strong green,
 * a touch of blue — sits in the ender-green / teal family.
 */
class PlanarSpiralParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    xd: Double, yd: Double, zd: Double,
) : PortalParticle(level, x, y, z, xd, yd, zd) {

    init {
        // Same brightness curve as vanilla PortalParticle's color setup (`j ∈ [0.4, 1.0]`); only
        // the per-channel weights differ. Tinting in `init` runs after super's constructor, so
        // we overwrite the rCol/gCol/bCol values it just set.
        val j = random.nextFloat() * 0.6f + 0.4f
        rCol = j * 0.15f
        gCol = j * 0.95f
        bCol = j * 0.45f
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle {
            val p = PlanarSpiralParticle(level, x, y, z, xd, yd, zd)
            p.pickSprite(sprites)
            return p
        }
    }
}
