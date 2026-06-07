package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.particles.SimpleParticleType
import kotlin.math.sqrt

/**
 * Exhaust puff for the [ShulkerPufferBlockEntity]. Distance-keyed scale curve:
 *
 *  - Spawns at [MIN_SIZE], moving in the nozzle direction with the velocity the server
 *    sent it. Low friction + zero gravity so it actually travels.
 *  - Each tick we measure the world-space distance from spawn point; while that's below
 *    [GROW_DISTANCE] (1 block per spec), `quadSize` lerps from [MIN_SIZE] up to
 *    [maxQuadSize]. This is what gives the exhaust its "starts as a wisp, blossoms into a
 *    plume" read instead of vanilla `DRAGON_BREATH`'s lazy slow cloud.
 *  - Once distance ≥ [GROW_DISTANCE], `quadSize` linearly fades to 0 over the remaining
 *    lifetime. Combined with the very short overall [lifetime], this produces a tight
 *    visual puff instead of a lingering trail.
 *
 *  Colour tint is a pale dragon's-breath purple (the sprite atlas already gives us the
 *  dragon-breath swirl shape; the RGB is what makes it read as "magic exhaust" rather than
 *  "smoke").
 */
class ShulkerPufferParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    sprites: SpriteSet,
    vx: Double, vy: Double, vz: Double,
) : TextureSheetParticle(level, x, y, z) {

    private val startX = x
    private val startY = y
    private val startZ = z

    /** Per-particle peak size, jittered ±20 % around [MAX_SIZE_BASE] so a stream of puffs
     *  doesn't look like a regular pulse train. */
    private val maxQuadSize: Float = MAX_SIZE_BASE * (0.8f + random.nextFloat() * 0.4f)

    init {
        pickSprite(sprites)
        xd = vx; yd = vy; zd = vz
        gravity = 0f
        // Low friction — we want the particle to actually travel ≥ 1 block before the size
        // curve transitions to fade. Vanilla's default `0.91` decays too fast at our
        // velocities (~0.3 b/t initial → ~1 b reached in 4 ticks at f=0.91 vs 3 at f=0.97).
        friction = 0.97f
        hasPhysics = false                                        // no collision; we're exhaust
        // Very short lifetime per spec ("a bit less lifetime"). 8–12 ticks is "tight burst,
        // not a cloud" — vanilla DRAGON_BREATH runs 60+, which is why a thruster looks like
        // an idling chimney instead of an active engine.
        lifetime = 8 + random.nextInt(5)
        quadSize = MIN_SIZE
        // Initial tint = ender green. The [tick] method lerps this toward purple over the
        // particle's lifetime; we only need to seed the start state here.
        rCol = GREEN_R
        gCol = GREEN_G
        bCol = GREEN_B
        alpha = 0.85f
    }

    override fun tick() {
        super.tick()
        val dx = x - startX
        val dy = y - startY
        val dz = z - startZ
        val dist = sqrt(dx * dx + dy * dy + dz * dz)

        quadSize = if (dist < GROW_DISTANCE) {
            // Phase A: linear grow from MIN_SIZE → maxQuadSize over 0..GROW_DISTANCE blocks.
            val t = (dist / GROW_DISTANCE).toFloat()
            MIN_SIZE + (maxQuadSize - MIN_SIZE) * t
        } else {
            // Phase B: linear fade from maxQuadSize → 0 over remaining lifetime. Using age
            // rather than "distance past GROW_DISTANCE" because friction decays velocity
            // toward zero, so a distance-driven fade can stall before reaching 0.
            val ageFrac = age.toFloat() / lifetime.toFloat()
            (maxQuadSize * (1f - ageFrac)).coerceAtLeast(0f)
        }

        // Colour fade: ender green → purple as the particle ages. Linear lerp on RGB —
        // simple but reads correctly because both endpoints sit roughly the same brightness
        // (no value crash at the midpoint). Greenness drives both the colour and the
        // emissive amount in [getLightColor].
        val lifeProgress = (age.toFloat() / lifetime.toFloat()).coerceIn(0f, 1f)
        val greenAmount = 1f - lifeProgress
        rCol = GREEN_R + (PURPLE_R - GREEN_R) * lifeProgress
        gCol = GREEN_G + (PURPLE_G - GREEN_G) * lifeProgress
        bCol = GREEN_B + (PURPLE_B - GREEN_B) * lifeProgress
        // Cache emissive frac (= greenness) for [getLightColor] — avoids re-deriving age
        // there since the render thread can call getLightColor multiple times per game tick.
        emissiveFrac = greenAmount
    }

    /** Render-thread cache of "how green is this particle right now". Set in [tick]
     *  on the simulation thread, read in [getLightColor] when the renderer asks for the
     *  light value. 1.0 = full green / full emissive, 0.0 = full purple / ambient light. */
    @Volatile private var emissiveFrac: Float = 1.0f

    override fun getLightColor(partialTick: Float): Int {
        // Emissive while the particle is in its green phase, fading to ambient as it
        // turns purple. Block + sky light components both lerp toward 15 (full) in
        // proportion to how green the particle currently is. At greenAmount=1 this returns
        // [LightTexture.FULL_BRIGHT]; at greenAmount=0 it returns the engine's ambient
        // lookup (which is what e.g. dragon's-breath gets in vanilla — affected by torches
        // and skylight). Linear blend in between.
        val emissive = emissiveFrac
        if (emissive <= 0f) return super.getLightColor(partialTick)
        if (emissive >= 1f) return LightTexture.FULL_BRIGHT
        val ambient = super.getLightColor(partialTick)
        val ambBlock = (ambient shr 4) and 0xF
        val ambSky = (ambient shr 20) and 0xF
        val finalBlock = ambBlock + ((15 - ambBlock) * emissive).toInt()
        val finalSky = ambSky + ((15 - ambSky) * emissive).toInt()
        return LightTexture.pack(
            finalBlock.coerceIn(0, 15),
            finalSky.coerceIn(0, 15),
        )
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            vx: Double, vy: Double, vz: Double,
        ): Particle = ShulkerPufferParticle(level, x, y, z, sprites, vx, vy, vz)
    }

    companion object {
        /** Distance over which `quadSize` grows from [MIN_SIZE] to [maxQuadSize]. */
        private const val GROW_DISTANCE = 1.0

        /** Spawn size — small, so the puff visibly blossoms out of the nozzle. */
        private const val MIN_SIZE = 0.04f

        /** Base peak size before per-particle jitter (`±20 %`). */
        private const val MAX_SIZE_BASE = 0.35f

        /** Ender-green start colour. Matches the [PlanarSpiralParticle] tint at full
         *  brightness — low red, dominant green, a touch of blue → reads as "ender". The
         *  particle starts at this hue *and* emissive, fading to [PURPLE_*] over its
         *  lifetime as the emissive contribution drops to zero. */
        private const val GREEN_R = 0.15f
        private const val GREEN_G = 0.95f
        private const val GREEN_B = 0.45f

        /** Final purple. End-amethyst / dragon-breath family — high red + blue, low
         *  green. Linear RGB lerp from green to here over the particle's age. */
        private const val PURPLE_R = 0.70f
        private const val PURPLE_G = 0.25f
        private const val PURPLE_B = 0.95f
    }
}
