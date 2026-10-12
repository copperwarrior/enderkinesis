package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Style
import org.shipwrights.enderkinesis.item.InkBleed
import org.shipwrights.enderkinesis.item.TomeLore
import kotlin.math.floor

/**
 * Fixed hand-written jitter and brown ink for the sloppy font. Each value is a hash of the pen position, so
 * a page does not change between frames. The limits keep each glyph in its own advance cell and its own line.
 */
object SloppyText {

    private val FONT = TomeLore.SLOPPY_STYLE.font

    // A glyph advance is the ink width + 1, so neighbors in one word at +0.5 and -0.5 touch but do not intersect.
    private const val GLYPH_REACH = 0.5f

    // A space puts 5 px between the ink of two words: 2 * (WORD_REACH_X + GLYPH_REACH) must stay below that.
    private const val WORD_REACH_X = 1.5f
    private const val WORD_REACH_Y = 1.0f

    // Lines are 9 px apart and a glyph cell is 8 px high. Ink on an edge row can move 0.5 px to that side.
    private const val EDGE_ROOM = 0.5f
    private const val CLEAR_ROOM = 1.5f

    private const val POSITION_STEPS = 4.0f

    // The ink goes from INK_COLOR to STAIN_COLOR across blotches that are STAIN_CELL pixels wide.
    private const val INK_COLOR = 0x4A2E1A
    private const val STAIN_COLOR = 0x8A5A2E
    private const val STAIN_CELL = 24.0f
    private const val TINT = 0.9f
    private const val SALT_STAIN = 0x3E57
    private const val CHANNEL_MASK = 0xFF
    private const val CHANNEL_MAX = 255.0f
    private const val SALT_GLYPH_X = 0x51ED
    private const val SALT_GLYPH_Y = 0x2F6B
    private const val SALT_WORD_X = 0x7A31
    private const val SALT_WORD_Y = 0x1C9D

    // Keep these two sets equal to `textures/font/sloppy.png`: the glyphs with ink in the top and bottom rows.
    private val INK_ON_TOP_ROW = asciiSet("!\"#$%&'()*/013456789<>?ABCDEFGHIJKLMNOPRSTUVWXZ[\\]^`dfhijl{}~")
    private val INK_ON_BOTTOM_ROW = asciiSet(",4@PQ_gmpqy")

    private fun asciiSet(chars: String) = BooleanArray(ASCII_SIZE).also { set -> chars.forEach { set[it.code] = true } }
    private const val ASCII_SIZE = 128

    @JvmStatic
    fun isSloppy(style: Style): Boolean = style.font == FONT

    @JvmStatic
    fun wordDx(x: Float, y: Float): Float = snap(unit(x, y, SALT_WORD_X) * WORD_REACH_X)

    @JvmStatic
    fun wordDy(x: Float, y: Float): Float = snap(unit(x, y, SALT_WORD_Y) * WORD_REACH_Y)

    @JvmStatic
    fun glyphDx(x: Float, y: Float, codePoint: Int): Float = snap(unit(x, y, SALT_GLYPH_X xor codePoint) * GLYPH_REACH)

    /** The full vertical offset of a glyph: [wordDy] plus the offset of the glyph, kept in its line. */
    @JvmStatic
    fun glyphDy(x: Float, y: Float, codePoint: Int, wordDy: Float): Float {
        val own = snap(unit(x, y, SALT_GLYPH_Y xor codePoint) * GLYPH_REACH)
        // A glyph from the fallback font can have ink on the two edge rows.
        val ascii = codePoint in 0 until ASCII_SIZE
        val up = if (ascii && !INK_ON_TOP_ROW[codePoint]) CLEAR_ROOM else EDGE_ROOM
        val down = if (ascii && !INK_ON_BOTTOM_ROW[codePoint]) CLEAR_ROOM else EDGE_ROOM
        return (wordDy + own).coerceIn(-up, down)
    }

