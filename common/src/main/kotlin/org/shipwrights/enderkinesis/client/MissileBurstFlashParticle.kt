package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.Mth

/**
 * 1:1 port of vanilla `FireworkParticles$OverlayParticle` — the bright pop the
 * Starter spawns at the burst centre alongside the sparks. Same `lifetime = 4`,
 * same sin-curve quad size (grows then shrinks, peak ~7.1), same linear alpha
 * ramp (`0.6 - (age + pt - 1) * 0.25 * 0.5`). The vanilla flash is tinted with
 * `colors[0]` via a per-spawn `setColor` call; we bake the OUTLINE pink in here
 * since we can't pass it through a `SimpleParticleType`.
 */
class MissileBurstFlashParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z) {

    init {
        lifetime = 4
        pickSprite(sprites)
        rCol = OUTLINE_R; gCol = OUTLINE_G; bCol = OUTLINE_B
    }

    override fun getQuadSize(scaleFactor: Float): Float =
        7.1f * Mth.sin((age.toFloat() + scaleFactor - 1f) * 0.25f * Mth.PI)

    override fun render(buffer: VertexConsumer, camera: Camera, partialTick: Float) {
        setAlpha(0.6f - (age.toFloat() + partialTick - 1f) * 0.25f * 0.5f)
        super.render(buffer, camera, partialTick)
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            vx: Double, vy: Double, vz: Double,
        ): Particle = MissileBurstFlashParticle(level, x, y, z, sprites)
    }

    companion object {
        private const val OUTLINE_R = 250f / 255f
        private const val OUTLINE_G = 215f / 255f
        private const val OUTLINE_B = 240f / 255f
    }
}
