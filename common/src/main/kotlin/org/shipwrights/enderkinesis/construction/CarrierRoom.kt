package org.shipwrights.enderkinesis.construction

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/** Whether a carrier body with its base cell at (x, y, z) has room there. */
internal fun interface BodyRoom {
    fun fits(x: Int, y: Int, z: Int): Boolean
}

/** Whether a body base at (x, y, z) is out of the plan: see [planLayerOut]. */
internal fun interface OutOfPlan {
    fun out(x: Int, y: Int, z: Int): Boolean
}

/** [planLayerOut] bottom of a ground build: its base stands on the ground, so no base is under it. */
const val GROUND_PLAN_BOTTOM = Int.MIN_VALUE

/** A base on plan layer [y] is out of the plan: past its footprint, over [topY], or under [bottomY]. */
internal fun planLayerOut(y: Int, inFootprint: Boolean, topY: Int, bottomY: Int): Boolean =
    y > topY || y < bottomY || !inFootprint

/** A cell between two carriers' stand floors: a leg cell for each body. */
const val STAND_GAP = 2

/**
 * The box of a body on support [face] from base cell ([x], [y], [z]): layers [from] until [to] out along the face
 * normal, each [k] blocks thick, and [half] blocks each way across. Written into [out] as min x, y, z, max x, y, z.
 */
@Suppress("LongParameterList") // A base cell, a face, a layer range, two sizes and the output.
internal fun bodyBox(x: Int, y: Int, z: Int, face: Direction, from: Int, to: Int, half: Double, k: Double, out: DoubleArray) {
    require(to > from) { "Layer range $from until $to is empty" }
    require(out.size >= BOX_VALUES) { "Box needs $BOX_VALUES values, not ${out.size}" }
    val c = when (face.axis) {
        Direction.Axis.X -> x
        Direction.Axis.Y -> y
        Direction.Axis.Z -> z
    }
    val positive = face.axisDirection == Direction.AxisDirection.POSITIVE
    val near = if (positive) c + 1 - to * k else c + from * k
    val far = near + (to - from) * k
    out[0] = if (face.axis == Direction.Axis.X) near else x + HALF_CELL - half
    out[1] = if (face.axis == Direction.Axis.Y) near else y + HALF_CELL - half
    out[2] = if (face.axis == Direction.Axis.Z) near else z + HALF_CELL - half
    out[3] = if (face.axis == Direction.Axis.X) far else x + HALF_CELL + half
    out[4] = if (face.axis == Direction.Axis.Y) far else y + HALF_CELL + half
    out[5] = if (face.axis == Direction.Axis.Z) far else z + HALF_CELL + half
}

/** Values in a [bodyBox] output. */
const val BOX_VALUES = 6

private const val HALF_CELL = 0.5

/**
 * The body box of a stand claim on [spot] with support [face] ([FLOOR_FACE]: a floor; else the wall spot's body
 * cell), [reach] across each way past the centre cell and [height] out from the support, in the spot's own frame.
 */
internal fun claimBodyBox(spot: Long, face: Int, reach: Double, height: Double, out: DoubleArray) {
    require(reach >= 0.0 && height > 0.0) { "Body sizes must be positive: reach $reach, height $height" }
    val y = BlockPos.getY(spot) + if (face == FLOOR_FACE) 1 else 0
    bodyBox(BlockPos.getX(spot), y, BlockPos.getZ(spot), Direction.from3DDataValue(face), 0, 1, reach + HALF_CELL, height, out)
}

/** Two claimed body boxes ([bodyBox] layout, one frame) leave less than [STAND_GAP] free on every axis: their legs meet. */
internal fun standBoxesClash(a: DoubleArray, b: DoubleArray): Boolean {
    require(a.size >= BOX_VALUES && b.size >= BOX_VALUES) { "Boxes need $BOX_VALUES values" }
    for (axis in 0 until AXES) {
        if (a[axis] - STAND_GAP >= b[axis + AXES] || b[axis] - STAND_GAP >= a[axis + AXES]) return false
    }
    return true
}

private const val AXES = 3

/** What [wayOut] found. */
internal enum class WayOut { OUT, SHUT, UNKNOWN }

