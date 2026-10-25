package org.shipwrights.enderkinesis.spell

import kotlin.math.roundToInt

/**
 * Magnitude scaling. Magnitude is the number of glyphs an element has on a scroll (a float,
 * because imbue splits divide it); a Mystic Gem's burst is magnitude 1. Every curve returns
 * exactly the gem's old number at magnitude 1, and the ceiling to balance against is 7.
 */
object SpellCurves {
    /** Area and rune radius at magnitude 1 (11 blocks across). */
    const val BASE_RADIUS: Double = 5.5

    /** Damage multiplier: 1.0 at magnitude 1, 3.4 at magnitude 7. Also scales the base
     *  effect durations the gems already had (burning). */
    fun curve(magnitude: Float): Float = 0.6f + 0.4f * magnitude

    fun damage(base: Float, magnitude: Float): Float = base * curve(magnitude)

    /** [baseTicks] scaled by [curve], for the durations the gems already had. */
    fun scaledTicks(baseTicks: Int, magnitude: Float): Int = (baseTicks * curve(magnitude)).roundToInt()

    /** The spec's "base + per magnitude" timers, in seconds, as ticks. */
    fun ticks(baseSeconds: Float, perMagnitudeSeconds: Float, magnitude: Float): Int =
        ((baseSeconds + perMagnitudeSeconds * magnitude) * 20f).roundToInt()

    /** Area and rune radius: 5.5 at magnitude 1, rising by a quarter of that per magnitude
     *  to 13.75 at 7. Gentler than [curve], because volume grows with the cube. */
    fun areaRadius(magnitude: Float): Double = BASE_RADIUS * (0.75 + 0.25 * magnitude)

    /** Status-effect amplifier tier from magnitude: I at 1–2, II at 3–4, III at 5 and up. */
    fun tierAmplifier(magnitude: Float): Int = when {
        magnitude >= 5f -> 2
        magnitude >= 3f -> 1
        else -> 0
    }

    /** Magnitude packed into a status-effect amplifier, for effects that need it back when
     *  they end. Rounded, so split magnitudes lose their fraction. */
    fun toAmplifier(magnitude: Float): Int = (magnitude.roundToInt() - 1).coerceIn(0, 6)

    fun fromAmplifier(amplifier: Int): Float = (amplifier + 1).toFloat()
}
