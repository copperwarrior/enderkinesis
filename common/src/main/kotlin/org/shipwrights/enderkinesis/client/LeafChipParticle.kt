package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

class LeafChipParticle(
    level: ClientLevel, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double,
) : Particle(level, x, y, z, dx, dy, dz) {

    private val leaves: TextureAtlasSprite = Minecraft.getInstance().getTextureAtlas(BLOCK_ATLAS)
        .apply(ResourceLocation("block/oak_leaves"))
    private val mask = SpellArt.LEAF_MASKS[random.nextInt(SpellArt.LEAF_MASKS.size)]
    // Texels.
    private val ox = random.nextInt(11)
    private val oy = random.nextInt(11)
    private val size = 0.16f + 0.06f * random.nextFloat()
    private val quarter = random.nextInt(4) * Mth.HALF_PI
    private val phase = random.nextFloat() * Mth.TWO_PI

    init {
        val rgb = if (random.nextInt(3) == 0) 0x4A9A25 else 0x6FBF3A
        rCol = ((rgb shr 16) and 0xFF) / 255f
        gCol = ((rgb shr 8) and 0xFF) / 255f
        bCol = (rgb and 0xFF) / 255f
        lifetime = 22 + random.nextInt(10)
        gravity = 0.15f
        friction = 0.9f
        hasPhysics = true
        xd = dx; yd = dy; zd = dz
    }

    override fun tick() {
        super.tick()
        xd += sin(age * 0.2f + phase) * 0.006
        zd += cos(age * 0.17f + phase) * 0.004
        val f = age.toFloat() / lifetime
        alpha = if (f < 0.75f) 1f else 1f - (f - 0.75f) / 0.25f
    }

    override fun render(consumer: VertexConsumer, camera: Camera, partialTick: Float) {
        val cam = camera.position
        val cx = (Mth.lerp(partialTick.toDouble(), xo, x) - cam.x).toFloat()
        val cy = (Mth.lerp(partialTick.toDouble(), yo, y) - cam.y).toFloat()
        val cz = (Mth.lerp(partialTick.toDouble(), zo, z) - cam.z).toFloat()
        val roll = quarter + 0.35f * sin((age + partialTick) * 0.15f + phase)
        val right = Vector3f(cos(roll), sin(roll), 0f).rotate(camera.rotation())
        val up = Vector3f(-sin(roll), cos(roll), 0f).rotate(camera.rotation())
        val px = size / 5f
        val light = getLightColor(partialTick)
        val du = (leaves.u1 - leaves.u0) / 16f
        val dv = (leaves.v1 - leaves.v0) / 16f
        for (row in 0 until 5) for (col in 0 until 5) {
            val ch = mask[row][col]
            if (ch == '.') continue
            val shade = if (ch == 's') 0.65f else 1f
            val u = leaves.u0 + (ox + col) * du
            val v = leaves.v0 + (oy + row) * dv
            // Row 0 is at the top.
            val l = (col - 2.5f) * px; val r = l + px
            val t = (2.5f - row) * px; val b = t - px
            fun put(a: Float, c: Float, uu: Float, vv: Float) {
                consumer.vertex(
                    (cx + right.x * a + up.x * c).toDouble(), (cy + right.y * a + up.y * c).toDouble(), (cz + right.z * a + up.z * c).toDouble(),
                ).uv(uu, vv).color(rCol * shade, gCol * shade, bCol * shade, alpha).uv2(light).endVertex()
            }
            put(l, b, u, v + dv); put(l, t, u, v); put(r, t, u + du, v); put(r, b, u + du, v + dv)
        }
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.TERRAIN_SHEET

    class Provider : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double,
        ): Particle = LeafChipParticle(level, x, y, z, dx, dy, dz)
    }
}
