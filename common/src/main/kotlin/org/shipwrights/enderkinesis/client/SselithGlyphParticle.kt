package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType

/**
 * A single rising sga-glyph rune. Spawned server-side as a cylindrical
 * column from a lectern when a Cataloger rewrites its book into Sselith
 * (see [org.shipwrights.enderkinesis.entity.CatalogerScribe]).
 *
 * Like the Wylland ship glyph it is **emissive** ([getLightColor] pinned
 * to full bright) so it reads clearly inside a dim library — but instead
 * of falling it drifts **upward** under zero gravity, easing off as the
 * default friction bleeds its velocity, like ink lifting off the page.
 */
class SselithGlyphParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
) : TextureSheetParticle(level, x, y, z) {

    init {
        // Warm Sselith yellow — strong red+green, little blue.
        val j = random.nextFloat() * 0.2f + 0.8f
        rCol = j
        gCol = j * 0.85f
        bCol = j * 0.2f
        setAlpha(1.0f)
        // Small runes — a fraction of the default glyph size.
        quadSize *= 0.4f + random.nextFloat() * 0.2f
        lifetime = 26 + random.nextInt(20)
        hasPhysics = false
        gravity = 0f
        // Steady upward drift with a faint horizontal shimmer; friction
        // in the base tick eases the rise so the column thins as it lifts.
        xd = (random.nextDouble() - 0.5) * 0.005
        yd = 0.03 + random.nextDouble() * 0.03
        zd = (random.nextDouble() - 0.5) * 0.005
    }

    override fun getLightColor(partialTick: Float): Int = 0xF000F0

    override fun getRenderType(): ParticleRenderType =
        ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle {
            val p = SselithGlyphParticle(level, x, y, z)
            p.pickSprite(sprites)
            return p
        }
    }
}
