package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType

/**
 * Enchanted-book glyph that rains down inside the local AABB of a ship
 * targeted by the Wylland Tome. Spawned client-side each tick while a
 * ship grab is active (see [WyllandTomeClient.spawnShipParticles]).
 *
 * Two deliberate departures from the vanilla enchant glyph make a
 * sparse, cheap stream still read clearly:
 *
 *  - **Emissive.** [getLightColor] is pinned to full brightness so each
 *    glyph glows regardless of the ambient light inside the hull — the
 *    "bright" the brief calls for.
 *  - **Falling.** It drifts downward under light gravity (the "rain"),
 *    rather than the vanilla glyph's convergent flight toward a target.
 *
 * Physics are off ([hasPhysics] = false) so the glyphs fall straight
 * through the hull without per-particle block collision — the count is
 * kept low for performance and we don't want to pay collision on top.
 */
class WyllandTomeShipParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
) : TextureSheetParticle(level, x, y, z) {

    init {
        // Bright, faintly violet wash — enchanted-book ink, lit up.
        val j = random.nextFloat() * 0.2f + 0.8f
        rCol = j * 0.85f
        gCol = j * 0.8f
        bCol = j
        setAlpha(1.0f)
        quadSize *= 0.9f + random.nextFloat() * 0.4f
        lifetime = 24 + random.nextInt(20)
        hasPhysics = false
        gravity = 0.5f
        // Gentle downward drift to start the fall; horizontal jitter so
        // the glyphs don't rain in perfect vertical columns.
        xd = (random.nextDouble() - 0.5) * 0.01
        yd = -(0.02 + random.nextDouble() * 0.02)
        zd = (random.nextDouble() - 0.5) * 0.01
    }

    /** Full-bright on both light channels — the particle is emissive and
     *  ignores the (often dim) light level inside the ship's hull. */
    override fun getLightColor(partialTick: Float): Int = 0xF000F0

    override fun getRenderType(): ParticleRenderType =
        ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle {
            val p = WyllandTomeShipParticle(level, x, y, z)
            p.pickSprite(sprites)
            return p
        }
    }
}
