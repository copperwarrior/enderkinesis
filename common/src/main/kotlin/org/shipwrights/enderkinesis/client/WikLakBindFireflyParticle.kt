package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType

/** One-shot variant of the Wohlonnogondonia firefly used by the Wik-Lak Host
 *  construction's bind-thread visual. Inherits the orbit / flicker / colour
 *  from [WohlonnogondoniaFireflyParticle] but resets `lifetime` to ~1 second
 *  so the beaded line is gone in step with the user-visible "summon flash"
 *  rather than persisting as ambient glitter for half a minute. */
class WikLakBindFireflyParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    sprites: SpriteSet,
) : WohlonnogondoniaFireflyParticle(level, x, y, z, sprites) {

    init {
        // Override parent's 300+rand(300) tick lifetime. The flicker
        // envelope inside the parent reads `age / lifetime`, so its
        // fade-in / fade-out shape just rescales — no other tweaks
        // needed for the short-life variant.
        lifetime = 18 + random.nextInt(6)
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = WikLakBindFireflyParticle(level, x, y, z, sprites)
    }
}
