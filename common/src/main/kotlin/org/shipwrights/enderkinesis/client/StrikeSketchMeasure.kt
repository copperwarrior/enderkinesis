package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage

/**
 * Read once from the colour pass, in texture UV with y up: the tone range for the stretch, where the block meets
 * the ground, and spots well inside the silhouette where a note's leader can end.
 */
internal class StrikeSketchMeasure(
    val toneLow: Float,
    val toneHigh: Float,
    val baseX: Float,
    val baseY: Float,
    val baseHalfWidth: Float,
    /** x, y pairs. */
    val anchors: FloatArray,
) {
    companion object {
        private const val LUMINANCE_LEVELS = 256
        private const val COVERED_ALPHA = 128
        private const val TONE_LOW_PERCENTILE = 0.05f
        private const val TONE_HIGH_PERCENTILE = 0.95f
        private const val CHANNEL_MAX = 255f
        private const val BASE_BAND = 0.25f
        private const val ANCHOR_STEP = 3
        private const val ANCHOR_INSET = 0.03f

        // NativeImage pixels are ABGR. downloadTexture keeps GL row order, so row 0 is the bottom.
        private const val ALPHA_SHIFT = 24
        private const val GREEN_SHIFT = 8
        private const val BLUE_SHIFT = 16
        private const val CHANNEL_MASK = 0xFF
        private const val RED_WEIGHT = 299
        private const val GREEN_WEIGHT = 587
        private const val BLUE_WEIGHT = 114
        private const val WEIGHT_TOTAL = 1000

        fun of(image: NativeImage): StrikeSketchMeasure {
            val size = image.width
            val counts = IntArray(LUMINANCE_LEVELS)
            var covered = 0
            var low = size
            var high = -1
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val pixel = image.getPixelRGBA(x, y)
                    if (pixel ushr ALPHA_SHIFT < COVERED_ALPHA) continue
                    counts[luminance(pixel)]++
                    covered++
                    if (y < low) low = y
                    if (y > high) high = y
                }
            }
            if (covered == 0) return StrikeSketchMeasure(0f, 1f, 0.5f, 0f, 0.5f, FloatArray(0))
            val base = base(image, low, high)
            return StrikeSketchMeasure(
                percentile(counts, covered * TONE_LOW_PERCENTILE) / CHANNEL_MAX,
                percentile(counts, covered * TONE_HIGH_PERCENTILE) / CHANNEL_MAX,
                base[0], base[1], base[2],
                anchors(image),
            )
        }

        /** The middle of the lowest covered row, and half the width of the lowest quarter of the silhouette. */
        private fun base(image: NativeImage, low: Int, high: Int): FloatArray {
            val size = image.width
            val band = low + maxOf(1, ((high - low) * BASE_BAND).toInt())
            var lowSum = 0
            var lowCount = 0
            var minX = size
            var maxX = -1
            for (y in low..minOf(band, size - 1)) {
                for (x in 0 until size) {
                    if (!covered(image, x, y)) continue
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    if (y == low) {
                        lowSum += x
                        lowCount++
                    }
                }
            }
            val centre = if (lowCount > 0) lowSum.toFloat() / lowCount / size else 0.5f
            return floatArrayOf(centre, low.toFloat() / size, (maxX - minX + 1) / 2f / size)
        }

        private fun anchors(image: NativeImage): FloatArray {
            val size = image.width
            val inset = (size * ANCHOR_INSET).toInt()
            val out = ArrayList<Float>()
            for (y in 0 until size step ANCHOR_STEP) {
                for (x in 0 until size step ANCHOR_STEP) {
                    val inside = covered(image, x, y) && covered(image, x - inset, y) && covered(image, x + inset, y) &&
                        covered(image, x, y - inset) && covered(image, x, y + inset)
                    if (!inside) continue
                    out.add(x.toFloat() / size)
                    out.add(y.toFloat() / size)
                }
            }
            return out.toFloatArray()
        }

        /** Covered pixel bounds as min x, min y, max x, max y; null when nothing is covered. */
        /** Pixels at [minAlpha] or more; a lower value takes in fainter parts. */
        fun bounds(image: NativeImage, minAlpha: Int = COVERED_ALPHA): IntArray? {
            var minX = image.width
            var minY = image.height
            var maxX = -1
            var maxY = -1
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    if (image.getPixelRGBA(x, y) ushr ALPHA_SHIFT < minAlpha) continue
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
            }
            return if (maxX < 0) null else intArrayOf(minX, minY, maxX, maxY)
        }

        private fun covered(image: NativeImage, x: Int, y: Int): Boolean =
            x in 0 until image.width && y in 0 until image.height &&
                image.getPixelRGBA(x, y) ushr ALPHA_SHIFT >= COVERED_ALPHA

        private fun luminance(pixel: Int): Int {
            val red = pixel and CHANNEL_MASK
            val green = pixel shr GREEN_SHIFT and CHANNEL_MASK
            val blue = pixel shr BLUE_SHIFT and CHANNEL_MASK
            return (RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue) / WEIGHT_TOTAL
        }

        private fun percentile(counts: IntArray, rank: Float): Int {
            var seen = 0
            for (level in counts.indices) {
                seen += counts[level]
                if (seen >= rank) return level
            }
            return counts.lastIndex
        }
    }
}
