package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType

/**
 * Ambient mote for [org.shipwrights.enderkinesis.dimension.SselithRepertory]. Each spawn is
 * independently one of two looks:
 *
 *  - **Smoke** (50%): the diffuse `generic_0..7` smoke puff sprites, slightly larger and
 *    more transparent. Reads as dust kicked up in afternoon sunlight.
 *  - **Light** (50%): the `glitter_0..7` sparkle set — same sprites vanilla `end_rod` uses,
 *    bright soft points. Reads as drifting motes of light.
 *
 * Both halves share the same yellow tint, downward gravity-driven motion, and a slow
 * sprite-frame cycle ([setSpriteFromIndex] each tick) for the gentle animation that keeps
 * the cloud feeling alive.
 *
 * Sprite layout: indices 0–7 are smoke, 8–15 are glitter; the [smokeMode] flag picks the
 * window for both the initial sprite and the per-tick animation.
 *
 * Spawned by the biome ambient-particle system (configured in
 * `data/enderkinesis/worldgen/biome/sselith_repertory.json`).
 */
class SselithMoteParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    private val sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z, 0.0, 0.0, 0.0) {

    /** Last sprite frame index passed to [setSpriteFromIndex]. Avoids
     *  the per-tick [SpriteSet] hash lookup when the frame hasn't changed
     *  (the frame only advances 8 times over the particle's full lifetime). */
    private var lastFrame: Int = -1

    /** Which half of the sprite set this mote uses. Picked once at spawn. */
    private val smokeMode: Boolean = random.nextFloat() < 0.5f

    /** Base alpha for this mote — smoke reads softer, light reads brighter. */
    private val baseAlpha: Float = if (smokeMode) 0.45f else 0.85f

    init {
        // Warm yellow tint applied to the (white) sprites. RGB factors are the on-screen
        // colour; in smoke mode they tint the soft puff, in light mode they tint the glitter
        // point.
        rCol = 1.00f
        gCol = 0.92f
        bCol = 0.55f
        alpha = 0.0f  // start invisible; fade-in in tick() ramps to baseAlpha

        // Size: smoke is bigger (diffuse), light is tinier (bright point).
        quadSize = if (smokeMode) {
            0.10f + random.nextFloat() * 0.06f
        } else {
            0.05f + random.nextFloat() * 0.04f
        }

        // Increased fall rate vs. the previous version. We enable vanilla `gravity` for a
        // gentle acceleration on top of the initial downward velocity — over a long lifetime
        // motes pick up a bit of speed, which keeps them from looking stuck in mid-air. The
        // value is small (0.04 — a fraction of vanilla smoke's 0.1) so they still drift, not
        // plummet.
        gravity = 0.04f
        // Block collision intentionally OFF for the ambient mote: with hundreds of
        // these in flight as the biome's atmospheric haze, per-particle terrain
        // collision is a measurable hit. The cataloger's localised dust trail uses
        // a separate `SselithDustParticle` that DOES collide (small count, short
        // lifetime, worth the cost there).
        hasPhysics = false

        // Initial velocities:
        //  - yd: starting ~3× the previous version's drift. `gravity` will keep nudging it
        //    further downward over time.
        //  - xd/zd: small horizontal jitter so motes don't all fall straight down.
        xd = (random.nextDouble() - 0.5) * 0.006
        yd = -(0.005 + random.nextDouble() * 0.008)
        zd = (random.nextDouble() - 0.5) * 0.006

        // Lifetime: still long enough that the sparse spawn rate accumulates into a visible
        // ambient cloud. Smoke lives a bit longer than light to compensate for its softer
        // alpha.
        lifetime = if (smokeMode) {
            500 + random.nextInt(300)
        } else {
            400 + random.nextInt(250)
        }

        setSpriteFromIndex(0)
    }

    override fun tick() {
        super.tick()
        // Slight horizontal wobble. Damp toward zero so motes don't accelerate sideways over
        // their long lifetime.
        xd = (xd * 0.96) + (random.nextDouble() - 0.5) * 0.0008
        zd = (zd * 0.96) + (random.nextDouble() - 0.5) * 0.0008

        // Cycle the sprite frame across the lifetime — 8 frames per half-set.
        // Only call setSpriteFromIndex when the frame actually advances; the
        // frame changes at most 8 times over the particle's life, so the
        // SpriteSet hash lookup is otherwise wasted work.
        val frame = ((age.toFloat() / lifetime.toFloat()) * 8f).toInt().coerceIn(0, 7)
        if (frame != lastFrame) { lastFrame = frame; setSpriteFromIndex(frame) }

        // Fade in over the first 10% of lifetime, fade out over the last 25% — both ends
        // keep motes from popping in/out and let the cumulative cloud breathe.
        val ageF = age.toFloat() / lifetime.toFloat()
        alpha = when {
            ageF < 0.10f -> baseAlpha * (ageF / 0.10f)
            ageF > 0.75f -> baseAlpha * ((1.0f - ageF) / 0.25f)
            else         -> baseAlpha
        }
    }

    /** Map a frame index 0..7 into the correct half of the 16-sprite set. */
    private fun setSpriteFromIndex(frame: Int) {
        val base = if (smokeMode) 0 else 8
        setSprite(sprites.get(base + frame, SPRITE_SET_SIZE))
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    companion object {
        /** Total sprite count in `sselith_mote.json` — 8 generic + 8 glitter. */
        private const val SPRITE_SET_SIZE = 16
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = SselithMoteParticle(level, x, y, z, sprites)
    }
}
