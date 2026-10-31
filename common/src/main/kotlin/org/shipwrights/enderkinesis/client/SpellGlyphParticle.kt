package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.client.renderer.LightTexture
import org.shipwrights.enderkinesis.spell.SpellGlyphOptions

/** An enchanting glyph in a spell's colour, glowing, drifting off as it fades. */
class SpellGlyphParticle(
    level: ClientLevel, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double,
    rgb: Int, sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z, dx, dy, dz) {

    init {
        rCol = ((rgb shr 16) and 0xFF) / 255f
        gCol = ((rgb shr 8) and 0xFF) / 255f
        bCol = (rgb and 0xFF) / 255f
        pickSprite(sprites)
        quadSize = 0.06f
        lifetime = 22 + random.nextInt(9)
        friction = 0.97f
        gravity = 0f
        hasPhysics = false
        xd = dx; yd = dy; zd = dz
    }

    override fun tick() {
        super.tick()
        alpha = 1f - age.toFloat() / lifetime * 0.8f
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    override fun getLightColor(partialTick: Float): Int = LightTexture.FULL_BRIGHT

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SpellGlyphOptions> {
        override fun createParticle(
            options: SpellGlyphOptions, level: ClientLevel,
            x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double,
        ): Particle = SpellGlyphParticle(level, x, y, z, dx, dy, dz, options.rgb, sprites)
    }
}
