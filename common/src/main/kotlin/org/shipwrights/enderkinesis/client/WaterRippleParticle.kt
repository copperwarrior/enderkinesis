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

/** A flat ring on the water surface that grows and fades. Spawn it just above the surface. */
class WaterRippleParticle(level: ClientLevel, x: Double, y: Double, z: Double) :
    TextureSheetParticle(level, x, y, z) {

    private val endRadius = END_RADIUS * (1f - RADIUS_SPREAD / 2f + random.nextFloat() * RADIUS_SPREAD)

    init {
        lifetime = LIFETIME_TICKS
        hasPhysics = false
        gravity = 0f
        xd = 0.0
        yd = 0.0
        zd = 0.0
    }

    override fun render(buffer: VertexConsumer, camera: Camera, partialTick: Float) {
        val t = ((age + partialTick) / lifetime).coerceIn(0f, 1f)
        val grow = 1f - (1f - t) * (1f - t)
        val r = START_RADIUS + (endRadius - START_RADIUS) * grow
        alpha = MAX_ALPHA * (1f - t)
        val cam = camera.position
        val cx = (x - cam.x).toFloat()
        val cy = (y - cam.y).toFloat()
        val cz = (z - cam.z).toFloat()
        val light = getLightColor(partialTick)
        // Wind the quad toward the camera, so that it shows from above and from under the water.
        val s = if (cy < 0f) 1f else -1f
        vertex(buffer, cx - r, cy, cz - r * s, u0, v0, light)
        vertex(buffer, cx - r, cy, cz + r * s, u0, v1, light)
        vertex(buffer, cx + r, cy, cz + r * s, u1, v1, light)
        vertex(buffer, cx + r, cy, cz - r * s, u1, v0, light)
    }

    @Suppress("LongParameterList") // One vertex needs its position, uv, and light.
    private fun vertex(buffer: VertexConsumer, x: Float, y: Float, z: Float, u: Float, v: Float, light: Int) {
        buffer.vertex(x.toDouble(), y.toDouble(), z.toDouble()).uv(u, v).color(rCol, gCol, bCol, alpha).uv2(light)
            .endVertex()
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = WaterRippleParticle(level, x, y, z).also { it.pickSprite(sprites) }
    }

    private companion object {
        const val LIFETIME_TICKS = 24
        const val START_RADIUS = 0.08f
        const val END_RADIUS = 0.6f
        const val RADIUS_SPREAD = 0.3f
        const val MAX_ALPHA = 0.7f
    }
}
