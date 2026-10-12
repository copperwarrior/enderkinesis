package org.shipwrights.enderkinesis.client

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.util.Mth
import net.minecraft.util.StringDecomposer
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.sselith.Sselith
import kotlin.math.cos
import kotlin.math.sin

object HolyText {
    // The font is only a marker. `font/holy.json` refers to `minecraft:default`, thus the glyphs do not change.
    private val FONT = EnderkinesisMod.id("holy")

    private const val DARK_YELLOW = 0xB89B00
    private const val SHIMMER_YELLOW = 0xDDC030
    private const val SHIMMER_RAD_PER_MS = 0.004
    private const val SHIMMER_RAD_PER_GLYPH = 0.6
    private const val DRIFT_PIXELS = 0.6
    private const val DRIFT_X_RAD_PER_MS = 0.0031
    private const val DRIFT_Y_RAD_PER_MS = 0.0043
    private const val DRIFT_X_RAD_PER_GLYPH = 1.7
    private const val DRIFT_Y_RAD_PER_GLYPH = 2.3

    @JvmStatic
    fun isHoly(style: Style): Boolean = style.font == FONT

    @JvmStatic
    fun mark(text: FormattedText): FormattedText {
        val plain = StringBuilder()
        val styles = ArrayList<Style>()
        // StringDecomposer changes the `§` codes into styles, thus a split run keeps its format.
        StringDecomposer.iterateFormatted(text, Style.EMPTY) { _, style, codePoint ->
            plain.appendCodePoint(codePoint)
            repeat(Character.charCount(codePoint)) { styles.add(style) }
            true
        }
        val matches = Sselith.INVOCATION_PATTERN.findAll(plain).toList()
        if (matches.isEmpty()) return text
        val holy = BooleanArray(plain.length)
        for (match in matches) holy.fill(true, match.range.first, match.range.last + 1)

        val marked = Component.empty()
        var start = 0
        for (end in 1..plain.length) {
            if (end < plain.length && styles[end] == styles[start] && holy[end] == holy[start]) continue
            val style = if (holy[start]) styles[start].withFont(FONT) else styles[start]
            marked.append(Component.literal(plain.substring(start, end)).withStyle(style))
            start = end
        }
        return marked
    }

    @JvmStatic
    fun shimmerColor(index: Int, millis: Long): Int {
        val blend = 0.5 + 0.5 * sin(millis * SHIMMER_RAD_PER_MS + index * SHIMMER_RAD_PER_GLYPH)
        return lerpChannel(blend, 16) or lerpChannel(blend, 8) or lerpChannel(blend, 0)
    }

    @JvmStatic
    fun driftX(index: Int, millis: Long): Float =
        (DRIFT_PIXELS * sin(millis * DRIFT_X_RAD_PER_MS + index * DRIFT_X_RAD_PER_GLYPH)).toFloat()

    @JvmStatic
    fun driftY(index: Int, millis: Long): Float =
        (DRIFT_PIXELS * cos(millis * DRIFT_Y_RAD_PER_MS + index * DRIFT_Y_RAD_PER_GLYPH)).toFloat()

    private fun lerpChannel(blend: Double, shift: Int): Int {
        val from = DARK_YELLOW shr shift and 0xFF
        val to = SHIMMER_YELLOW shr shift and 0xFF
        return Mth.clamp(Math.round(from + (to - from) * blend).toInt(), 0, 0xFF) shl shift
    }
}
