package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType

/** Staff-of-Sundering fire particle — used for the rotating CW + CCW tip
 *  rings and the stage-4 helical spiral. Uses the **vanilla flame sprite**
 *  for the actual fire silhouette (so it doesn't read as a flat coloured
 *  square), has **zero motion** (rotation comes from the per-tick respawn
 *  at advancing angular slot positions in [SunderingClient]), and lives
 *  ~5 ticks with a sin alpha envelope so each spawn flickers in and out.
 *
 *  Vanilla `FlameParticle` is unusable here — its lifetime is randomised
 *  to 10..40 ticks and it bakes in a small upward drift. Both would
 *  smear the ring rotation into a long persistent halo.
 *
 *  Per-spawn size is encoded in the constructor's `sizeMultiplier` arg
 *  (passed via the spawn call's `vx` slot in the [Provider], since this
 *  particle has no actual velocity to use those arguments for). That lets
 *  [SunderingClient] use one particle type for three call sites at three
 *  different visual scales — small for the inner ring, medium for the
 *  outer ring, in-between for the spiral. */
class SunderingFireParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    sizeMultiplier: Double,
) : TextureSheetParticle(level, x, y, z) {

    init {
        // No tint — the vanilla flame texture already has the orange/yellow
        // gradient baked in. Multiplying by 1, 1, 1 keeps the source colour.
        rCol = 1f; gCol = 1f; bCol = 1f
        setAlpha(0f)                                           // fade in via tick()

        xd = 0.0; yd = 0.0; zd = 0.0

        // Per-spawn size with multiplicative jitter in [0.75, 1.25] — gives
        // the cluster a hand-flickered fire look instead of stamped uniform
        // dots. Combined with the per-call [sizeMultiplier] this still
        // preserves the inner-smaller-than-outer ordering.
        val jitter = 0.75 + random.nextDouble() * 0.5
        val sized = sizeMultiplier * jitter
        quadSize *= sized.toFloat().coerceAtLeast(0.05f)

        // Short lifetime — at 5 ticks per particle, an inner ring spawning
        // 14/tick averages ~70 active particles around the ring, with the
        // newest at the current angular slot and the oldest 5 × rate rad
        // behind. The short tail is the "rotation streak" the rings show.
        lifetime = 5
        gravity = 0f
        hasPhysics = false
    }

    /** Render-time size taper — mirrors vanilla `FlameParticle.getQuadSize`
     *  (`quadSize × (1 - f² × 0.5)`), so the flame shrinks over its
     *  lifetime instead of holding a static size. The fade is gentle (50 %
     *  drop end-to-end, easing in via the squared term), so particles
     *  visibly contract as they age out of the ring trail. */
    override fun getQuadSize(partialTick: Float): Float {
        val f = (age.toFloat() + partialTick) / lifetime.toFloat()
        return quadSize * (1f - f * f * 0.5f)
    }

    override fun tick() {
        xo = x; yo = y; zo = z
        if (age++ >= lifetime) {
            remove()
            return
        }
        // Stationary — angular position is baked into the spawn coords by
        // the SunderingClient ring/spiral spawners, not derived per-tick
        // from the particle itself.
        val f = age.toFloat() / lifetime.toFloat()
        setAlpha(Math.sin(f * Math.PI).toFloat().coerceIn(0f, 1f))
    }

    override fun getLightColor(partialTick: Float): Int = 0xF000F0

    override fun getRenderType(): ParticleRenderType =
        ParticleRenderType.PARTICLE_SHEET_OPAQUE

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle {
            // `xd` slot smuggles the size multiplier — see class kdoc. yd/zd
            // are unused. Fallback default when called without an explicit
            // size (xd == 0 from a stray vanilla-flavour `addParticle` call).
            val size = if (xd > 1e-6) xd else 0.5
            val p = SunderingFireParticle(level, x, y, z, size)
            p.pickSprite(sprites)
            return p
        }
    }
}