/**
 * Bounded flood fill over carrier body bases from ([sx], [sy], [sz]), one cell at a time in six directions,
 * between layers [minY] and [maxY]: [WayOut.OUT] when a base that [room] fits reaches a cell [out] accepts,
 * [WayOut.SHUT] when every base it can reach is searched, and [WayOut.UNKNOWN] past [cap] bases. The walker climbs
 * walls and ceilings, so support does not matter. [seen] keeps every reached base; [queue] is scratch. With
 * [solid], the fill also takes the twelve edge steps, as the walker rounds a wall top or a lip in one move; a step
 * between two [solid] cells is a squeeze it cannot make.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod") // Plain values keep it testable without a level.
internal fun wayOut(
    sx: Int, sy: Int, sz: Int, minY: Int, maxY: Int, room: BodyRoom, out: OutOfPlan,
    queue: LongArrayFIFOQueue, seen: LongOpenHashSet, cap: Int, solid: BodyRoom? = null,
): WayOut {
    require(minY <= maxY) { "Layer range $minY..$maxY is empty" }
    require(cap > 0) { "Cap must be positive: $cap" }
    queue.clear()
    seen.clear()
    val start = BlockPos.asLong(sx, sy, sz)
    queue.enqueue(start)
    seen.add(start)
    while (!queue.isEmpty) {
        val cur = queue.dequeueLong()
        val x = BlockPos.getX(cur)
        val y = BlockPos.getY(cur)
        val z = BlockPos.getZ(cur)
        if (out.out(x, y, z)) return WayOut.OUT
        val steps = if (solid == null) NEIGHBOURS else NEIGHBOURS + EDGES
        for (d in 0 until steps) {
            val nx = x + STEP_X[d]
            val ny = y + STEP_Y[d]
            val nz = z + STEP_Z[d]
            if (ny < minY || ny > maxY) continue
            val key = BlockPos.asLong(nx, ny, nz)
            if (seen.contains(key)) continue
            if (d >= NEIGHBOURS && solid != null && edgeSqueezed(x, y, z, d, solid)) continue
            if (!room.fits(nx, ny, nz)) continue
            if (seen.size >= cap) return WayOut.UNKNOWN
            seen.add(key)
            queue.enqueue(key)
        }
    }
    return WayOut.SHUT
}

/** Edge step [d] from ([x], [y], [z]) passes between two [solid] cells: its two one-axis halves are both blocked. */
private fun edgeSqueezed(x: Int, y: Int, z: Int, d: Int, solid: BodyRoom): Boolean {
    val ax = STEP_X[d]
    val ay = STEP_Y[d]
    val az = STEP_Z[d]
    // Each edge step moves on two axes: the two halves are the first axis alone and the second axis alone.
    val firstX = if (ax != 0) ax else 0
    val firstY = if (ax == 0 && ay != 0) ay else 0
    val firstZ = if (ax == 0 && ay == 0) az else 0
    return solid.fits(x + firstX, y + firstY, z + firstZ) && solid.fits(x + ax - firstX, y + ay - firstY, z + az - firstZ)
}

private const val NEIGHBOURS = 6
private const val EDGES = 12
private val STEP_X = intArrayOf(1, -1, 0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 0, 0, 0)
private val STEP_Y = intArrayOf(0, 0, 1, -1, 0, 0, 1, -1, 1, -1, 0, 0, 0, 0, 1, 1, -1, -1)
private val STEP_Z = intArrayOf(0, 0, 0, 0, 1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1)

/**
 * Work spots the carrier failed to reach, each kept for [ticks] game ticks from the failure. Its own slots: the
 * floors that step-asides remember cannot push a failed spot out. Full, it drops the oldest.
 */
internal class SpotBlacklist(capacity: Int, private val ticks: Long) {
    private val spots = LongArray(capacity)
    private val until = LongArray(capacity) { Long.MIN_VALUE }
    private var next = 0

    init {
        require(capacity > 0) { "Capacity must be positive: $capacity" }
        require(ticks > 0) { "Ticks must be positive: $ticks" }
    }

    /** Adds [spot] at game time [now], or starts its time again when it is in the list. */
    fun add(spot: Long, now: Long) {
        for (i in spots.indices) {
            if (spots[i] == spot && until[i] > now) {
                until[i] = now + ticks
                return
            }
        }
        spots[next] = spot
        until[next] = now + ticks
        next = (next + 1) % spots.size
    }

    /** True when [spot] failed less than [ticks] before game time [now]. */
    fun blocks(spot: Long, now: Long): Boolean {
        for (i in spots.indices) if (spots[i] == spot && now < until[i]) return true
        return false
    }

    fun clear() {
        until.fill(Long.MIN_VALUE)
        next = 0
    }
}
