package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType

/**
 * Dust-trail particle dropped by [org.shipwrights.enderkinesis.entity.Cataloger] as
 * it walks. Visually almost identical to [SselithMoteParticle] — same warm-yellow
 * tint, same 8-smoke + 8-glitter sprite atlas, same fade-in/out, same per-tick
 * frame cycle — with two differences that justify the separate particle type:
 *
 *  - **`hasPhysics = true`**: motes settle when they hit a wall, a floor, or the
 *    shelves the cataloger is pathing past, rather than dropping through them.
 *    This is what makes the trail "stick" visibly to the ground.
 *  - **Shorter lifetime**: a fraction of [SselithMoteParticle]'s. The ambient
 *    biome mote needs a long life to accumulate into the haze atmosphere; this
 *    one just needs to read as a brief settling cloud behind the cataloger.
 *
 * Keeping these separate is a perf split — block-collision is non-trivial at the
 * particle count the biome-wide ambient mote runs at, but cheap at the small,
 * tightly-bounded dust-trail count.
 */
class SselithDustParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    private val sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z, 0.0, 0.0, 0.0) {

    /** Which half of the sprite set this dust uses. Picked once at spawn. */
    private val smokeMode: Boolean = random.nextFloat() < 0.5f

    /** Base alpha — smoke reads softer, light reads brighter. */
    private val baseAlpha: Float = if (smokeMode) 0.45f else 0.85f

    init {
        rCol = 1.00f
        gCol = 0.92f
        bCol = 0.55f
        alpha = 0.0f

        quadSize = if (smokeMode) {
            0.10f + random.nextFloat() * 0.06f
        } else {
            0.05f + random.nextFloat() * 0.04f
        }

        gravity = 0.04f
        // Block collision: motes that hit a wall, the floor, or the bookshelves
        // the cataloger is pathing past settle on the surface instead of clipping
        // through. Worth the cost here (small count, short lifetime) where it
        // wouldn't be on the biome-wide ambient mote.
        hasPhysics = true

        xd = (random.nextDouble() - 0.5) * 0.006
        yd = -(0.005 + random.nextDouble() * 0.008)
        zd = (random.nextDouble() - 0.5) * 0.006

        // Shorter than SselithMoteParticle (which is 400–800 ticks): the dust trail
        // is a brief settling effect, not the long-lived atmospheric haze the
        // ambient mote provides. Smoke still lives a bit longer than glitter to
        // compensate for its softer alpha.
        lifetime = if (smokeMode) {
            120 + random.nextInt(60)
        } else {
            90 + random.nextInt(50)
        }

        setSpriteFromIndex(0)
    }

    override fun tick() {
        super.tick()
        xd = (xd * 0.96) + (random.nextDouble() - 0.5) * 0.0008
        zd = (zd * 0.96) + (random.nextDouble() - 0.5) * 0.0008

        val frame = ((age.toFloat() / lifetime.toFloat()) * 8f).toInt().coerceIn(0, 7)
        setSpriteFromIndex(frame)

        val ageF = age.toFloat() / lifetime.toFloat()
        alpha = when {
            ageF < 0.10f -> baseAlpha * (ageF / 0.10f)
            ageF > 0.75f -> baseAlpha * ((1.0f - ageF) / 0.25f)
            else         -> baseAlpha
        }
    }

    private fun setSpriteFromIndex(frame: Int) {
        val base = if (smokeMode) 0 else 8
        setSprite(sprites.get(base + frame, SPRITE_SET_SIZE))
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    companion object {
        /** Total sprite count in `sselith_dust.json` — 8 generic + 8 glitter. */
        private const val SPRITE_SET_SIZE = 16
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = SselithDustParticle(level, x, y, z, sprites)
    }
}
