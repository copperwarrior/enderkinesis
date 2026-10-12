package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType

/** Hangs below an Inegric Retort, then falls, as a pointed dripstone drip does. */
class IchaericInkDripParticle(
    level: ClientLevel, x: Double, y: Double, z: Double, private val sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z) {

    init {
        setSize(0.01f, 0.01f)
        gravity = 0f
        lifetime = HANG_TICKS + MAX_FALL_TICKS
        setColor(IchaericInkVisuals.RED, IchaericInkVisuals.GREEN, IchaericInkVisuals.BLUE)
        setSprite(sprites.get(0, 1))
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_OPAQUE

    override fun tick() {
        super.tick()
        if (age == HANG_TICKS) {
            gravity = FALL_GRAVITY
            setSprite(sprites.get(1, 1))
        }
        if (onGround) remove()
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = IchaericInkDripParticle(level, x, y, z, sprites)
    }

    companion object {
        private const val HANG_TICKS = 40
        private const val MAX_FALL_TICKS = 60
        // Particle.tick applies 0.04 * gravity, so this gives the 0.06 fall of a vanilla drip.
        private const val FALL_GRAVITY = 1.5f
    }
}
