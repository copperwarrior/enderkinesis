package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.level.block.Block
import org.shipwrights.enderkinesis.block.FractalProjectorBlock
import org.shipwrights.enderkinesis.item.SketchSpec
import org.shipwrights.enderkinesis.item.SketchSubject
import org.shipwrights.enderkinesis.item.SketchTag
import org.shipwrights.enderkinesis.registry.EKBlocks
import java.lang.ref.WeakReference
import java.util.Optional
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

/** A sketch that starts on text line [line] of the page, moved [dx] right of centre and [dy] down, in GUI pixels. */
class SketchPlacement(val spec: SketchSpec, val line: Int, val dx: Int = 0, val dy: Int = 0)

class SketchLayout(val lines: List<FormattedCharSequence>, val sketches: List<SketchPlacement>)

/**
 * Lays out sketch tags in book pages and draws them. A tag starts its own line and takes enough blank lines for its
 * height; a tag split across style runs, one whose block state does not parse, or an empty block tag stays as text.
 */
object InlineSketches {

    private const val MAX_TAG_CELLS = 36

    // A tag grid bakes one sketch for each cell, and every cell on the open page must stay cached.
    private const val CACHE_LIMIT = MAX_TAG_CELLS + 8

    // Render thread only. Baked sketches stay while the player pages back and forth; the oldest close first.
    private val cache = object : LinkedHashMap<SketchSpec, StrikeSketch>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SketchSpec, StrikeSketch>): Boolean {
            if (size <= CACHE_LIMIT) return false
            eldest.value.close()
            return true
        }
    }

    // Render thread only. A random pattern is picked when a new screen first lays out a page, so it holds while the book stays open.
    private var pickedFor = WeakReference<Screen>(null)
    private var picked = 0

    private fun resolve(spec: SketchSpec): SketchSpec {
        if (!spec.randomPattern) return spec
        val screen = Minecraft.getInstance().screen
        if (pickedFor.get() !== screen) {
            pickedFor = WeakReference(screen)
            picked = Random.nextInt(FractalProjectorBlock.FRACTAL_TYPE_COUNT)
        }
        val subject = spec.subject
        val pattern = if (subject is SketchSubject.Block && subject.state.`is`(EKBlocks.FRACTAL_PROJECTOR.get())) {
            SketchSubject.Block(subject.state.setValue(FractalProjectorBlock.FRACTAL_TYPE, picked))
        } else {
            subject
        }
        return spec.copy(subject = pattern, randomPattern = false)
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
                val spec = tag.spec?.let(::resolve) ?: continue
                val subject = spec.subject
                val members = if (subject is SketchSubject.BlockTag) tagBlocks(subject) else null
                if (members?.isEmpty() == true) continue
                block.add(FormattedText.of(text.substring(at, tag.start).removeSuffix("\n"), style))
                flush()
                if (members == null) sketches.add(SketchPlacement(spec, lines.size)) else sketches.addAll(grid(spec, members, lines.size))
                repeat(spec.lines) { lines.add(FormattedCharSequence.EMPTY) }
                at = tag.end
                if (text.startsWith("\n", at)) at++
            }
            block.add(FormattedText.of(text.substring(at), style))
        }
        flush()
        return SketchLayout(lines, sketches)
    }

    private fun tagBlocks(subject: SketchSubject.BlockTag): List<Block> =
        BuiltInRegistries.BLOCK.getTagOrEmpty(subject.tag).take(MAX_TAG_CELLS).map { it.value() }

    /** One small sketch for each block, in a near-square grid centred in the tag's square; rows centre too. */
    private fun grid(spec: SketchSpec, blocks: List<Block>, line: Int): List<SketchPlacement> {
        val columns = ceil(sqrt(blocks.size.toFloat())).toInt()
        val rows = (blocks.size + columns - 1) / columns
        val cell = (spec.size / columns).coerceAtLeast(SketchTag.MIN_SIZE)
        val top = (spec.size - rows * cell) / 2
        return blocks.mapIndexed { i, block ->
            val row = i / columns
            val inRow = minOf(columns, blocks.size - row * columns)
            val cellSpec = SketchSpec(SketchSubject.Block(block.defaultBlockState()), spec.seed + i, cell, spec.notes, spec.colour)
            SketchPlacement(cellSpec, line, (i % columns) * cell + (cell - inRow * cell) / 2, top + row * cell)
        }
    }

    /** [centreX] is the page centre on screen; [textTop] is the screen y of the first text line. */
    fun draw(graphics: GuiGraphics, sketches: List<SketchPlacement>, centreX: Int, textTop: Int) {
        if (sketches.isEmpty()) return
        graphics.flush()
        for (placement in sketches) {
            val spec = placement.spec
            val sketch = cache.getOrPut(spec) { StrikeSketch(spec) }
            sketch.render(
                graphics, centreX - spec.size / 2 + placement.dx,
                textTop + placement.line * BookSkin.LINE_HEIGHT + placement.dy,
            )
        }
    }
}
