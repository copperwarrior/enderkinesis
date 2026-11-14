package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType

/** A moss strand or moss pad flake that drops off a vine when the vine fades. */
class WoggakVineShedParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    xSpeed: Double, ySpeed: Double, zSpeed: Double,
    sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z, xSpeed, ySpeed, zSpeed) {

    init {
        pickSprite(sprites)
        xd = xSpeed
        yd = ySpeed
        zd = zSpeed
        gravity = GRAVITY
        quadSize = SIZE_MIN + random.nextFloat() * SIZE_RANGE
        lifetime = LIFE_MIN + random.nextInt(LIFE_RANGE)
        roll = random.nextFloat() * TWO_PI
        oRoll = roll
    }

    override fun tick() {
        super.tick()
        val fadeFrom = FADE_START * lifetime
        alpha = if (age > fadeFrom) ((lifetime - age) / (lifetime - fadeFrom)).coerceIn(0f, 1f) else 1f
        roll += SPIN * (if (xd >= 0.0) 1f else -1f)
        oRoll = roll - SPIN * (if (xd >= 0.0) 1f else -1f)
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = WoggakVineShedParticle(level, x, y, z, xd, yd, zd, sprites)
    }

    private companion object {
        const val GRAVITY = 0.5f
        const val SIZE_MIN = 0.06f
        const val SIZE_RANGE = 0.05f
        const val LIFE_MIN = 30
        const val LIFE_RANGE = 20
        const val FADE_START = 0.5f
        const val SPIN = 0.12f
        const val TWO_PI = 6.2831855f
    }
}