    /** The packed RGB ink color at a pen position. */
    @JvmStatic
    fun inkColor(x: Float, y: Float): Int {
        val stain = blotch(x / STAIN_CELL, y / STAIN_CELL)
        return mixChannel(RED_SHIFT, stain) or mixChannel(GREEN_SHIFT, stain) or mixChannel(0, stain)
    }

    private fun mixChannel(shift: Int, stain: Float): Int {
        val ink = INK_COLOR shr shift and CHANNEL_MASK
        val stained = STAIN_COLOR shr shift and CHANNEL_MASK
        return (ink + (stained - ink) * stain).toInt() shl shift
    }

    /** The RGB color for a weighted color from [InkBleed]: the ink at the pen position, mixed by the weight. */
    @JvmStatic
    fun bleedColor(x: Float, y: Float, weighted: Int): Int {
        val weight = (weighted ushr InkBleed.WEIGHT_SHIFT) / InkBleed.WEIGHT_MAX.toFloat()
        val ink = inkColor(x, y)
        return bleedChannel(ink, weighted, RED_SHIFT, weight) or
            bleedChannel(ink, weighted, GREEN_SHIFT, weight) or
            bleedChannel(ink, weighted, 0, weight)
    }

    private fun bleedChannel(ink: Int, color: Int, shift: Int, weight: Float): Int {
        val from = (ink shr shift and CHANNEL_MASK) * TINT
        val to = color shr shift and CHANNEL_MASK
        return (from + (to - from) * weight).toInt() shl shift
    }

    /** One channel of [base] after the tint toward [ink]. [dim] is the shadow factor of the text. */
    @JvmStatic
    fun tintChannel(base: Float, ink: Int, shift: Int, dim: Float): Float {
        val target = (ink shr shift and CHANNEL_MASK) / CHANNEL_MAX * dim
        return base + (target - base) * TINT
    }

    /** Smooth value noise in 0..1. */
    private fun blotch(x: Float, y: Float): Float {
        val cellX = floor(x)
        val cellY = floor(y)
        val tx = smooth(x - cellX)
        val ty = smooth(y - cellY)
        val ix = cellX.toInt()
        val iy = cellY.toInt()
        val top = corner(ix, iy) + (corner(ix + 1, iy) - corner(ix, iy)) * tx
        val bottom = corner(ix, iy + 1) + (corner(ix + 1, iy + 1) - corner(ix, iy + 1)) * tx
        return top + (bottom - top) * ty
    }

    private fun smooth(t: Float): Float = t * t * (3.0f - 2.0f * t)

    private fun corner(x: Int, y: Int): Float = (hash(x, y, SALT_STAIN) and UNIT_MASK) / UNIT_MASK.toFloat()

    // Truncation to full screen pixels keeps the glyph sharp and never increases an offset.
    private fun snap(offset: Float): Float {
        val scale = Minecraft.getInstance().window.guiScale.toFloat()
        return (offset * scale).toInt() / scale
    }

    /** A fixed pseudo-random value in -1..1 for a pen position. */
    internal fun unit(x: Float, y: Float, salt: Int): Float {
        val h = hash(floor(x * POSITION_STEPS).toInt(), floor(y * POSITION_STEPS).toInt(), salt)
        return (h and UNIT_MASK) / (UNIT_MASK / 2.0f) - 1.0f
    }

    private fun hash(x: Int, y: Int, salt: Int): Int {
        val h = (x * HASH_X xor y * HASH_Y xor salt * HASH_SALT).let { (it xor (it ushr 15)) * HASH_MIX }
        return h xor (h ushr 13)
    }

    private const val HASH_X = -0x61c88647
    private const val HASH_Y = -0x7a143595
    private const val HASH_SALT = -0x3d4d51cb
    private const val HASH_MIX = 0x2c1b3c6d
    private const val UNIT_MASK = 0xFFFF
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
}
