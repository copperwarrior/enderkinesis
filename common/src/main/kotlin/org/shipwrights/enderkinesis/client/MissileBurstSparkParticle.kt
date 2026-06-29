package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SimpleAnimatedParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType

/**
 * 1:1 port of vanilla `FireworkParticles$SparkParticle` minus the trail/twinkle
 * branches we never enable. Extends [SimpleAnimatedParticle] for the same reason
 * vanilla does — its inherited tick runs the second-half alpha ramp
 * (`1 - (age - lifetime/2)/lifetime`), `setSpriteFromAge` sprite cycle, and the
 * 20%-per-tick exponential colour approach toward the fade target. The pink
 * palette is wired via the inherited `setColor` / `setFadeColor` — OUTLINE
 * (pink-tinted white) → GLOW (saturated magenta), matching
 * MagicMissileTrailRenderer's beam.
 *
 * The sprite atlas is the vanilla **generic** swirl (`particle/generic_0..7`,
 * the same set Portal / ReversePortal / DragonBreath use) rather than vanilla
 * SparkParticle's firework spark sheet — gives the burst the ender-particle
 * silhouette while keeping the spark *behaviour* identical.
 *
 * Vanilla SparkParticle ctor passes `0.1f` as the float arg to
 * [SimpleAnimatedParticle]'s ctor, which becomes the particle's `gravity` (friction
 * is set to 0.91 inside the super ctor). The 0.99 alpha that vanilla's Starter
 * sets externally via `setAlpha(0.99f)` is wired here in init.
 */
class MissileBurstSparkParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    vx: Double, vy: Double, vz: Double,
    sprites: SpriteSet,
) : SimpleAnimatedParticle(level, x, y, z, sprites, SPARK_GRAVITY) {

    init {
        xd = vx; yd = vy; zd = vz
        scale(0.75f)
        lifetime = LIFETIME_BASE + random.nextInt(LIFETIME_JITTER)
        setSpriteFromAge(sprites)
        setColor(OUTLINE_COLOR)
        setFadeColor(GLOW_COLOR)
        alpha = 0.99f
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            vx: Double, vy: Double, vz: Double,
        ): Particle = MissileBurstSparkParticle(level, x, y, z, vx, vy, vz, sprites)
    }

    companion object {
        /** Vanilla `SparkParticle` constructs `SimpleAnimatedParticle` with 0.1f — the
         *  float that ends up in `Particle.gravity`. Sparks fall noticeably for it. */
        private const val SPARK_GRAVITY: Float = 0.1f

        /** Half vanilla's 48 + nextInt(12). Snappier, less drifty burst. */
        private const val LIFETIME_BASE: Int = 24
        private const val LIFETIME_JITTER: Int = 6

        /** Beam palette from MagicMissileTrailRenderer, packed for `setColor(int)`. */
        private const val OUTLINE_COLOR: Int = 0xFAD7F0
        private const val GLOW_COLOR: Int = 0xDC82C3
    }
}
