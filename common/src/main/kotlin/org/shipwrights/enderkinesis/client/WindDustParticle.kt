package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType

/**
 * Incantation of Winds dust. Two differences from [SselithDustParticle]
 * that justify the separate type: it spawns at FULL alpha (no fade-in —
 * the incantation's dust must read as instantaneous), and it RESPECTS the
 * spawn velocity instead of overriding it (the gale mode is literally the
 * velocity the server gives it).
 *
 *  - [Mode.HAND]: tiny mote rising from the casting hand. Weightless,
 *    high drag, brief.
 *  - [Mode.GUST]: blast mote shooting downrange with the wind. Larger,
 *    near-zero drag so it keeps sailing, short-lived.
 *  - [Mode.EIDETIC]: Eidetic Bookmark cast mote. Looks like HAND, but lives
 *    longer, keeps climbing, and wanders more as it ages.
 */
class WindDustParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    xSpeed: Double, ySpeed: Double, zSpeed: Double,
    private val sprites: SpriteSet,
    private val mode: Mode,
) : TextureSheetParticle(level, x, y, z, 0.0, 0.0, 0.0) {

    enum class Mode { HAND, GUST, EIDETIC }

    /** GUST mixes smoke + glitter sprites for texture; HAND is always the
     *  bright glitter half so the tiny motes stay visible. */
    private val smokeMode: Boolean = mode == Mode.GUST && random.nextFloat() < 0.5f

    private val baseAlpha: Float

    init {
        rCol = 1.00f
        gCol = 0.92f
        bCol = 0.55f
        xd = xSpeed
        yd = ySpeed
        zd = zSpeed
        gravity = 0.0f
        hasPhysics = false

        when (mode) {
            Mode.HAND -> {
                quadSize = 0.015f + random.nextFloat() * 0.015f
                lifetime = 14 + random.nextInt(8)
                baseAlpha = 0.9f
                friction = 0.96f
            }
            Mode.GUST -> {
                quadSize = 0.08f + random.nextFloat() * 0.06f
                lifetime = 14 + random.nextInt(8)
                baseAlpha = 0.85f
                friction = 0.99f
            }
            Mode.EIDETIC -> {
                quadSize = 0.03f + random.nextFloat() * 0.03f
                lifetime = 34 + random.nextInt(16)
                baseAlpha = 0.9f
                friction = 0.985f
            }
        }
        // Full alpha at spawn — no fade-in.
        alpha = baseAlpha
        setSpriteFromIndex(if (mode == Mode.EIDETIC) LAST_FRAME else 0)
    }

    override fun tick() {
        super.tick()
        // GUST: the wind carries the gust level — vertical velocity decays
        // fast while horizontal speed is kept, so a blast fired up/down
        // from the hand curves into the yaw-locked wind within ~5 ticks.
        if (mode == Mode.GUST) {
            yd *= GUST_VERTICAL_DECAY
        }
        val ageF = age.toFloat() / lifetime.toFloat()
        if (mode == Mode.EIDETIC) {
            // The jitter grows with age, so the ring holds its shape at first and scatters as it climbs.
            val jitter = EIDETIC_JITTER * (0.3 + ageF)
            xd += (random.nextDouble() - 0.5) * jitter
            yd += EIDETIC_LIFT + (random.nextDouble() - 0.5) * jitter * 0.5
            zd += (random.nextDouble() - 0.5) * jitter
        }
        if (mode != Mode.EIDETIC) setSpriteFromIndex((ageF * 8f).toInt().coerceIn(0, LAST_FRAME))
        // Fade OUT only, over the last 30% of life.
        alpha = if (ageF > 0.7f) baseAlpha * ((1.0f - ageF) / 0.3f) else baseAlpha
    }

    private fun setSpriteFromIndex(frame: Int) {
        val base = if (smokeMode) 0 else 8
        // `SpriteSet.get(age, lifetime)` gives index `age * (size - 1) / lifetime`.
        setSprite(sprites.get(base + frame, SPRITE_SET_SIZE - 1))
    }

    // The glitter frames change size from 1 to 7 px; over a long life each frame shows long enough to read as
    // a size step, so EIDETIC keeps the largest frame and shrinks the quad smoothly instead.
    override fun getQuadSize(partialTick: Float): Float {
        if (mode != Mode.EIDETIC) return quadSize
        val aged = ((age + partialTick) / lifetime).coerceIn(0f, 1f)
        return quadSize * (1f - (1f - EIDETIC_END_SCALE) * aged)
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    /** Hand and Eidetic dust are emissive — matches the full-bright first-person motes. */
    override fun getLightColor(partialTick: Float): Int =
        if (mode != Mode.GUST) {
            net.minecraft.client.renderer.LightTexture.FULL_BRIGHT
        } else {
            super.getLightColor(partialTick)
        }

    companion object {
        /** 8 generic + 8 glitter, same atlas layout as `sselith_dust.json`. */
        private const val SPRITE_SET_SIZE = 16

        /** Per-tick vertical velocity decay for GUST — flattens the initial
         *  view-aligned blast into the horizontal wind. */
        private const val GUST_VERTICAL_DECAY = 0.75

        private const val EIDETIC_JITTER = 0.03
        private const val EIDETIC_LIFT = 0.0005

        private const val LAST_FRAME = 7

        // glitter_7 is 7 px across; a mote ends at the 1 px of glitter_0.
        private const val EIDETIC_END_SCALE = 1f / 7f
    }

    class Provider(
        private val sprites: SpriteSet,
        private val mode: Mode,
    ) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = WindDustParticle(level, x, y, z, xd, yd, zd, sprites, mode)
    }
}
