package org.shipwrights.enderkinesis.client

import org.joml.Matrix4f
import kotlin.math.hypot
import kotlin.random.Random

/** One column for each note. [anchors]: anchor x, y and text origin x, y. [styles]: width, lines, side and style, seed. */
internal class NoteLayout(val anchors: Matrix4f, val styles: Matrix4f, val count: Int) {
    companion object {
        val NONE = NoteLayout(Matrix4f(), Matrix4f(), 0)
    }
}

/** How much the block shrinks and moves in the sketch, to leave paper for ground marks and notes. */
internal class SketchFrame(val zoom: Float, val offsetX: Float, val offsetY: Float, val notes: Boolean) {
    companion object {
        val WITH_NOTES = SketchFrame(1.45f, 0.0435f, -0.087f, true)
        val PLAIN = SketchFrame(1.2f, 0.07f, -0.06f, false)
    }
}

/** Places two to four field notes round the sketch from the paper's seed. */
internal object StrikeNotes {

    private const val MIN_NOTES = 2
    private const val NOTE_RANGE = 3
    private const val MIN_LINES = 2
    private const val LINE_RANGE = 3
    private const val ANCHOR_SPACING = 0.2f
    private const val ANCHOR_TRIES = 400
    private const val MIN_WIDTH = 0.75f
    private const val WIDTH_RANGE = 0.25f
    private const val ARROW_CHANCE = 0.5f
    private const val CURVE_CHANCE = 0.5f
    private const val HEADING_CHANCE = 0.4f
    private const val STYLE_ARROW = 1
    private const val STYLE_CURVED = 2
    private const val STYLE_HEADING = 4
    private const val SEED_RANGE = 97f
    private const val COLUMN = 4

    /** Free paper round the block: top-left of the text, in sketch UV with y up, and its width in GUI px. */
    private class Slot(val x: Float, val y: Float, val width: Float)

    private val SLOTS = listOf(
        Slot(0.03f, 0.97f, 34f),
        Slot(0.64f, 0.97f, 34f),
        Slot(0.79f, 0.62f, 23f),
        Slot(0.03f, 0.2f, 36f),
    )

    fun layout(measure: StrikeSketchMeasure, seed: Int, sketchSize: Int, frame: SketchFrame): NoteLayout {
        val random = Random(seed)
        val count = MIN_NOTES + random.nextInt(NOTE_RANGE)
        val slots = SLOTS.shuffled(random).take(count)
        val anchors = pickAnchors(measure.anchors, count, random, frame)
        val order = shortestPairing(anchors, slots.take(anchors.size), sketchSize)

        val a = FloatArray(COLUMN * COLUMN)
        val b = FloatArray(COLUMN * COLUMN)
        anchors.forEachIndexed { i, anchor ->
            val slot = slots[order[i]]
            val width = slot.width * (MIN_WIDTH + random.nextFloat() * WIDTH_RANGE)
            val side = if (slot.x + width / sketchSize / 2f > anchor[0]) 1 else -1
            var style = 0
            if (random.nextFloat() < ARROW_CHANCE) style = style or STYLE_ARROW
            if (random.nextFloat() < CURVE_CHANCE) style = style or STYLE_CURVED
            if (random.nextFloat() < HEADING_CHANCE) style = style or STYLE_HEADING
            val lines = MIN_LINES + random.nextInt(LINE_RANGE)
            floatArrayOf(anchor[0], anchor[1], slot.x, slot.y).copyInto(a, i * COLUMN)
            floatArrayOf(width, lines.toFloat(), (side * (1 + style)).toFloat(), 1f + random.nextFloat() * SEED_RANGE)
                .copyInto(b, i * COLUMN)
        }
        return NoteLayout(Matrix4f().set(a), Matrix4f().set(b), anchors.size)
    }

    /** Spread-out anchors, moved from texture UV into sketch UV. */
    private fun pickAnchors(candidates: FloatArray, count: Int, random: Random, frame: SketchFrame): List<FloatArray> {
        val spots = candidates.size / 2
        val picked = ArrayList<FloatArray>()
        if (spots == 0) return picked
        for (attempt in 0 until ANCHOR_TRIES) {
            if (picked.size == count) break
            val i = random.nextInt(spots)
            val x = candidates[i * 2]
            val y = candidates[i * 2 + 1]
            if (picked.all { hypot(it[2] - x, it[3] - y) > ANCHOR_SPACING }) {
                picked.add(floatArrayOf(toSketch(x, frame.offsetX, frame), toSketch(y, frame.offsetY, frame), x, y))
            }
        }
        return picked
    }

    private fun toSketch(value: Float, offset: Float, frame: SketchFrame): Float = 0.5f + (value - 0.5f - offset) / frame.zoom

    // The pairing with the shortest total leader length has no crossing straight leaders.
    private fun shortestPairing(anchors: List<FloatArray>, slots: List<Slot>, sketchSize: Int): IntArray {
        var best = IntArray(slots.size) { it }
        var bestLength = Float.MAX_VALUE
        for (order in permutations(slots.size)) {
            var length = 0f
            anchors.forEachIndexed { i, anchor ->
                val slot = slots[order[i]]
                length += hypot(slot.x + slot.width / sketchSize / 2f - anchor[0], slot.y - anchor[1])
            }
            if (length < bestLength) {
                bestLength = length
                best = order
            }
        }
        return best
    }

    // At most four notes, so at most 24 orders; built iteratively.
    private fun permutations(n: Int): List<IntArray> {
        var orders = listOf(IntArray(0))
        for (size in 1..n) {
            orders = orders.flatMap { order -> (0..order.size).map { at -> insert(order, at, size - 1) } }
        }
        return orders
    }

    private fun insert(order: IntArray, at: Int, value: Int): IntArray =
        IntArray(order.size + 1) { i -> if (i < at) order[i] else if (i == at) value else order[i - 1] }
}
