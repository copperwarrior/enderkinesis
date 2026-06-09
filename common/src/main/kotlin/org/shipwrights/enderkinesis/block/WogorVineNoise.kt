package org.shipwrights.enderkinesis.block

/** Hash-based value noise: deterministic 3D field gating Wogor vine growth into ~12-block-wide zones. */
object WogorVineNoise {

    private const val FREQ = 1.0 / 12.0

    /** Field is approximately uniform [0, 1]; 0.85 qualifies ~15% of wood blocks. */
    private const val THRESHOLD = 0.85

    fun shouldVine(x: Int, y: Int, z: Int): Boolean =
        rawNoise(x, y, z) > THRESHOLD

    fun rawNoise(x: Int, y: Int, z: Int): Double =
        valueNoise3d(x * FREQ, y * FREQ, z * FREQ)

    private fun valueNoise3d(x: Double, y: Double, z: Double): Double {
        val xi = Math.floor(x).toInt()
        val yi = Math.floor(y).toInt()
        val zi = Math.floor(z).toInt()
        val u = fade(x - xi)
        val v = fade(y - yi)
        val w = fade(z - zi)

        val c000 = hash01(xi, yi, zi)
        val c001 = hash01(xi, yi, zi + 1)
        val c010 = hash01(xi, yi + 1, zi)
        val c011 = hash01(xi, yi + 1, zi + 1)
        val c100 = hash01(xi + 1, yi, zi)
        val c101 = hash01(xi + 1, yi, zi + 1)
        val c110 = hash01(xi + 1, yi + 1, zi)
        val c111 = hash01(xi + 1, yi + 1, zi + 1)

        val x00 = lerp(c000, c100, u)
        val x01 = lerp(c001, c101, u)
        val x10 = lerp(c010, c110, u)
        val x11 = lerp(c011, c111, u)
        val y0 = lerp(x00, x10, v)
        val y1 = lerp(x01, x11, v)
        return lerp(y0, y1, w)
    }

    private fun fade(t: Double): Double = t * t * (3.0 - 2.0 * t)

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    private fun hash01(x: Int, y: Int, z: Int): Double {
        var h = x * 0x9E3779B1.toInt() xor (y * 0x85EBCA77.toInt()) xor (z * 0xC2B2AE3D.toInt())
        h = (h xor (h ushr 15)) * 0x2C1B3C6D.toInt()
        h = (h xor (h ushr 12)) * 0x297A2D39.toInt()
        h = h xor (h ushr 15)
        return (h and 0x7FFFFFFF) / 2147483648.0
    }
}
