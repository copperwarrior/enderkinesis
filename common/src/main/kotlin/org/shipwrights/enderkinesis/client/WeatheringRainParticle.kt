package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType

/** A green drop from the Ritual of Weathering's fog: it fades in, falls, lands, and lies a moment as a vanilla drip does. */
class WeatheringRainParticle(
    level: ClientLevel, x: Double, y: Double, z: Double, private val sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z) {

    private var landed = false

    init {
        setSize(0.01f, 0.01f)
        xd = 0.0
        yd = 0.0
        zd = 0.0
        gravity = FALL_GRAVITY
        lifetime = MAX_FALL_TICKS
        setColor(RED, GREEN, BLUE)
        setSprite(sprites.get(0, 1))
        alpha = 0f
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    override fun tick() {
        super.tick()
        alpha = (age / FADE_IN_TICKS).coerceAtMost(1f)
        if (onGround && !landed) {
            landed = true
            setSprite(sprites.get(1, 1))
            lifetime = age + LAND_TICKS
        }
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = WeatheringRainParticle(level, x, y, z, sprites)
    }

    companion object {
        private const val RED = 0.45f
        private const val GREEN = 0.75f
        private const val BLUE = 0.20f
        private const val FADE_IN_TICKS = 6f
        // Particle.tick applies 0.04 * gravity, so this gives the 0.06 fall of a vanilla drip.
        private const val FALL_GRAVITY = 1.5f
        private const val MAX_FALL_TICKS = 60
        private const val LAND_TICKS = 4
    }
}
