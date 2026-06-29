package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType

/** Staff-of-Sundering stage-1 "ender wisp" — uses the vanilla portal
 *  particle sprite (the wispy ender silhouette) but **tinted warm pale
 *  orange** so it reads as a sun-hot streak rather than the vanilla
 *  purple-lavender ender portal hue.
 *
 *  Motion is intentionally **simple constant per-tick velocity** — none of
 *  the `f * f * 2 - f` non-linear scaling vanilla `PortalParticle` uses,
 *  which makes the visible speed change over lifetime and disguises true
 *  forward motion. Here, `(vx, vy, vz)` passed to `addParticle` is the
 *  per-tick world-space displacement, so passing `viewVec × speedPerTick`
 *  produces an unambiguous straight-line trail along the beam axis. */
class SunderingBeamParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    vx: Double, vy: Double, vz: Double,
) : TextureSheetParticle(level, x, y, z) {

    init {
        // Warm pale-orange tint. Each particle samples a small brightness
        // range so the cloud isn't monochrome. Multiplicative against the
        // portal texture: R kept high, G ≈ 75 %, B ≈ 45 % — kills the
        // vanilla lavender and pushes the silhouette toward an ember hue.
        val scale = random.nextFloat() * 0.25f + 0.75f                    // 0.75..1.0
        rCol = scale
        gCol = scale * 0.75f
        bCol = scale * 0.45f
        setAlpha(0f)                                                       // fade in via tick()

        xd = vx; yd = vy; zd = vz

        // Slightly smaller than the default portal sprite — the beam
        // packs lots of these and full-size particles read as too soft.
        quadSize *= 0.6f + random.nextFloat() * 0.4f
        // Shorter than vanilla portal (40..50). At ~24 ticks the streak
        // travels ~5 b at speedPerTick 0.2 — visible without overshooting
        // typical sight-line beam targets.
        lifetime = 20 + random.nextInt(8)
        gravity = 0f
        hasPhysics = false
    }

    override fun tick() {
        xo = x; yo = y; zo = z
        if (age++ >= lifetime) {
            remove()
            return
        }
        // Constant world-space step — vx/vy/vz are blocks-per-tick.
        x += xd
        y += yd
        z += zd
        // Smooth fade in / out — sin(πf) gives 0 at age 0, peak at
        // age = lifetime / 2, 0 at age = lifetime.
        val f = age.toFloat() / lifetime.toFloat()
        setAlpha(Math.sin(f * Math.PI).toFloat().coerceIn(0f, 1f))
    }

    /** Emissive — the particle should glow against any backdrop. */
    override fun getLightColor(partialTick: Float): Int = 0xF000F0

    override fun getRenderType(): ParticleRenderType =
        ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle {
            val p = SunderingBeamParticle(level, x, y, z, xd, yd, zd)
            p.pickSprite(sprites)
            return p
        }
    }
}
