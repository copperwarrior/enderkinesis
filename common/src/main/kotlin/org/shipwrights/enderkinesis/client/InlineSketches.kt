package org.shipwrights.enderkinesis.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import org.shipwrights.enderkinesis.item.SketchSpec
import org.shipwrights.enderkinesis.item.SketchTag
import java.util.Optional

/** A sketch that starts on text line [line] of the page. */
class SketchPlacement(val spec: SketchSpec, val line: Int) {
    val lines: Int get() = spec.lines
}

class SketchLayout(val lines: List<FormattedCharSequence>, val sketches: List<SketchPlacement>)

/**
 * Lays out sketch tags in book pages and draws them. A tag starts its own line and takes enough blank lines for its
 * height; a tag split across style runs, or one whose block state does not parse, stays as text.
 */
object InlineSketches {

    private const val CACHE_LIMIT = 8

    // Render thread only. Baked sketches stay while the player pages back and forth; the oldest close first.
    private val cache = object : LinkedHashMap<SketchSpec, StrikeSketch>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SketchSpec, StrikeSketch>): Boolean {
            if (size <= CACHE_LIMIT) return false
            eldest.value.close()
            return true
        }
    }

    /** Splits [page] into lines with [split], with blank lines where each sketch goes. */
    fun layout(page: FormattedText, split: (FormattedText) -> List<FormattedCharSequence>): SketchLayout {
        val runs = ArrayList<Pair<Style, String>>()
        page.visit({ style, text -> runs.add(style to text); Optional.empty<Unit>() }, Style.EMPTY)
        if (runs.none { SketchTag.mayContain(it.second) }) return SketchLayout(split(page), emptyList())

        val lines = ArrayList<FormattedCharSequence>()
        val sketches = ArrayList<SketchPlacement>()
        val block = ArrayList<FormattedText>()
        fun flush() {
            val text = FormattedText.composite(block)
            if (text.string.isNotEmpty()) lines.addAll(split(text))
            block.clear()
        }
        for ((style, text) in runs) {
            var at = 0
            for (tag in SketchTag.findAll(text)) {
                val spec = tag.spec ?: continue
                block.add(FormattedText.of(text.substring(at, tag.start).removeSuffix("\n"), style))
                flush()
                val placement = SketchPlacement(spec, lines.size)
                sketches.add(placement)
                repeat(placement.lines) { lines.add(FormattedCharSequence.EMPTY) }
                at = tag.end
                if (text.startsWith("\n", at)) at++
            }
            block.add(FormattedText.of(text.substring(at), style))
        }
        flush()
        return SketchLayout(lines, sketches)
    }

    /** [centreX] is the page centre on screen; [textTop] is the screen y of the first text line. */
    fun draw(graphics: GuiGraphics, sketches: List<SketchPlacement>, centreX: Int, textTop: Int) {
        if (sketches.isEmpty()) return
        graphics.flush()
        for (placement in sketches) {
            val spec = placement.spec
            val sketch = cache.getOrPut(spec) { StrikeSketch(spec) }
            sketch.render(graphics, centreX - spec.size / 2, textTop + placement.line * BookSkin.LINE_HEIGHT)
        }
    }
}
