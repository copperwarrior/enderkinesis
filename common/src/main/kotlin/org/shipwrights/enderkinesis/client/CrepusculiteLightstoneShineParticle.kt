package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.DustParticleBase
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.SimpleParticleType
import org.joml.Vector3f

/** Vanilla dust behaviour with the end rod glitter sprites from crepusculite_lightstone_shine.json. */
class CrepusculiteLightstoneShineParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    vx: Double, vy: Double, vz: Double,
    sprites: SpriteSet,
) : DustParticleBase<DustParticleOptions>(level, x, y, z, vx, vy, vz, DUST, sprites) {

    init {
        val brightness = random.nextFloat() * 0.4f + 0.6f
        rCol = randomizeColor(DUST.color.x(), brightness)
        gCol = randomizeColor(DUST.color.y(), brightness)
        bCol = randomizeColor(DUST.color.z(), brightness)
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            vx: Double, vy: Double, vz: Double,
        ): Particle = CrepusculiteLightstoneShineParticle(level, x, y, z, vx, vy, vz, sprites)
    }

    private companion object {
        val DUST = DustParticleOptions(Vector3f(255f / 255f, 229f / 255f, 233f / 255f), 0.6f)
    }
}
