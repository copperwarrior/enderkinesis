package org.shipwrights.enderkinesis.client

import net.minecraft.util.Mth

/**
 * Client-side "how Wohlon is my sky right now" state.
 *
 * Delegates the blend factor to [WohlonBiomeFog.currentBlend] — the shared
 * spatial biome-blend (sampled from the player's configured `biomeBlendRadius`
 * worth of cells around the camera, then smoothed by an exponential temporal
 * low-pass) the fog overrides use. Sharing the value keeps the two effects
 * perfectly synchronised: the fog and the sky cross-fade at the same rate,
 * against the same boundary shape, so the player's transition into the biome
 * reads as one coordinated atmospheric shift rather than two unrelated effects
 * with their own timings.
 *
 * Used by:
 *  - [org.shipwrights.enderkinesis.mixin.LevelRendererWohlonBiomeSkyMixin] for
 *    the sunset-gradient fade and the v-shape sun / moon / star alpha
 *    cross-fades.
 *  - [org.shipwrights.enderkinesis.mixin.LevelTimeAccessGetTimeOfDayWohlonBiomeMixin]
 *    to hard-snap the rendered time-of-day at the cross-fade midpoint
 *    (celestial bodies are invisible at the snap moment, hiding the rotation
 *    step).
 *  - [org.shipwrights.enderkinesis.mixin.LevelRendererCloudsWohlonBiomeMixin]
 *    to cancel cloud rendering above the threshold.
 *  - [org.shipwrights.enderkinesis.mixin.LevelRendererWohlonSunMoonMixin]
 *    to swap sun/moon texture, moon phase, and moon UV at the threshold.
 *
 * Inside the Wohlon dimension itself the spatial sample reads ~1.0 (every cell
 * in the dim is the Wohlon biome), which is the right answer: Wohlon dim
 * already has its own `fixed_time` matching [WOHLON_TIME_OF_DAY], so the
 * time-of-day mixin's snap is a no-op there.
 */
object WohlonBiomeSkyState {

    /** Threshold for [shouldRenderWohlonSky] *and* the moment at
     *  which the rendered time-of-day hard-snaps between the
     *  actual world time and Wohlon's fixed dusk pose. The sun,
     *  moon, and star alpha cross-fades are designed to reach zero
     *  exactly at this point (vanilla fades 1 → 0 across blend
     *  0 → 0.5, Wohlon fades 0 → 1 across 0.5 → 1.0), so the
     *  rendered sky elements are invisible *at the snap moment* —
     *  the time change is hidden behind a fully-faded sun and a
     *  fully-faded star field, and the eye never sees the rotation
     *  step. */
    private const val WOHLON_SKY_THRESHOLD: Float = 0.5f

    /** Wohlon's dimension-type `fixed_time` (see
     *  `data/enderkinesis/dimension_type/wohlonnogondonia.json`).
     *  Raw game-time the sun renders at when fully in Wohlon; we
     *  compute the rendered time-of-day from it via vanilla's
     *  `DimensionType.timeOfDay` formula. */
    private const val WOHLON_FIXED_TIME: Long = 12815L

    /** Pre-computed rendered time-of-day for [WOHLON_FIXED_TIME]
     *  via vanilla's `DimensionType.timeOfDay(long)` curve. */
    private val WOHLON_TIME_OF_DAY: Float = computeRenderedTimeOfDay(WOHLON_FIXED_TIME)

    /** Public read of the shared spatial blend. */
    @JvmStatic
    val currentBlend: Float get() = WohlonBiomeFog.currentBlend

    /** True if the Wohlon-themed sky overlays (sun + moon texture
     *  swap, sunset-colour suppression, cloud cull) should
     *  activate this frame. Toggles at the v-shape midpoint where
     *  the alpha fades reach 0, so the discrete swap is invisible. */
    @JvmStatic
    fun shouldRenderWohlonSky(): Boolean =
        WohlonBiomeFog.currentBlend >= WOHLON_SKY_THRESHOLD

    /** Returns the time-of-day to actually render. **Hard-snaps**
     *  between [actual] and [WOHLON_TIME_OF_DAY] at
     *  [WOHLON_SKY_THRESHOLD]. The snap is hidden by the matching
     *  sun / moon / star alpha cross-fades — those reach 0 at the
     *  threshold, so the celestial bodies are invisible at the
     *  exact moment time changes and the rotation step is hidden.
     *
     *  The *sky disc* doesn't have an alpha fade, so its brightness
     *  curve (driven by `getSkyDarken(getTimeOfDay)`) would visibly
     *  step at the threshold. To avoid that *without* lerping the
     *  time-of-day used by the sun/moon matrix (which would
     *  re-introduce the swirl), we keep this method snap-based and
     *  let a separate `@Redirect` inside `Level.getSkyDarken`
     *  smoothly lerp the time-of-day used by *only* that one
     *  brightness calculation — see [smoothTimeOfDayForSkyDarken]
     *  and [org.shipwrights.enderkinesis.mixin.LevelSkyDarkenSmoothMixin]. */
    @JvmStatic
    fun blendTimeOfDay(actual: Float): Float =
        if (WohlonBiomeFog.currentBlend < WOHLON_SKY_THRESHOLD) actual else WOHLON_TIME_OF_DAY

    /** The Wohlon rendered time-of-day constant, for callers (the
     *  sky-darken mixin) that need to compute Wohlon-side
     *  brightness directly. */
    @JvmStatic
    fun getWohlonTimeOfDay(): Float = WOHLON_TIME_OF_DAY

    /** Recomputes the *actual* dimension-time-of-day from raw game
     *  time, bypassing the [LevelTimeAccessGetTimeOfDayWohlonBiomeMixin]
     *  snap. Needed inside the sky-darken redirect because we need
     *  the un-snapped value as the lerp's starting point — calling
     *  `level.getTimeOfDay(partial)` directly would just give us
     *  the snap back. */
    @JvmStatic
    fun unsnappedTimeOfDayFromDayTime(dayTime: Long): Float =
        computeRenderedTimeOfDay(dayTime)

    /** Mojang's `DimensionType.timeOfDay(long)` — copied here so
     *  we don't need a registry-access path on the client. */
    private fun computeRenderedTimeOfDay(gameTime: Long): Float {
        val d = Mth.frac(gameTime.toDouble() / 24000.0 - 0.25)
        val d1 = 0.5 - Math.cos(d * Math.PI) / 2.0
        return ((d * 2.0 + d1) / 3.0).toFloat()
    }

    /** Lerp on the cyclic `[0, 1)` time-of-day domain. Picks the
     *  shorter of the two paths around the unit circle so e.g.
     *  blending from 0.95 to 0.05 sweeps forward through 1.0
     *  rather than backward through noon. */
    private fun lerpCyclic(a: Float, b: Float, t: Float): Float {
        var d = b - a
        if (d > 0.5f) d -= 1f
        else if (d < -0.5f) d += 1f
        var r = a + d * t
        if (r < 0f) r += 1f
        else if (r >= 1f) r -= 1f
        return r
    }
}
