package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import kotlin.math.abs

/**
 * Client-side "how Wohlon is my sky right now" state.
 *
 * Samples the local biome around the player every client tick to
 * compute a target blend factor in `[0, 1]` (0 = pure non-Wohlon,
 * 1 = surrounded by Wohlon biome cells), then smoothly approaches
 * it with a fixed lerp speed so transitions across biome edges
 * are visible-but-gentle rather than snapping.
 *
 * Used by [org.shipwrights.enderkinesis.mixin.LevelRendererWohlonBiomeSkyMixin]
 * to redirect `ClientLevel.getTimeOfDay` calls inside the sky
 * renderer, smoothly interpolating the sun's rotation toward
 * Wohlon's fixed dusk pose (gameTime ≈ 12815) when the player is
 * inside or near a Wohlon biome patch in any other dimension.
 *
 * Inside the Wohlon dimension itself the blend stays at 0 — vanilla
 * already uses the dimension's `fixed_time` for sun rendering, so
 * overriding here would double-apply and snap the sun back to
 * actual game time.
 */
object WohlonBiomeSkyState {

    /** Maximum per-tick step toward the target blend. Linear
     *  approach (not exponential) so the blend value reaches the
     *  target exactly in a bounded number of ticks, then locks.
     *  Exponential lerp would approach but never reach 1.0,
     *  leaving a tiny residual weight on the actual time-of-day
     *  — the sun would visibly drift over hours of play.
     *
     *  Target: exactly 2 s for a 0 → 1 (or 1 → 0) transition.
     *  At 20 tps that's 40 ticks, so step = 1 / 40 = 0.025. */
    private const val LERP_STEP: Float = 0.025f

    /** Threshold for [shouldRenderWohlonSky]. Above this, the
     *  Wohlon-themed sky overlays (sun + moon texture, sunset-
     *  colour suppression) kick in as a hard swap. Hard swap at
     *  the midpoint reads less jarring than near 0 or 1 because
     *  the time-of-day lerp is halfway through its arc at that
     *  point — the sun is already mid-motion when the texture
     *  pops, so the eye registers the swap less. */
    private const val WOHLON_SKY_THRESHOLD: Float = 0.5f

    /** Wohlon's dimension-type `fixed_time` (see
     *  `data/enderkinesis/dimension_type/wohlonnogondonia.json`).
     *  This is the raw game-time the sun renders for inside
     *  Wohlon; we compute the rendered time-of-day from it via
     *  vanilla's `DimensionType.timeOfDay` formula. */
    private const val WOHLON_FIXED_TIME: Long = 12815L

    /** Pre-computed rendered time-of-day for [WOHLON_FIXED_TIME]
     *  via vanilla's `DimensionType.timeOfDay(long)` curve. Cached
     *  at startup since the input is constant. */
    private val WOHLON_TIME_OF_DAY: Float = computeRenderedTimeOfDay(WOHLON_FIXED_TIME)

    /** Public read by the mixin redirect. Updated on the client
     *  tick thread; the renderer reads on its own thread but
     *  float writes are atomic on the JVM so no sync needed. */
    @Volatile
    @JvmField
    var currentBlend: Float = 0f

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    private fun tick(level: ClientLevel) {
        // Inside the Wohlon dim, vanilla's `fixed_time` already
        // controls sun position — don't double up.
        if (level.dimension() == Wohlonnogondonia.LEVEL_KEY) {
            currentBlend = 0f
            return
        }
        val player = Minecraft.getInstance().player ?: return
        // Binary detection: target is 1 if the player's block is
        // inside a Wohlon biome cell, 0 otherwise. The 2-second
        // linear lerp below smooths the on/off transition; we
        // intentionally do NOT sample neighbouring cells for a
        // fractional blend, because at the boundary that produced
        // values stuck around 0.4–0.6 and never finished the
        // sun-texture swap cleanly.
        val target = if (isInWohlonBiome(level, player.blockPosition())) 1f else 0f
        val delta = target - currentBlend
        currentBlend = when {
            abs(delta) <= LERP_STEP -> target          // snap when within one step
            delta > 0 -> currentBlend + LERP_STEP
            else -> currentBlend - LERP_STEP
        }
    }

