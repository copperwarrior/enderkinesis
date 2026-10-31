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
import org.joml.Vector3f

/** A flat glyph, one block wide, that stays where the pen wrote it. Call [orient] after the engine makes it. */
class OarscribeGlyphParticle(level: ClientLevel, x: Double, y: Double, z: Double) :
    TextureSheetParticle(level, x, y, z) {

    private val normal = Vector3f(0f, 1f, 0f)
    private val across = Vector3f(1f, 0f, 0f)
    private val up = Vector3f(0f, 0f, -1f)

    init {
        // Same warm yellow as SselithGlyphParticle.
        val j = random.nextFloat() * 0.2f + 0.8f
        rCol = j
        gCol = j * 0.85f
        bCol = j * 0.2f
        lifetime = LIFETIME_TICKS
        hasPhysics = false
        gravity = 0f
        xd = 0.0
        yd = 0.0
        zd = 0.0
        setSize(1f, 1f)
    }

    /** [normal] is out of the glyph face and [across] runs along the line; both are world-frame unit vectors. */
    fun orient(normal: Vector3f, across: Vector3f) {
        this.normal.set(normal)
        this.across.set(across)
        normal.cross(across, up)
    }

    override fun render(buffer: VertexConsumer, camera: Camera, partialTick: Float) {
        val cam = camera.position
        val cx = (x - cam.x).toFloat()
        val cy = (y - cam.y).toFloat()
        val cz = (z - cam.z).toFloat()
        alpha = 1f - ((age + partialTick) / lifetime).coerceIn(0f, 1f)
        val light = getLightColor(partialTick)
        // Wind the quad toward the camera, so that it shows from both sides whether or not culling is on.
        val front = normal.x * cx + normal.y * cy + normal.z * cz < 0f
        val side = if (front) 1f else -1f
        vertex(buffer, cx, cy, cz, -side, -1f, if (front) u0 else u1, v1, light)
        vertex(buffer, cx, cy, cz, side, -1f, if (front) u1 else u0, v1, light)
        vertex(buffer, cx, cy, cz, side, 1f, if (front) u1 else u0, v0, light)
        vertex(buffer, cx, cy, cz, -side, 1f, if (front) u0 else u1, v0, light)
    }

    @Suppress("LongParameterList") // One vertex needs its centre, corner, uv, and light.
    private fun vertex(
        buffer: VertexConsumer, cx: Float, cy: Float, cz: Float,
        a: Float, b: Float, u: Float, v: Float, light: Int,
    ) {
        buffer.vertex(
            (cx + (across.x * a + up.x * b) * HALF).toDouble(),
            (cy + (across.y * a + up.y * b) * HALF).toDouble(),
            (cz + (across.z * a + up.z * b) * HALF).toDouble(),
        ).uv(u, v).color(rCol, gCol, bCol, alpha).uv2(light).endVertex()
    }

    override fun getLightColor(partialTick: Float): Int = 0xF000F0

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = OarscribeGlyphParticle(level, x, y, z).also { it.pickSprite(sprites) }
    }

    private companion object {
        const val LIFETIME_TICKS = 100
        const val HALF = 0.5f
    }
}
