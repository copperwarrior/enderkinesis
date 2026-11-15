package org.shipwrights.enderkinesis.client

import org.joml.Matrix4f
import org.joml.Vector3f

/** A view centre and half-width, in pose units. */
internal class SketchView(val centreX: Float, val centreY: Float, val extent: Float)

/**
 * The fit of a sketch as steps, so a large scene can render each try over several frames. Every subject fills the
 * frame: widen until nothing touches the edge, then fit to the drawn outline. A fit that zooms in far is measured
 * again, because a few pixels of outline give a poor fit.
 */
internal class SketchFit(first: SketchView, private val resolution: Int) {
    constructor(extentHint: Float, resolution: Int) : this(SketchView(0f, 0f, extentHint), resolution)

    /** The view to render next, or the result when [done]. */
    var view: SketchView = first
        private set
    var done: Boolean = false
        private set
    private var widenings = 0
    private var refines = 0

    /** Takes the drawn [bounds] (left, bottom, right, top pixels) of a render at [view]; null when nothing drew. */
    fun measured(bounds: IntArray?) {
        check(!done) { "the fit is done" }
        if (bounds == null) {
            done = true
            return
        }
        val touches = bounds[0] == 0 || bounds[1] == 0 || bounds[2] == resolution - 1 || bounds[3] == resolution - 1
        if (touches && widenings < FIT_WIDENINGS) {
            view = SketchView(view.centreX, view.centreY, view.extent * FIT_GROWTH)
            widenings++
            return
        }
        val fit = fitted(view, bounds)
        if (touches || fit.extent > view.extent / FIT_REFINE_ZOOM || refines == FIT_REFINES) done = true else refines++
        view = fit
    }

    private fun fitted(view: SketchView, bounds: IntArray): SketchView {
        fun toPose(pixel: Int, centre: Float) = centre + ((pixel + 0.5f) / resolution * 2f - 1f) * view.extent
        val left = toPose(bounds[0], view.centreX)
        val right = toPose(bounds[2], view.centreX)
        val bottom = toPose(bounds[1], view.centreY)
        val top = toPose(bounds[3], view.centreY)
        val half = (maxOf(right - left, top - bottom) / 2f + view.extent / resolution) * FIT_MARGIN
        return SketchView((left + right) / 2f, (bottom + top) / 2f, maxOf(half, view.extent * FIT_MIN_FRACTION))
    }

    private companion object {
        const val FIT_WIDENINGS = 2
        const val FIT_GROWTH = 3f
        const val FIT_MARGIN = 1.08f
        const val FIT_REFINES = 2
        const val FIT_REFINE_ZOOM = 2f
        const val FIT_MIN_FRACTION = 0.02f
    }
}

/**
 * The view that holds the box [min] until [max] (scene units) under the sketch's pitch then yaw, with a small margin
 * so the first render does not touch the edge.
 */
internal fun boxView(min: Vector3f, max: Vector3f, pitch: Float, yaw: Float): SketchView {
    val turn = Matrix4f().rotateX(Math.toRadians(pitch.toDouble()).toFloat()).rotateY(Math.toRadians(yaw.toDouble()).toFloat())
    var left = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = Float.MAX_VALUE
    var top = -Float.MAX_VALUE
    val corner = Vector3f()
    for (i in 0 until BOX_CORNERS) {
        corner.set(if (i and 1 == 0) min.x else max.x, if (i and 2 == 0) min.y else max.y, if (i and 4 == 0) min.z else max.z)
        turn.transformPosition(corner)
        left = minOf(left, corner.x)
        right = maxOf(right, corner.x)
        bottom = minOf(bottom, corner.y)
        top = maxOf(top, corner.y)
    }
    val half = maxOf(right - left, top - bottom) / 2f * BOX_VIEW_MARGIN
    return SketchView((left + right) / 2f, (bottom + top) / 2f, maxOf(half, MIN_BOX_EXTENT))
}

private const val BOX_CORNERS = 8
private const val BOX_VIEW_MARGIN = 1.1f
private const val MIN_BOX_EXTENT = 0.5f

/**
 * Splits [parts] scene parts into draws of at most [batch] parts. A scene with no parts still gives one empty draw,
 * so its targets clear and bind.
 */
internal class PassCursor(private val parts: Int, private val batch: Int) {
    init {
        require(parts >= 0 && batch > 0) { "bad pass cursor: $parts parts, batch $batch" }
    }

    private var started = false
    private var drawn = 0

    val done: Boolean get() = started && drawn == parts

    /** True before the first draw, when the targets still need a clear. */
    val first: Boolean get() = !started

    /** The next draw is parts [from] until [to]; call [advance] once it is drawn. */
    val from: Int get() = drawn
    val to: Int get() = minOf(parts, drawn + batch)

    fun advance() {
        check(!done) { "the pass is done" }
        drawn = to
        started = true
    }
}
