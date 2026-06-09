package org.shipwrights.enderkinesis.client

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia

/**
 * Spatial biome-blend scalar for the Wohlon fog overlay — mirrors vanilla's `BiomeColors`
 * pipeline (count-Wohlon / count-total across the biome-blend radius), NOT a time-lerped
 * in/out state. Fog colour is NOT hardcoded here: the biome JSON pins `fog_color` to
 * `sky_color` so vanilla's setupColor + brightness-modulation drives them in lockstep.
 */
object WohlonBiomeFog {

    private const val PEAK_Y: Double = 64.0
    private const val FALLOFF_Y: Double = 96.0
    private const val FOG_MIN_DENSITY: Float = 0.30f

    /** Exponential low-pass rate. The spatial sample steps by ~20% when a full row of cells
     *  shifts; a linear lerp would visibly stair-step. Exponential α=1−exp(−RATE·dt) makes
     *  the approach asymptotic so steps overlap into a continuous curve.
     *  RATE=8 → half-life ≈ 87 ms, time-to-95% ≈ 374 ms. */
    private const val BLEND_LERP_RATE_PER_SEC: Float = 8f

    /** Read each frame by the mixin's `@WrapOperation` and `setupFog` TAIL — they can't see the camera. */
    @JvmStatic
    @Volatile var currentBlend: Float = 0f
        private set

    /** Latest *spatial* sample target — the unsmoothed
     *  Wohlon-fraction from the most recent [sampleBlend] call.
     *  [currentBlend] eases toward this each frame via the
     *  exponential low-pass below. */
    @Volatile private var targetBlend: Float = 0f

    /** Wall-clock timestamp of the last [sampleBlend] call, for
     *  the real-time delta the temporal lerp needs. `0` is the
     *  first-call sentinel — no lerp until we have a real delta
     *  to work with. */
    @Volatile private var lastUpdateNanos: Long = 0L

    /**
     * Compute the Wohlon-biome blend factor by sampling the
     * biome at every integer block position in a horizontal
     * square around the camera, using the player's configured
     * biome-blend radius. The raw fraction `wohlonHits / total`
     * becomes the *target* — [currentBlend] then eases toward
     * that target via a real-time low-pass at
     * [BLEND_LERP_RATE_PER_SEC] so the discrete step quantization
     * of the spatial sample reads as a smooth fade rather than
     * footstep-synchronised jitter.
     *
     * Sampling is horizontal-only at the camera's Y, matching
     * vanilla biome blending (foliage / water / grass colour all
     * blend horizontally only). Cost: `(2r+1)²` paletted-container
     * reads per frame — 25 at the default `r=2`, 225 at the max
     * `r=7`. Each is a few dozen ns. Negligible.
     */
    @JvmStatic
    fun sampleBlend(level: Level, camera: Camera): Float {
        val center = camera.blockPosition
        val cx = center.x
        val cy = center.y
        val cz = center.z
        val radius = Minecraft.getInstance().options.biomeBlendRadius().get()
        val span = radius * 2 + 1
        val total = span * span
        var wohlonHits = 0
        val mut = BlockPos.MutableBlockPos()
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                mut.set(cx + dx, cy, cz + dz)
                if (level.getBiome(mut).`is`(Wohlonnogondonia.BIOME_KEY)) {
                    wohlonHits++
                }
            }
        }
        targetBlend = wohlonHits.toFloat() / total.toFloat()

        // Temporal low-pass: ease currentBlend toward the just-
        // computed targetBlend by a per-second rate scaled by the
        // real wall-clock delta. FPS-independent — at 30 fps each
        // frame takes a bigger step; at 240 fps each is smaller;
        // either way the perceived ease-to-target duration is the
        // same. First call has no delta yet, so we snap.
        val now = System.nanoTime()
        val dtSeconds: Float = if (lastUpdateNanos == 0L) {
            0f
        } else {
            ((now - lastUpdateNanos).toDouble() * 1e-9).toFloat()
        }
        lastUpdateNanos = now

        // Exponential low-pass: close fraction α of the remaining
        // distance to target this frame, where α = 1 - exp(-rate·dt).
        // Asymptotic, so successive spatial-sample steps bleed into
        // each other instead of producing a piecewise-linear staircase.
        val alpha = (1.0 - Math.exp(-BLEND_LERP_RATE_PER_SEC * dtSeconds.toDouble())).toFloat()
        currentBlend += (targetBlend - currentBlend) * alpha

        return currentBlend
    }

    /** Vertical density curve. `1.0` at [PEAK_Y], linearly falling
     *  to [FOG_MIN_DENSITY] at `|y - PEAK_Y| ≥ [FALLOFF_Y]`. The
     *  density combines with the boundary [currentBlend] via
     *  multiplication. */
    @JvmStatic
    fun fogDensityAt(y: Double): Float {
        val dist = if (y > PEAK_Y) y - PEAK_Y else PEAK_Y - y
        val t = (dist / FALLOFF_Y).toFloat()
        val clamped = if (t > 1f) 1f else t
        return 1f - clamped * (1f - FOG_MIN_DENSITY)
    }
}
