package org.shipwrights.enderkinesis.client

import net.minecraft.client.Camera
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Slow colour drift of the Wohlon sky and fog, and the canopy cover over the camera that pulls the
 * distance fog in. Both scale with [WohlonBiomeFog.currentBlend]. Render thread only.
 */
object WohlonAtmosphere {

    // Two incommensurate periods, so the drift never reads as a loop.
    private const val BRIGHT_PERIOD_A_SEC = 97.0
    private const val BRIGHT_PERIOD_B_SEC = 41.0
    private const val BRIGHT_AMP_A = 0.06
    private const val BRIGHT_AMP_B = 0.03
    private const val HUE_PERIOD_SEC = 133.0
    private const val HUE_MAX = 0.06f

    // Firefly green, the hue the drift leans toward.
    private const val HUE_R = 0.557f
    private const val HUE_G = 0.831f
    private const val HUE_B = 0.784f

    /** Fog end at full blend, open sky and full canopy cover; fractions of the vanilla value. */
    private const val FOG_END_OPEN = 0.85f
    private const val FOG_END_COVERED = 0.6f

    // A column counts as covered when its top block is this far above the camera.
    private const val COVER_CLEARANCE = 2
    private const val COVER_RADIUS = 2
    private const val COVER_STRIDE = 3
    private const val COVER_RESAMPLE_NANOS = 250_000_000L
    private const val COVER_LERP_RATE_PER_SEC = 2.0

    var tintR = 1f
        private set
    var tintG = 1f
        private set
    var tintB = 1f
        private set

    /** Fraction of the vanilla terrain fog end to use this frame. */
    var fogEndScale = 1f
        private set

    // Dark: shaded water under the canopy is far darker than the dusk fog colour.
    private val MURK_TINT = floatArrayOf(0.22f, 0.30f, 0.27f)

    // The last fog colour above water, so the underwater fog and the water seen from above match.
    // Starts as the Wohlon sky colour, for a player who spawns in water.
    private val airFog = floatArrayOf(0.345f, 0.494f, 0.549f)

    @JvmStatic
    fun recordAirFog(r: Float, g: Float, b: Float) {
        airFog[0] = r; airFog[1] = g; airFog[2] = b
    }

    /** Colour of murky Wohlon water, from above and from below. */
    @JvmStatic
    fun murkRed(): Float = airFog[0] * MURK_TINT[0]

    @JvmStatic
    fun murkGreen(): Float = airFog[1] * MURK_TINT[1]

    @JvmStatic
    fun murkBlue(): Float = airFog[2] * MURK_TINT[2]

    /** Terrain fog range of this frame, for passes that draw after the sky and cloud fog setups. */
    @JvmStatic
    var terrainFogStart = 0f
    @JvmStatic
    var terrainFogEnd = 1f

    /** True while vanilla setupColor runs, so its sky read is not tinted a second time. */
    @JvmStatic
    var skyTintSuppressed = false

    /** Canopy cover over the camera, 0 to 1. */
    var cover = 0f
        private set
    private var coverTarget = 0f
    private var coverSampleNanos = 0L
    private var lastNanos = 0L
    private val probe = BlockPos.MutableBlockPos()

    /** Call one time per frame, after [WohlonBiomeFog.sampleBlend]. */
    @JvmStatic
    fun update(level: Level, camera: Camera) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else (now - lastNanos) * 1e-9
        lastNanos = now
        val blend = WohlonBiomeFog.currentBlend
        if (blend <= 0f) {
            tintR = 1f; tintG = 1f; tintB = 1f
            fogEndScale = 1f
            cover = 0f
            return
        }
        // Wall-clock, so the drift does not jump with a /time set or stop in the fixed-time dimension.
        val t = now * 1e-9
        val bright = 1.0 +
            BRIGHT_AMP_A * Math.sin(t * TAU / BRIGHT_PERIOD_A_SEC) +
            BRIGHT_AMP_B * Math.sin(t * TAU / BRIGHT_PERIOD_B_SEC + 1.3)
        val hue = HUE_MAX * (0.5f + 0.5f * Math.sin(t * TAU / HUE_PERIOD_SEC).toFloat())
        tintR = 1f + blend * ((bright.toFloat() * (1f + hue * (HUE_R - 1f))) - 1f)
        tintG = 1f + blend * ((bright.toFloat() * (1f + hue * (HUE_G - 1f))) - 1f)
        tintB = 1f + blend * ((bright.toFloat() * (1f + hue * (HUE_B - 1f))) - 1f)

        if (now - coverSampleNanos > COVER_RESAMPLE_NANOS) {
            coverSampleNanos = now
            coverTarget = sampleCover(level, camera)
        }
        cover += (coverTarget - cover) * (1.0 - Math.exp(-COVER_LERP_RATE_PER_SEC * dt)).toFloat()
        val target = FOG_END_OPEN + (FOG_END_COVERED - FOG_END_OPEN) * cover
        fogEndScale = 1f + blend * (target - 1f)
    }

    @JvmStatic
    fun onDisconnect() {
        tintR = 1f; tintG = 1f; tintB = 1f
        fogEndScale = 1f
        cover = 0f
        coverTarget = 0f
        lastNanos = 0L
    }

    // Client chunks carry the MOTION_BLOCKING heightmap, which counts leaves: the canopy.
    private fun sampleCover(level: Level, camera: Camera): Float {
        val cam = camera.blockPosition
        var covered = 0
        var total = 0
        for (dx in -COVER_RADIUS..COVER_RADIUS) {
            for (dz in -COVER_RADIUS..COVER_RADIUS) {
                probe.set(cam.x + dx * COVER_STRIDE, cam.y, cam.z + dz * COVER_STRIDE)
                total++
                val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, probe.x, probe.z)
                if (top > cam.y + COVER_CLEARANCE) covered++
            }
        }
        return covered.toFloat() / total
    }

    private const val EYE_PULSE_PERIOD_SEC = 7.0
    private const val EYE_PULSE_MIN = 0.8f

    /** Slow brightness pulse of the wogor eye in the sky, in [EYE_PULSE_MIN, 1]. */
    @JvmStatic
    fun eyePulse(): Float {
        val wave = 0.5f + 0.5f * Math.sin(System.nanoTime() * 1e-9 * TAU / EYE_PULSE_PERIOD_SEC).toFloat()
        return EYE_PULSE_MIN + (1f - EYE_PULSE_MIN) * wave
    }

    private const val TAU = Math.PI * 2.0
}
