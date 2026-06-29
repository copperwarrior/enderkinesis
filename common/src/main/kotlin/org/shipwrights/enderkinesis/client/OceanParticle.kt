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
 *  - [Mode.FOAM_CREST]: a small short-lived foam-green dot that appears at a wave crest,
 *    drifts briefly, then fades. The mesh now carries the wave geometry; these add the
 *    foam highlights at the crests without re-implementing the full surface particle field.
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

    enum class Mode { SURFACE, DEEP, SPLASH, FOAM_CREST }

    private val baseX = x
    private val baseY = y
    private val baseZ = z
    private val disp = DoubleArray(3)
    private var foamBoosted = false

    /** Reconstructed lattice rest-plane Y for SPLASH particles. Computed at spawn from the
     *  particle's spawn position minus the wave's height at the spawn moment, so
     *  `waveSurfaceY(x, z, t) = waterLineY + GerstnerOcean.heightAt(x, z, t, intensity)`
     *  is recoverable per tick without needing to send `waterLineY` over the spawn packet. */
    private var waterLineY: Double = 0.0

    /** Per-particle scratch for wave-velocity sampling during splash ticks. */
    private val tmpWaveVel: org.joml.Vector3d = org.joml.Vector3d()

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
            // Sized up + longer-lived than the original "very light" tuning so the spray reads
            // as foam rather than a faint mist on hull-on-wave hits.
            gravity = 1.0f
            friction = 0.99f                       // less drag
            lifetime = 40 + this.random.nextInt(30)
            quadSize *= 1.2f + this.random.nextFloat() * 0.6f
            xd = vx; yd = vy; zd = vz
            alpha = 1.0f
            this.xo = x; this.yo = y; this.zo = z
            // Reconstruct the lattice's rest-plane Y so the splash can sample the wave
            // surface anywhere, anytime. Splash spawns at the hull-on-wave contact point —
            // i.e., its spawn Y *is* `waterLineY + heightAt(spawnX, spawnZ, spawnTime)`.
            // Subtract the wave height at spawn → recover `waterLineY`.
            val spawnTime = level.gameTime.toDouble()
            waterLineY = y - GerstnerOcean.heightAt(x, z, spawnTime, SPLASH_WAVE_INTENSITY)
        } else if (mode == Mode.FOAM_CREST) {
            // Crest foam highlight: small, foam-green from the moment it appears, slight
            // tangential drift (the velocity the server sent — typically the wave's tangent
            // flow), no gravity, very short lifetime. Sits roughly where it spawned, fading.
            gravity = 0.0f
            friction = 0.95f
            lifetime = 12 + this.random.nextInt(10)
            quadSize *= 0.35f + this.random.nextFloat() * 0.25f
            xd = vx; yd = vy; zd = vz
            alpha = 0.0f
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
        when (mode) {
            Mode.SPLASH -> tickSplash()
            Mode.FOAM_CREST -> tickFoamCrest()
            else -> tickWave()
        }
    }

    /** Foam-crest tick: drift along the velocity it was given (typically the wave's tangent
     *  flow at spawn), apply friction, no gravity, fade in/out with a brief life. Foam green
     *  from the start — the colour is the only thing the mesh can't render at the crest. */
    private fun tickFoamCrest() {
        move(xd, yd, zd)
        xd *= friction.toDouble(); yd *= friction.toDouble(); zd *= friction.toDouble()
        setColor(FOAM_R, FOAM_G, FOAM_B)
        val fadeIn = (age.toDouble() / 3.0).coerceAtMost(1.0).toFloat()
        val tailStart = lifetime - 8
        val fadeOut = if (age > tailStart) {
            (lifetime - age).toFloat() / (lifetime - tailStart).coerceAtLeast(1).toFloat()
        } else 1.0f
        alpha = min(fadeIn, fadeOut) * 0.85f
    }

    private fun tickSplash() {
        // Compute the wave's surface Y at the droplet's *current* XZ. From this we know
        // whether the droplet is in the air or has re-entered the water — fundamental to
        // making the splash behave like real spray rather than just a ballistic dot that
        // falls through the wave.
        val time = level.gameTime.toDouble()
        val waveSurfaceY =
            waterLineY + GerstnerOcean.heightAt(x, z, time, SPLASH_WAVE_INTENSITY)
        val inAir = y > waveSurfaceY

        if (inAir) {
            // Wind/spray coupling: a fraction of the wave's tangential surface velocity
            // pushes the droplet horizontally. The wave's flow carries the foam.
            GerstnerOcean.velocityAt(x, z, time, SPLASH_WAVE_INTENSITY, tmpWaveVel)
            xd += tmpWaveVel.x * SPLASH_WIND_COUPLING
            zd += tmpWaveVel.z * SPLASH_WIND_COUPLING
            yd -= 0.04 * gravity
        } else {
            // Re-entered the water — heavy lateral drag, vertical settle toward the wave
            // surface, and accelerated fade so the droplet visibly merges into the wave
            // rather than persisting underwater. Velocity is killed first so the droplet
            // settles in place; gravity is *suppressed* once submerged so it doesn't keep
            // sinking through the deep water column.
            xd *= SPLASH_WATER_DRAG
            zd *= SPLASH_WATER_DRAG
            yd *= SPLASH_WATER_DRAG_V
            // Pull gently toward the surface (buoyant settle) instead of free-falling.
            val rise = (waveSurfaceY - y).coerceAtLeast(-1.0) * 0.05
            yd += rise
            // Shorten the remaining life so the droplet doesn't linger underwater.
            val deadline = lifetime - SPLASH_DROWN_FADE_TICKS
            if (age < deadline) age = deadline
        }

        move(xd, yd, zd)
        xd *= friction.toDouble(); yd *= friction.toDouble(); zd *= friction.toDouble()
        if (onGround) {
            if (yd < 0.0) yd = -yd * SPLASH_BOUNCE   // hull / terrain bounce
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

        /** Wave intensity the splash assumes when sampling the Gerstner surface for its
         *  per-tick simulation. Splashes are emitted only where waves are active, so the
         *  shallow-water dampening that [GerstnerOcean.intensityFor] applies to the deep-
         *  field doesn't really apply here; 1.0 gives the splash the full-amplitude wave
         *  it just hit. */
        private const val SPLASH_WAVE_INTENSITY = 1.0

        /** Fraction of the wave's tangential surface velocity added to the droplet's
         *  horizontal velocity per tick while airborne. Small enough to read as "wind
         *  carries the foam" rather than the droplet being pinned to the water. */
        private const val SPLASH_WIND_COUPLING = 0.08

        /** Lateral drag once the droplet has re-entered the water (per tick). Aggressive so
         *  the spray settles in place rather than continuing to skim along the surface. */
        private const val SPLASH_WATER_DRAG = 0.55

        /** Vertical drag while submerged. Even more aggressive than lateral so the droplet
         *  doesn't sink through the deep water column. */
        private const val SPLASH_WATER_DRAG_V = 0.40

        /** Once submerged, kill the droplet within this many ticks. Long enough for the
         *  brief alpha-fade to play; short enough that the spray reads as "merged into the
         *  wave" rather than lingering as an underwater ghost. */
        private const val SPLASH_DROWN_FADE_TICKS = 4

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
