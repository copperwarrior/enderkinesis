package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType
import org.shipwrights.enderkinesis.item.IncantationOfTheRiverItem

/** Incantation of the River pressure-washer particles, all in the River blue: [Mode.DROPLET] is a fine droplet that
 *  flies on its spawn velocity and falls, [Mode.MIST] a faint haze puff that drifts and grows, and [Mode.FOAM] a brief
 *  pale foam blob at the impact. */
class RiverSprayParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    xSpeed: Double, ySpeed: Double, zSpeed: Double,
    sprites: SpriteSet,
    private val mode: Mode,
) : TextureSheetParticle(level, x, y, z) {

    enum class Mode { DROPLET, MIST, FOAM }

    private val baseAlpha: Float
    private val baseSize: Float

    init {
        xd = xSpeed
        yd = ySpeed
        zd = zSpeed
        val rgb = IncantationOfTheRiverItem.WATER_COLOUR
        setColor((rgb shr 16 and 0xFF) / 255f, (rgb shr 8 and 0xFF) / 255f, (rgb and 0xFF) / 255f)
        when (mode) {
            Mode.DROPLET -> {
                baseSize = 0.06f + random.nextFloat() * 0.04f
                lifetime = 14 + random.nextInt(10)
                gravity = 0.5f
                friction = 0.9f
                hasPhysics = true
                baseAlpha = 0.9f
            }
            Mode.MIST -> {
                baseSize = 0.5f
                lifetime = 26 + random.nextInt(9)
                gravity = -0.01f
                friction = 0.97f
                hasPhysics = false
                baseAlpha = 0.16f
            }
            Mode.FOAM -> {
                setColor(0.85f, 0.94f, 1.0f)
                baseSize = 0.25f + random.nextFloat() * 0.15f
                lifetime = 4
                gravity = 0f
                friction = 0.5f
                hasPhysics = false
                baseAlpha = 0.7f
                xd = (random.nextDouble() - 0.5) * FOAM_JITTER
                yd = (random.nextDouble() - 0.5) * FOAM_JITTER
                zd = (random.nextDouble() - 0.5) * FOAM_JITTER
            }
        }
        // The vanilla generic set runs from one pixel (frame 0) to a full blob (frame 7).
        val frame = if (mode == Mode.DROPLET) DROPLET_FRAME else PUFF_FRAME_MIN + random.nextInt(GENERIC_FRAMES - PUFF_FRAME_MIN)
        setSprite(sprites.get(frame, GENERIC_FRAMES - 1))
        quadSize = baseSize
        alpha = baseAlpha
    }

    override fun tick() {
        super.tick()
        if (removed) return
        val f = age.toFloat() / lifetime
        when (mode) {
            Mode.DROPLET -> {
                // A drop that lands is gone; it does not slide on the ground.
                if (onGround) remove()
                alpha = if (f > FADE_START) baseAlpha * (1f - f) / (1f - FADE_START) else baseAlpha
            }
            Mode.MIST -> {
                quadSize = baseSize * (1f + f * MIST_GROWTH)
                alpha = baseAlpha * (1f - f) * (1f - f)
            }
            Mode.FOAM -> alpha = baseAlpha * (1f - f) * (1f - f)
        }
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    companion object {
        private const val FADE_START = 0.7f
        private const val MIST_GROWTH = 1.5f
        private const val FOAM_JITTER = 0.04
        private const val GENERIC_FRAMES = 8
        private const val DROPLET_FRAME = 4
        private const val PUFF_FRAME_MIN = 5
    }

    class Provider(private val sprites: SpriteSet, private val mode: Mode) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = RiverSprayParticle(level, x, y, z, xd, yd, zd, sprites, mode)
    }
}
