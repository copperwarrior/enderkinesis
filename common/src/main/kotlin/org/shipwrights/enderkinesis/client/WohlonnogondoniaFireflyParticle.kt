package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wohlonnogondonia ambient firefly.
 *
 * Each particle:
 *  - **Tints light teal** — picked from Wohlon's palette family (a brighter sibling of the
 *    `foliage_color`/`grass_color` dark teal in the biome JSON) so the cloud reads as part
 *    of the same world rather than imported.
 *  - **Orbits its spawn anchor** — slow circular drift around the block it was born next
 *    to. Two independent phases (XZ orbit + Y wobble) and a small random drift make a
 *    sparse population look organic rather than mechanical.
 *  - **Flickers** — alpha is a sinusoidal pulse with a per-particle phase, squared so the
 *    peaks are sharp ("blip on, blip off") rather than a continuous shimmer. Period ≈ 1.5 s
 *    matches the natural cadence of real-world fireflies. A separate envelope fades the
 *    flicker in at spawn and out at death so particles don't pop.
 *  - **Always full-bright** — vanilla particle lighting would dim them in shadow under the
 *    canopy, defeating the "glowing" reading. `getLightColor` is pinned to LightTexture
 *    fullbright so they look the same in a dark grove and in open mud flats.
 *
 * Spawned by [WohlonnogondoniaFireflies] at low density on the client, only while the
 * local player is in Wohlon.
 */
open class WohlonnogondoniaFireflyParticle(
    level: ClientLevel,
    private val anchorX: Double,
    private val anchorY: Double,
    private val anchorZ: Double,
    private val sprites: SpriteSet,
) : TextureSheetParticle(level, anchorX, anchorY, anchorZ, 0.0, 0.0, 0.0) {

    /** Per-particle phase so neighbouring fireflies blink out of sync. */
    private val flickerPhase: Float = random.nextFloat() * (Math.PI.toFloat() * 2f)
    private val orbitPhase: Float = random.nextFloat() * (Math.PI.toFloat() * 2f)
    private val orbitRadius: Float = 0.35f + random.nextFloat() * 0.30f
    private val orbitOmega: Float = 0.045f + random.nextFloat() * 0.025f
    private val yWobbleOmega: Float = 0.030f + random.nextFloat() * 0.020f
    private val yWobbleAmp: Float = 0.15f + random.nextFloat() * 0.15f

    private var lastFrame: Int = -1

    init {
        // Light teal — brighter cousin of the Wohlon biome foliage colour
        // (#2E4D59 in the biome JSON). Approx RGB (142, 212, 200) — sits in
        // the same hue family but with enough lift to read as a glow.
        rCol = 0.557f
        gCol = 0.831f
        bCol = 0.784f
        alpha = 0.0f

        quadSize = 0.04f + random.nextFloat() * 0.03f

        gravity = 0.0f
        hasPhysics = false

        // Tiny initial drift jitter; the orbit math below dominates motion.
        xd = 0.0
        yd = 0.0
        zd = 0.0

        // ~15–30 s lifetime. At the low spawn rate set by
        // [WohlonnogondoniaFireflies] this yields a steady-state population
        // of single-digit fireflies in the player's visible volume.
        lifetime = 300 + random.nextInt(300)

        setSpriteFromIndex(0)
    }

    override fun tick() {
        xo = x; yo = y; zo = z
        if (age++ >= lifetime) { remove(); return }

        // Anchor-relative orbit. Each particle traces a slow lazy ellipse
        // around its spawn point rather than wandering off into the void.
        val tA = age.toFloat()
        val targetX = anchorX + cos(tA * orbitOmega + orbitPhase) * orbitRadius
        val targetZ = anchorZ + sin(tA * orbitOmega + orbitPhase) * orbitRadius
        val targetY = anchorY + sin(tA * yWobbleOmega + flickerPhase) * yWobbleAmp

        // Soft pull toward the orbit target — gives a smooth path without
        // teleporting the particle.
        x += (targetX - x) * 0.10
        y += (targetY - y) * 0.10
        z += (targetZ - z) * 0.10

        // Animate the sprite at the same low frequency as the orbit so the
        // glitter frame doesn't snap mid-pulse.
        val frame = ((tA / lifetime.toFloat()) * 8f).toInt().coerceIn(0, 7)
        if (frame != lastFrame) {
            lastFrame = frame
            setSprite(sprites.get(frame, SPRITE_SET_SIZE))
        }

        // Flicker:
        //  - envelope: fade in over the first 8 % of life, fade out the
        //    last 15 %, so spawn/death don't pop.
        //  - pulse: sin² of (age × ~0.2 rad/tick + phase) — period ≈ 1.5 s,
        //    sharp peaks ("blip"), random per-particle phase. Floor at 0.2
        //    so a firefly is always at least faintly visible between blips.
        val ageNorm = tA / lifetime.toFloat()
        val envelope = when {
            ageNorm < 0.08f -> ageNorm / 0.08f
            ageNorm > 0.85f -> (1f - ageNorm) / 0.15f
            else            -> 1f
        }
        val pulseOmega = 0.21f
        val s = sin(tA * pulseOmega + flickerPhase)
        val pulse = (s * 0.5f + 0.5f).let { it * it }   // sin², sharp peaks
        alpha = envelope * (0.20f + pulse * 0.80f)
    }

    /** Pin to full-bright so the particles read as "glowing" in any light. */
    override fun getLightColor(partialTick: Float): Int = 0xF000F0

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    private fun setSpriteFromIndex(frame: Int) {
        setSprite(sprites.get(frame, SPRITE_SET_SIZE))
    }

    companion object {
        private const val SPRITE_SET_SIZE = 8
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = WohlonnogondoniaFireflyParticle(level, x, y, z, sprites)
    }
}