    /** True if the Wohlon-themed sky should overlay this frame
     *  (sun + moon texture swap, sunset-colour suppression).
     *  Java-callable via @JvmStatic for the mixin redirects. */
    @JvmStatic
    fun shouldRenderWohlonSky(): Boolean = currentBlend >= WOHLON_SKY_THRESHOLD

    /** Is the player's block inside a Wohlon biome cell? Vanilla
     *  biome storage is per 4×4×4 voxel cell, so this is stable
     *  across small movements within a cell and flips cleanly at
     *  cell boundaries. */
    private fun isInWohlonBiome(level: ClientLevel, pos: BlockPos): Boolean {
        return level.getBiome(pos).`is`(Wohlonnogondonia.BIOME_KEY)
    }

    /** Mixin-callable bridge: returns the time-of-day to actually
     *  render, given the level's natural value. Lerps between
     *  [actual] and [WOHLON_TIME_OF_DAY] on the cyclic [0, 1)
     *  domain.
     *
     *  Currently unused at runtime — the
     *  {@code Level.getDayTime} intercept makes
     *  [blendDayTime] do the work upstream, and
     *  [Level#getTimeOfDay] picks up the blended raw time
     *  automatically. Kept here for future callers that want a
     *  parametric-blend helper instead of a raw-time one. */
    @JvmStatic
    fun blendTimeOfDay(actual: Float): Float {
        val b = currentBlend
        if (b <= 0.001f) return actual
        return lerpCyclic(actual, WOHLON_TIME_OF_DAY, b)
    }

    /** Mixin-callable bridge: returns the day-time to actually
     *  expose to the client, given the level's natural value.
     *  Lerps the **raw** game-time on the cyclic `[0, 24000)`
     *  domain toward Wohlon's [WOHLON_FIXED_TIME] (12815), then
     *  re-attaches the original day count.
     *
     *  Hits at the [Level#getDayTime] call site, so any client
     *  system that reads time-of-day through the normal Level
     *  API — lighting (`getSkyDarken` → `getTimeOfDay`), the
     *  clock item, sky renderer, star brightness, fog interp —
     *  sees the blended value without further per-system mixins.
     *
     *  Day-count preservation note: at blend = 1 the returned
     *  value satisfies `result % 24000 == 12815`, with day
     *  index equal to `actual / 24000`. This can leave the
     *  returned absolute time slightly less than `actual` for
     *  the rest of the day if the player is past 12815 in their
     *  current day, but downstream consumers only care about
     *  `time % 24000`, so the offset is invisible. Monotonicity
     *  per-frame is preserved (actual+1 ⇒ result+1). */
    @JvmStatic
    fun blendDayTime(actual: Long): Long {
        val b = currentBlend
        if (b <= 0f) return actual
        val actualMod = (actual % 24000L).toInt()
        val targetMod = WOHLON_FIXED_TIME.toInt()
        var delta = targetMod - actualMod
        // Shortest cyclic path on the [0, 24000) ring.
        if (delta > 12000) delta -= 24000
        else if (delta < -12000) delta += 24000
        val blendedRaw = actualMod + (delta * b).toInt()
        val blendedMod = ((blendedRaw % 24000) + 24000) % 24000
        val dayCount = actual / 24000L
        return dayCount * 24000L + blendedMod.toLong()
    }

    /** Mojang's `DimensionType.timeOfDay(long)` — copied here so
     *  we don't need a registry-access path on the client. Maps
     *  a raw game time to the rendered "sun arc" position in
     *  `[0, 1)` that `LevelRenderer.renderSky` rotates with. */
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
