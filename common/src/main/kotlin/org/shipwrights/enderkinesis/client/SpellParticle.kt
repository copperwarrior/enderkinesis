package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.spell.SpellMote
import kotlin.math.cos
import kotlin.math.sin

/** The spawn velocity is the starting motion. */
class SpellParticle(
    level: ClientLevel, x: Double, y: Double, z: Double,
    dx: Double, dy: Double, dz: Double,
    private val mote: SpellMote, private val sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z, dx, dy, dz) {

    private val baseSize: Float
    private val phase = random.nextFloat() * Mth.TWO_PI

    /** For [SpellMote.Motion.STAIN]: the landing age, or -1 while the particle falls. */
    private var landedAt = -1

    /** For [SpellMote.Motion.ORBIT]: the spawn speed sets the centre of the circle. */
    private val orbitX = x - dz / SpellMote.ORBIT_SPIN
    private val orbitZ = z + dx / SpellMote.ORBIT_SPIN

    init {
        val rgb = if (mote.altEvery > 0 && random.nextInt(mote.altEvery) == 0) mote.rgbAlt else mote.rgb
        rCol = ((rgb shr 16) and 0xFF) / 255f
        gCol = ((rgb shr 8) and 0xFF) / 255f
        bCol = (rgb and 0xFF) / 255f
        baseSize = mote.size / 2f * (0.85f + random.nextFloat() * 0.3f)
        quadSize = baseSize
        lifetime = (mote.life * (0.8f + random.nextFloat() * 0.4f)).toInt().coerceAtLeast(1)
        gravity = mote.gravity
        friction = mote.friction
        hasPhysics = mote.gravity > 0f
        alpha = mote.alpha
        if (mote.motion == SpellMote.Motion.STAIN) sprite = sprites.get(0, 1)
        else if (mote.animated) setSpriteFromAge(sprites) else pickSprite(sprites)
        // Set this last. The vanilla constructor changes the velocity at random.
        xd = dx; yd = dy; zd = dz
    }

    override fun tick() {
        if (landedAt >= 0) {
            xo = x; yo = y; zo = z
            if (age++ >= lifetime) remove()
            val dry = 1f - smooth((age - landedAt - STAIN_WET_TICKS).toFloat() / (lifetime - landedAt - STAIN_WET_TICKS).coerceAtLeast(1))
            alpha = 0.95f * dry
            return
        }
        super.tick()
        if (AegisContacts.anyFrames()) stopAtAegis()
        val f = (age.toFloat() / lifetime).coerceIn(0f, 1f)
        if (mote.animated && mote.motion != SpellMote.Motion.STAIN) setSpriteFromAge(sprites)
        quadSize *= mote.grow
        alpha = mote.alpha * (1f - f * (1f - mote.fadeTo))
        when (mote.motion) {
            SpellMote.Motion.JITTER -> {
                xd += (random.nextFloat() - 0.5f) * JITTER
                yd += (random.nextFloat() - 0.5f) * JITTER
                zd += (random.nextFloat() - 0.5f) * JITTER
            }
            SpellMote.Motion.FLOAT -> {
                xd += sin(age * 0.4f + phase) * SWAY
                zd += cos(age * 0.35f + phase * 1.3f) * SWAY
            }
            SpellMote.Motion.STAIN -> if (onGround) {
                landedAt = age
                xd = 0.0; yd = 0.0; zd = 0.0
                sprite = sprites.get(1, 1)
                rCol = 0x7A / 255f; gCol = 0x0A / 255f; bCol = 0x12 / 255f
                alpha = 0.95f
            }
            SpellMote.Motion.ORBIT -> {
                val rx = x - orbitX
                val rz = z - orbitZ
                val c = cos(SpellMote.ORBIT_SPIN)
                val s = sin(SpellMote.ORBIT_SPIN)
                xd = orbitX + rx * c - rz * s - x
                zd = orbitZ + rx * s + rz * c - z
            }
            SpellMote.Motion.PLAIN -> {}
        }
    }

    /** An Aegis blocks spells, so spell particles also stop at its face. */
    private fun stopAtAegis() {
        val (at, n) = AegisContacts.stopAt(Vec3(x, y, z), Vec3(xo, yo, zo)) ?: return
        setPos(at.x, at.y, at.z)
        val v = Vec3(xd, yd, zd)
        val slide = v.subtract(n.scale(v.dot(n))).scale(0.3)
        xd = slide.x; yd = slide.y - 0.01; zd = slide.z
        gravity = maxOf(gravity, 0.3f)
        lifetime = minOf(lifetime, age + 10)
    }

    override fun render(consumer: VertexConsumer, camera: Camera, partialTick: Float) {
        if (landedAt < 0) return super.render(consumer, camera, partialTick)
        val cam = camera.position
        val px = (x - cam.x).toFloat(); val py = (y - cam.y + 0.01).toFloat(); val pz = (z - cam.z).toFloat()
        val s = quadSize
        val c = cos(phase) * s; val n = sin(phase) * s
        val light = getLightColor(partialTick)
        fun put(dx: Float, dz: Float, u: Float, v: Float) {
            val rx = dx * c - dz * n; val rz = dx * n + dz * c
            consumer.vertex((px + rx).toDouble(), py.toDouble(), (pz + rz).toDouble()).uv(u, v).color(rCol, gCol, bCol, alpha).uv2(light).endVertex()
        }
        put(-1f, 1f, u0, v1); put(1f, 1f, u1, v1); put(1f, -1f, u1, v0); put(-1f, -1f, u0, v0)
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    override fun getLightColor(partialTick: Float): Int =
        if (mote.glows) LightTexture.FULL_BRIGHT else super.getLightColor(partialTick)

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    class Provider(private val sprites: SpriteSet, private val mote: SpellMote) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double,
        ): Particle = SpellParticle(level, x, y, z, dx, dy, dz, mote, sprites)
    }

    private companion object {
        const val JITTER = 0.02f
        const val SWAY = 0.004
        /** Ticks before a stain starts to dry. */
        const val STAIN_WET_TICKS = 20
    }
}
