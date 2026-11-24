package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.logging.LogUtils
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import kotlin.math.PI

object QuiescentCradleGlyphs {

    private val LOG = LogUtils.getLogger()

    val TEXTURE = ResourceLocation("minecraft", "textures/font/ascii_sga.png")

    /** One letter. [width], [height] and [centreY] (up from the cell centre) are in font pixels; UVs are 0..1. */
    class Glyph(
        val u0: Float, val v0: Float, val u1: Float, val v1: Float,
        val width: Float, val height: Float, val centreY: Float,
    )

    /** Letters along one circle: [letters] indexes the glyphs, [angles] is radians, [loose] holds 6 randoms per letter. */
    class CircleLayout(
        val radius: Double,
        val glyphs: Array<Glyph>,
        val count: Int,
        val letters: IntArray,
        val angles: FloatArray,
        val loose: FloatArray,
    )

    private const val CELLS = 16
    private const val FONT_PIXELS = 8f
    private const val LETTERS = 26
    // 'a' to 'o' fill row 6 from column 1, 'p' to 'z' row 7 from column 0, as in vanilla font/alt.json.
    private const val FIRST_CELL = 6 * CELLS + 1
    private const val ALPHA_CUT = 40

    /** One font pixel in blocks, on every circle. */
    const val FONT_PX = 0.042

    private const val LETTER_GAP = 1.0
    private const val WORD_GAP = 3.0
    private const val MIN_WORD = 3
    private const val WORD_SPREAD = 5
    private const val LOOSE_VALUES = 6

    /** A hard cap per circle; a huge ship gets a part circle of letters rather than an unbounded loop. */
    private const val MAX_LETTERS = 4096

    // Client thread only. Null until first use and after a resource reload.
    private var glyphs: Array<Glyph>? = null

    fun init() {
        // A resource reload can swap the font texture.
        ClientReloadShadersEvent.EVENT.register { _, _ -> glyphs = null }
    }

    fun glyphs(): Array<Glyph> = glyphs ?: load().also { glyphs = it }

    private fun load(): Array<Glyph> {
        val resource = Minecraft.getInstance().resourceManager.getResource(TEXTURE).orElse(null)
        if (resource == null) {
            LOG.warn("Missing {}; cradle circles use whole cells", TEXTURE)
            return Array(LETTERS) { wholeCell(it) }
        }
        return resource.open().use { NativeImage.read(it) }.use { image -> Array(LETTERS) { crop(image, it) } }
    }

    private fun cellOf(letter: Int): Pair<Int, Int> {
        val cell = FIRST_CELL + letter
        return (cell % CELLS) to (cell / CELLS)
    }

    private fun wholeCell(letter: Int): Glyph {
        val (cx, cy) = cellOf(letter)
        return Glyph(
            cx.toFloat() / CELLS, cy.toFloat() / CELLS, (cx + 1f) / CELLS, (cy + 1f) / CELLS,
            FONT_PIXELS, FONT_PIXELS, 0f,
        )
    }

    private fun crop(image: NativeImage, letter: Int): Glyph {
        val size = image.width / CELLS
        val (cx, cy) = cellOf(letter)
        var x0 = size
        var y0 = size
        var x1 = -1
        var y1 = -1
        for (y in 0 until size) {
            for (x in 0 until size) {
                val alpha = image.getPixelRGBA(cx * size + x, cy * size + y) ushr 24
                if (alpha <= ALPHA_CUT) continue
                x0 = minOf(x0, x)
                y0 = minOf(y0, y)
                x1 = maxOf(x1, x)
                y1 = maxOf(y1, y)
            }
        }
        if (x1 < 0) return wholeCell(letter)
        val toFont = FONT_PIXELS / size
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        return Glyph(
            (cx * size + x0).toFloat() / image.width, (cy * size + y0).toFloat() / image.height,
            (cx * size + x0 + w).toFloat() / image.width, (cy * size + y0 + h).toFloat() / image.height,
            w * toFont, h * toFont, (size / 2f - (y0 + h / 2f)) * toFont,
        )
    }

    /** One line of words round a circle of [radius] blocks, centred, at the letters' true widths, [fontPx] blocks a font pixel. */
    fun layout(radius: Double, seed: Int, set: Array<Glyph>, fontPx: Double = FONT_PX): CircleLayout {
        require(radius > 0.0) { "Circle radius must be positive, was $radius" }
        require(fontPx > 0.0) { "Font pixel must be positive, was $fontPx" }
        val circumference = 2.0 * PI * radius
        val letters = IntArray(MAX_LETTERS)
        val centres = DoubleArray(MAX_LETTERS)
        val rnd = Lcg(seed * 131L + 3L)
        var count = 0
        var x = 0.0
        var left = MIN_WORD + (rnd.next() * WORD_SPREAD).toInt()
        while (count < MAX_LETTERS) {
            val letter = (rnd.next() * set.size).toInt()
            val w = set[letter].width * fontPx
            if (x + w > circumference) break
            letters[count] = letter
            centres[count] = x + w / 2.0
            count++
            x += w + LETTER_GAP * fontPx
            if (--left == 0) {
                x += WORD_GAP * fontPx
                left = MIN_WORD + (rnd.next() * WORD_SPREAD).toInt()
            }
        }
        val shift = (circumference - (x - LETTER_GAP * fontPx)) / 2.0
        val angles = FloatArray(count) { ((shift + centres[it]) / radius).toFloat() }
        val looseRnd = Lcg(seed * 977L)
        val loose = FloatArray(count * LOOSE_VALUES) { (looseRnd.next() * 2.0 - 1.0).toFloat() }
        return CircleLayout(radius, set, count, letters.copyOf(count), angles, loose)
    }

    /** The demo's generator, so the same seeds give the same words. */
    private class Lcg(seed: Long) {
        private var state = seed and MASK

        fun next(): Double {
            state = (state * MULTIPLIER + INCREMENT) and MASK
            return state.toDouble() / RANGE
        }

        private companion object {
            const val MASK = 0xFFFFFFFFL
            const val MULTIPLIER = 1664525L
            const val INCREMENT = 1013904223L
            const val RANGE = 4294967296.0
        }
    }
}
