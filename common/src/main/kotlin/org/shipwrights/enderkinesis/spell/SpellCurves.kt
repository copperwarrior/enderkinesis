package org.shipwrights.enderkinesis.spell

import kotlin.math.roundToInt

/** Magnitude is a float because imbue splits divide it. Each curve gives the old Ulder Gem value at magnitude 1. */
object SpellCurves {
    const val BASE_RADIUS: Double = 5.5

    /** Also scales the base effect durations of the gems, such as burning. */
    fun curve(magnitude: Float): Float = 0.6f + 0.4f * magnitude

    fun damage(base: Float, magnitude: Float): Float = base * curve(magnitude)

    fun ticks(baseSeconds: Float, perMagnitudeSeconds: Float, magnitude: Float): Int =
        ((baseSeconds + perMagnitudeSeconds * magnitude) * 20f).roundToInt()

    /** Gentler than [curve], because volume grows with the cube of the radius. */
    fun areaRadius(magnitude: Float): Double = BASE_RADIUS * (0.75 + 0.25 * magnitude)

    fun tierAmplifier(magnitude: Float): Int = when {
        magnitude >= 5f -> 2
        magnitude >= 3f -> 1
        else -> 0
    }

    /** Rounded, so split magnitudes lose their fraction. */
    fun toAmplifier(magnitude: Float): Int = (magnitude.roundToInt() - 1).coerceIn(0, 6)

    fun fromAmplifier(amplifier: Int): Float = (amplifier + 1).toFloat()
}
