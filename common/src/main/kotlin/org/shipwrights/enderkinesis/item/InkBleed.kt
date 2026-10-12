package org.shipwrights.enderkinesis.item

import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.util.StringDecomposer

/**
 * Makes the text near a colored run go gradually to that color. A character near a colored run gets that
 * color with a weight in the top byte. Vanilla ignores the top byte, and `SloppyText.bleedColor` uses it
 * to mix the color with the ink at draw time.
 */
object InkBleed {

    const val WEIGHT_SHIFT = 24

    // TextColor reads its JSON form with Integer.parseInt, so the top bit must stay clear.
    const val WEIGHT_MAX = 0x7F
    const val RGB_MASK = 0xFFFFFF

    private const val RADIUS = 10

    fun apply(page: Component): Component {
        val codePoints = IntArrayList()
        val styles = ArrayList<Style>()
        // StringDecomposer changes the `§` codes into styles.
        StringDecomposer.iterateFormatted(page, Style.EMPTY) { _, style, codePoint ->
            codePoints.add(codePoint)
            styles.add(style)
            true
        }
        if (styles.none { it.color != null }) return page

        val weights = IntArray(styles.size)
        val colors = IntArray(styles.size)
        spread(styles, styles.indices, weights, colors)
        spread(styles, styles.indices.reversed(), weights, colors)
        return rebuild(codePoints, styles, weights, colors)
    }

    /** One pass in the direction of [order]. The nearest colored run gives the larger weight. */
    private fun spread(styles: List<Style>, order: IntProgression, weights: IntArray, colors: IntArray) {
        var distance = RADIUS + 1
        var color = 0
        for (i in order) {
            val own = styles[i].color
            if (own != null) {
                distance = 0
                color = own.value and RGB_MASK
                continue
            }
            if (distance >= RADIUS) continue
            distance++
            val weight = WEIGHT_MAX * (RADIUS + 1 - distance) / (RADIUS + 1)
            if (weight > weights[i]) {
                weights[i] = weight
                colors[i] = color
            }
        }
    }

    private fun rebuild(codePoints: IntArrayList, styles: List<Style>, weights: IntArray, colors: IntArray): Component {
        val out = Component.empty()
        val run = StringBuilder()
        var runStyle = Style.EMPTY
        for (i in styles.indices) {
            val style = if (weights[i] == 0) {
                styles[i]
            } else {
                styles[i].withColor(TextColor.fromRgb(weights[i] shl WEIGHT_SHIFT or colors[i]))
            }
            if (style != runStyle && run.isNotEmpty()) {
                out.append(Component.literal(run.toString()).withStyle(runStyle))
                run.setLength(0)
            }
            runStyle = style
            run.appendCodePoint(codePoints.getInt(i))
        }
        if (run.isNotEmpty()) out.append(Component.literal(run.toString()).withStyle(runStyle))
        return out
    }
}
