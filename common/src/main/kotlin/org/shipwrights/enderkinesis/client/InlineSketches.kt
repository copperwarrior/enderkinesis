package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.TextureTarget
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

/**
 * A sketch, or a block-tag [grid], that starts on text line [line] of the page. Its left edge is [offsetX] right of the
 * page centre and its top is [offsetY] below the line, in GUI pixels.
 */
class SketchPlacement internal constructor(
    val spec: SketchSpec, val line: Int, internal val offsetX: Int, internal val offsetY: Int, internal val grid: GridLayout? = null,
) {
    internal val key: Any = grid ?: spec
}

/** One grid square, with its top-left corner at ([x], [y]) GUI pixels in the grid's box. */
internal class GridCell(val spec: SketchSpec, val x: Int, val y: Int)

/** The cells of a block-tag grid, in a [width] by [height] box. The hash is kept, because the page draw reads it each frame. */
internal class GridLayout(val spec: SketchSpec, val width: Int, val height: Int, val cells: List<GridCell>) {
    private val cellSpecs = cells.map { it.spec }
    private val hash = 31 * spec.hashCode() + cellSpecs.hashCode()

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean =
        other is GridLayout && hash == other.hash && spec == other.spec && cellSpecs == other.cellSpecs
}

/** A grid baked into one texture, a few cells each frame; cells not yet baked stay empty. */
internal class SketchGrid(private val layout: GridLayout) : BakedSketch {
    private var atlas: TextureTarget? = null
    private var atlasScale = 0
    private var baked = 0

    /** Bakes up to [budget] more cells and returns how many it baked. */
    fun bake(budget: Int): Int {
        val target = target()
        val scale = atlasScale
        var used = 0
        while (used < budget && baked < layout.cells.size && StrikeSketch.shader != null) {
            val cell = layout.cells[baked]
            val size = cell.spec.size
            // The atlas texture starts at the bottom row.
            if (!StrikeSketch(cell.spec).bakeInto(target, scale, cell.x * scale, (layout.height - cell.y - size) * scale)) break
            baked++
            used++
        }
        return used
    }

    override fun render(graphics: GuiGraphics, x: Int, y: Int) {
        StrikeSketch.blit(graphics, x, y, layout.width, layout.height, target().colorTextureId, glRows = true)
    }

    private fun target(): TextureTarget {
        val scale = StrikeSketch.guiPixelScale()
        if (scale != atlasScale) {
            close()
            atlasScale = scale
        }
        return atlas ?: StrikeSketch.blankTarget(layout.width * scale, layout.height * scale, depth = false).also { atlas = it }
    }

    override fun close() {
        atlas?.destroyBuffers()
        atlas = null
        baked = 0
    }
}

class SketchLayout(val lines: List<FormattedCharSequence>, val sketches: List<SketchPlacement>)

/**
 * Lays out sketch tags in book pages and draws them. A tag starts its own line and takes enough blank lines for its
 * height; a tag split across style runs, one whose block state does not parse, or an empty block tag stays as text.
 */
object InlineSketches {

    private const val MAX_TAG_CELLS = 36
    private const val CACHE_LIMIT = 8

    // Each cell bake renders the scene several times and reads it back, so a full grid in one frame stalls it.
    private const val GRID_CELLS_PER_FRAME = 4

    // Render thread only. The sketches on the open page.
    private var pageSketches = 0

    // Render thread only. Baked sketches stay while the player pages back and forth; the oldest close first.
    // The limit never falls below the open page's count, so a page does not evict its own sketches each frame.
    private val cache = object : LinkedHashMap<Any, BakedSketch>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, BakedSketch>): Boolean {
            if (size <= maxOf(CACHE_LIMIT, pageSketches)) return false
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
                sketches.add(if (members == null) SketchPlacement(spec, lines.size, -spec.size / 2, 0) else grid(spec, members, lines.size))
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
    private fun grid(spec: SketchSpec, blocks: List<Block>, line: Int): SketchPlacement {
        val geometry = gridGeometry(blocks.size, spec.size)
        val cell = geometry.cell
        val cells = blocks.mapIndexed { i, block ->
            val cellSpec = SketchSpec(SketchSubject.Block(block.defaultBlockState()), spec.seed + i, cell, spec.notes, spec.colour)
            GridCell(cellSpec, geometry.xs[i] - geometry.left, geometry.ys[i] - geometry.top)
        }
        return SketchPlacement(spec, line, geometry.left, geometry.top, GridLayout(spec, geometry.width, geometry.height, cells))
    }

    /** Square [cell] edges: [xs] left edges from the page centre, [ys] tops from the line, and the box that holds them. */
    internal class GridGeometry(val cell: Int, val xs: IntArray, val ys: IntArray) {
        val left = xs.min()
        val top = ys.min()
        val width = xs.max() + cell - left
        val height = ys.max() + cell - top
    }

    internal fun gridGeometry(count: Int, size: Int): GridGeometry {
        require(count > 0) { "a grid needs a cell" }
        val columns = ceil(sqrt(count.toFloat())).toInt()
        val rows = (count + columns - 1) / columns
        val cell = (size / columns).coerceAtLeast(SketchTag.MIN_SIZE)
        val top = (size - rows * cell) / 2
        val xs = IntArray(count) { i ->
            val inRow = minOf(columns, count - i / columns * columns)
            -cell / 2 + (i % columns) * cell + (cell - inRow * cell) / 2
        }
        return GridGeometry(cell, xs, IntArray(count) { i -> top + i / columns * cell })
    }

    /** [centreX] is the page centre on screen; [textTop] is the screen y of the first text line. */
    fun draw(graphics: GuiGraphics, sketches: List<SketchPlacement>, centreX: Int, textTop: Int) {
        if (sketches.isEmpty()) return
        graphics.flush()
        pageSketches = sketches.size
        var budget = GRID_CELLS_PER_FRAME
        for (placement in sketches) {
            val grid = placement.grid
            val sketch = cache.getOrPut(placement.key) { if (grid == null) StrikeSketch(placement.spec) else SketchGrid(grid) }
            if (sketch is SketchGrid) budget -= sketch.bake(budget)
            sketch.render(graphics, centreX + placement.offsetX, textTop + placement.line * BookSkin.LINE_HEIGHT + placement.offsetY)
        }
    }
}
