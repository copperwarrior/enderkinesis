package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.physics.GerstnerOcean
import org.shipwrights.enderkinesis.physics.OceanDepth
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import kotlin.math.min

/**
 * The virtual ocean's particle. Modes:
 *  - [Mode.SURFACE]: rides the shared Gerstner surface (purple → foam), collides.
 *  - [Mode.DEEP]: a faint dark-green mote drifting in the water volume.
 *  - [Mode.SPLASH]: a short ballistic foam droplet thrown up when a hull block hits the water,
 *    uses the velocity the server sent it with, falls under gravity, collides.
 */
class OceanParticle(
    level: ClientLevel,
    x: Double,
    y: Double,
    z: Double,
    sprites: SpriteSet,
    private val mode: Mode,
    vx: Double,
    vy: Double,
    vz: Double,
) : TextureSheetParticle(level, x, y, z) {

    enum class Mode { SURFACE, DEEP, SPLASH }

    private val baseX = x
    private val baseY = y
    private val baseZ = z
    private val disp = DoubleArray(3)
    private var foamBoosted = false

    /**
     * Precomputed per-wave phase offset / amplitude (see [GerstnerOcean.precomputeSample]) for
     * surface/deep particles. Computed once here so the per-tick wave loop is just a sin/cos sum
     * over already-baked numbers — no `(TWO_PI/wavelength)·(dir·base)` or `amp·intensity` per
     * particle per tick. Splash particles don't sample the wave field and leave these unused.
     */
    private val basePhase = DoubleArray(GerstnerOcean.waveCount)
    private val amp = DoubleArray(GerstnerOcean.waveCount)

    init {
        pickSprite(sprites)
        hasPhysics = true               // collide with terrain / ship hulls like other particles
        if (mode == Mode.SPLASH) {
            // Ballistic: drifts with the velocity the server sent it, falls under gravity.
            gravity = 1.0f
            friction = 0.99f                       // less drag
            lifetime = 24 + this.random.nextInt(26)
            quadSize *= 0.5f + this.random.nextFloat() * 0.4f
            xd = vx; yd = vy; zd = vz
            alpha = 1.0f
            this.xo = x; this.yo = y; this.zo = z
        } else {
            gravity = 0.0f
            friction = 1.0f
            lifetime = 70 + this.random.nextInt(50)
            quadSize *= 0.6f + this.random.nextFloat() * 0.4f
            alpha = 0.0f                 // fade in (see tick)
            // Spawn flat on the rest plane; displacement (and alpha) ease in over FADE_IN.
            this.x = baseX; this.y = baseY; this.z = baseZ
            this.xo = this.x; this.yo = this.y; this.zo = this.z
            // Intensity is locked at spawn: it's derived from the (near-static) terrain floor
            // under this column and the (server-driven) waterline, both stable over the
            // particle's <6 s lifetime. Locking it eliminates `terrainYForColumn` + intensity
            // recomputation per tick, AND lets [GerstnerOcean.precomputeSample] fold the
            // amplitude term once.
            val intensity = if (mode == Mode.DEEP) 1.0
                else GerstnerOcean.intensityFor(baseY, terrainYForColumn(level, baseX, baseZ, baseY))
            GerstnerOcean.precomputeSample(baseX, baseZ, intensity, basePhase, amp)
        }
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    /** Emissive: render at full brightness regardless of world lighting. */
    override fun getLightColor(partialTick: Float): Int = 0xF000F0

    override fun tick() {
        xo = x; yo = y; zo = z
        if (age++ >= lifetime) {
            remove()
            return
        }
        if (mode == Mode.SPLASH) tickSplash() else tickWave()
    }

    private fun tickSplash() {
        yd -= 0.04 * gravity
        move(xd, yd, zd)
        xd *= friction.toDouble(); yd *= friction.toDouble(); zd *= friction.toDouble()
        if (onGround) {
            if (yd < 0.0) yd = -yd * SPLASH_BOUNCE   // a little bounce
            xd *= 0.85; zd *= 0.85
        }
        setColor(SPLASH_R, SPLASH_G, SPLASH_B)
        val fadeOut = (lifetime - age).toFloat() / (lifetime * 0.4f).coerceAtLeast(1f)
        alpha = min(1.0f, fadeOut)
    }

    private fun tickWave() {
        // Ease the displacement in over the fade so there's no pop on spawn. Subsurface motes
        // only sway gently (and around their own 3D point) instead of riding the full surface.
        // Wave field is now sampled from the per-particle precomputed (basePhase, amp) pair —
        // the position-dependent half of each wave's phase and the intensity-folded amplitudes
        // were baked once at construction, so the per-tick path is just one sin/cos per wave.
        val ease = (age.toDouble() / FADE_IN_TICKS).coerceAtMost(1.0)
        val scale = if (mode == Mode.DEEP) DEEP_WAVE_SCALE else 1.0
        GerstnerOcean.displaceFromSample(level.gameTime.toDouble(), basePhase, amp, disp)
        val tx = baseX + disp[0] * ease * scale
        val ty = baseY + disp[1] * ease * scale
        val tz = baseZ + disp[2] * ease * scale
        move(tx - x, ty - y, tz - z) // collision-aware

        // If collision (ship hull / terrain) stopped it well short of its wave position, the ship
        // has run over its rest point — let it die instead of being pinned against the moving
        // hull (sprite clipping into the columns).
        val rx = tx - x; val ry = ty - y; val rz = tz - z
        if (rx * rx + ry * ry + rz * rz > BLOCKED_SQ) {
            remove()
            return
        }

        if (mode == Mode.DEEP) {
            // The body of the virtual ocean: deep ender-green (#032620).
            setColor(DEEP_R, DEEP_G, DEEP_B)
        } else {
            // Surface: original ender-purple; only the upper crests turn green (foam).
            val n = (disp[1] / GerstnerOcean.maxAmplitude).coerceIn(-1.0, 1.0)
            val foam = ((n - 0.4) / 0.6).coerceIn(0.0, 1.0)
            setColor(
                lerp(ENDER_R, FOAM_R, foam),
                lerp(ENDER_G, FOAM_G, foam),
                lerp(ENDER_B, FOAM_B, foam),
            )
            if (!foamBoosted && foam > 0.5) {
                lifetime += FOAM_BONUS
                foamBoosted = true
            }
        }

        val fadeIn = (age.toDouble() / FADE_IN_TICKS).coerceAtMost(1.0).toFloat()
        val tailStart = lifetime * 4 / 5
        val fadeOut = if (age > tailStart) {
            (lifetime - age).toFloat() / (lifetime - tailStart).coerceAtLeast(1).toFloat()
        } else 1.0f
        alpha = min(fadeIn, fadeOut)
    }

    private fun lerp(a: Float, b: Float, f: Double): Float = a + (b - a) * f.toFloat()

    class Provider(
        private val sprites: SpriteSet,
        private val mode: Mode,
    ) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xSpeed: Double, ySpeed: Double, zSpeed: Double,
        ): Particle = OceanParticle(level, x, y, z, sprites, mode, xSpeed, ySpeed, zSpeed)
    }

    companion object {
        private const val FADE_IN_TICKS = 8.0
        private const val FOAM_BONUS = 16

        /** Subsurface motes only sway a fraction of the surface wave amplitude. */
        private const val DEEP_WAVE_SCALE = 0.3

        // Original ender-particle purple; foam crest green.
        private const val ENDER_R = 0.52f; private const val ENDER_G = 0.20f; private const val ENDER_B = 0.66f
        private const val FOAM_R = 0.30f; private const val FOAM_G = 1.00f; private const val FOAM_B = 0.55f

        // Deep ender-green for the subsurface volume: #032620.
        private const val DEEP_R = 0.0118f; private const val DEEP_G = 0.1490f; private const val DEEP_B = 0.1255f

        // Bright spray foam.
        private const val SPLASH_R = 0.62f; private const val SPLASH_G = 1.00f; private const val SPLASH_B = 0.78f

        /** Splash keeps this fraction of downward speed when it hits a surface. */
        private const val SPLASH_BOUNCE = 0.4

        /** If a wave particle ends a tick this far (²) from its target, it's been overrun → cull. */
        private const val BLOCKED_SQ = 0.55 * 0.55

        /** How often (ticks) the shared floor cache is dropped so it follows terrain edits and
         *  dimension changes. Floor geometry is near-static, so a coarse interval is plenty. */
        private const val FLOOR_CACHE_TTL = 100L

        /**
         * The ocean floor Y under a column, sampled at most once per chunk per [FLOOR_CACHE_TTL].
         * Particles tick on a single (client) thread, so a plain map needs no synchronisation. Wave
         * intensity is a smooth, clamped metric, so chunk-granular depth reads identically to the
         * per-block value to the eye — while turning a per-particle [OceanDepth.terrainY] scan
         * (potentially thousands per tick in a ceiling dim) into one scan per chunk per window.
         */
        private val floorCache = Long2DoubleOpenHashMap()
        private var floorCacheStamp = Long.MIN_VALUE

        private fun terrainYForColumn(level: Level, x: Double, z: Double, planeY: Double): Double {
            val window = level.gameTime / FLOOR_CACHE_TTL
            if (window != floorCacheStamp) {
                floorCache.clear()
                floorCacheStamp = window
            }
            val bx = Math.floor(x).toInt()
            val bz = Math.floor(z).toInt()
            val key = ChunkPos.asLong(bx shr 4, bz shr 4)
            if (floorCache.containsKey(key)) return floorCache.get(key)
            val t = OceanDepth.terrainY(level, bx, bz, planeY)
            floorCache.put(key, t)
            return t
        }
    }
}
