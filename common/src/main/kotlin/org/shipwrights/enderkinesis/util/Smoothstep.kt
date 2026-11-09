package org.shipwrights.enderkinesis.util

/** Hermite smoothstep of [t], clamped to 0..1. */
fun smoothstep(t: Double): Double {
    val c = t.coerceIn(0.0, 1.0)
    return c * c * (3.0 - 2.0 * c)
}

/** Hermite smoothstep of [t], clamped to 0..1. */
fun smoothstep(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

fun smoothstep(edge0: Double, edge1: Double, x: Double): Double = smoothstep((x - edge0) / (edge1 - edge0))

fun smoothstep(edge0: Float, edge1: Float, x: Float): Float = smoothstep((x - edge0) / (edge1 - edge0))
