package org.shipwrights.enderkinesis.client

import net.minecraft.core.BlockPos
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.state.BlockState
import org.joml.FrustumIntersection
import kotlin.math.floor
import kotlin.math.max

/** world = m * local for one plan pose. [m] is a signed axis permutation, so its inverse is its transpose. */
internal class PlanAxes(private val m: IntArray) {
    init {
        require(m.size == MATRIX_SIZE) { "plan axes need a 3x3 matrix" }
        for (row in 0 until AXES) {
            require((0 until AXES).sumOf { kotlin.math.abs(m[row * AXES + it]) } == 1) { "plan axes row $row is not a signed axis" }
        }
    }

    fun worldX(x: Int, y: Int, z: Int): Int = m[0] * x + m[1] * y + m[2] * z
    fun worldY(x: Int, y: Int, z: Int): Int = m[3] * x + m[4] * y + m[5] * z
    fun worldZ(x: Int, y: Int, z: Int): Int = m[6] * x + m[7] * y + m[8] * z

    fun localX(x: Double, y: Double, z: Double): Double = m[0] * x + m[3] * y + m[6] * z
    fun localY(x: Double, y: Double, z: Double): Double = m[1] * x + m[4] * y + m[7] * z
    fun localZ(x: Double, y: Double, z: Double): Double = m[2] * x + m[5] * y + m[8] * z

    /** The plan-local step of each world face in [GHOST_FACES] order, as dx, dy, dz triples. */
    fun localFaceSteps(): IntArray = IntArray(GHOST_FACES.size) { i ->
        val face = i / AXES * AXES
        val wx = GHOST_FACES[face]
        val wy = GHOST_FACES[face + 1]
        val wz = GHOST_FACES[face + 2]
        when (i % AXES) {
            0 -> m[0] * wx + m[3] * wy + m[6] * wz
            1 -> m[1] * wx + m[4] * wy + m[7] * wz
            else -> m[2] * wx + m[5] * wy + m[8] * wz
        }
    }

    companion object {
        const val AXES = 3
        const val MATRIX_SIZE = AXES * AXES
        val IDENTITY = PlanAxes(intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1))
    }
}

/**
 * The 16^3 section grid of a plan in one pose, and the sections near a camera, nearest first. Coordinates are world
 * axes relative to the anchor; a cell box spans [min, max) in blocks.
 */
internal class GhostSectionGrid(val sizeX: Int, val sizeY: Int, val sizeZ: Int, private val axes: PlanAxes) {
    init {
        require(sizeX > 0 && sizeY > 0 && sizeZ > 0) { "empty plan size: $sizeX $sizeY $sizeZ" }
    }

    val countX: Int = sectionsOf(sizeX)
    val countY: Int = sectionsOf(sizeY)
    val countZ: Int = sectionsOf(sizeZ)

    private var near = LongArray(INITIAL_NEAR)
    var nearCount: Int = 0
        private set
    private var windowX = 0
    private var windowY = 0
    private var windowZ = 0

    /** The world box of section ([sx], [sy], [sz]) into [out] as minX, minY, minZ, maxX, maxY, maxZ. */
    fun worldBox(sx: Int, sy: Int, sz: Int, out: IntArray) {
        val x0 = sx * SECTION
        val y0 = sy * SECTION
        val z0 = sz * SECTION
        val x1 = minOf(x0 + SECTION, sizeX) - 1
        val y1 = minOf(y0 + SECTION, sizeY) - 1
        val z1 = minOf(z0 + SECTION, sizeZ) - 1
        val ax = axes.worldX(x0, y0, z0)
        val ay = axes.worldY(x0, y0, z0)
        val az = axes.worldZ(x0, y0, z0)
        val bx = axes.worldX(x1, y1, z1)
        val by = axes.worldY(x1, y1, z1)
        val bz = axes.worldZ(x1, y1, z1)
        out[0] = minOf(ax, bx)
        out[1] = minOf(ay, by)
        out[2] = minOf(az, bz)
        out[3] = max(ax, bx) + 1
        out[4] = max(ay, by) + 1
        out[5] = max(az, bz) + 1
    }

    /**
     * Collects the sections whose box comes within [radius] of the camera at ([cx], [cy], [cz]), nearest first. Read
     * them with [nearX], [nearY], [nearZ] for indices below [nearCount]. The work is bounded by [radius], not the plan.
     * Sections that [filter] does not keep are left out.
     */
    fun collectNear(cx: Double, cy: Double, cz: Double, radius: Int, box: IntArray, filter: SectionFilter = SectionFilter.ALL) {
        require(radius in 1..MAX_RADIUS) { "ghost radius out of range: $radius" }
        val lx = axes.localX(cx, cy, cz)
        val ly = axes.localY(cx, cy, cz)
        val lz = axes.localZ(cx, cy, cz)
        windowX = clampSection(lx - radius, countX)
        windowY = clampSection(ly - radius, countY)
        windowZ = clampSection(lz - radius, countZ)
        val endX = clampSection(lx + radius, countX)
        val endY = clampSection(ly + radius, countY)
        val endZ = clampSection(lz + radius, countZ)
        val radiusSq = radius.toDouble() * radius
        nearCount = 0
        for (sx in windowX..endX) {
            for (sy in windowY..endY) {
                for (sz in windowZ..endZ) {
                    if (!filter.keep(sx, sy, sz)) continue
                    worldBox(sx, sy, sz, box)
                    val distSq = boxDistanceSq(box, cx, cy, cz)
                    if (distSq > radiusSq) continue
                    if (nearCount == near.size) near = near.copyOf(near.size * 2)
                    near[nearCount++] = (distSq.toLong() shl DIST_SHIFT) or
                        ((sx - windowX).toLong() shl X_SHIFT) or ((sy - windowY).toLong() shl Y_SHIFT) or (sz - windowZ).toLong()
                }
            }
        }
        near.sort(0, nearCount)
    }

    fun nearX(i: Int): Int = windowX + (near[i] shr X_SHIFT and WINDOW_MASK).toInt()
    fun nearY(i: Int): Int = windowY + (near[i] shr Y_SHIFT and WINDOW_MASK).toInt()
    fun nearZ(i: Int): Int = windowZ + (near[i] and WINDOW_MASK).toInt()

    companion object {
        const val SECTION = 16
        private const val SECTION_SHIFT = 4
        private const val INITIAL_NEAR = 256
        private const val X_SHIFT = 24
        private const val Y_SHIFT = 12
        private const val DIST_SHIFT = 36
        private const val WINDOW_MASK = 0xFFFL

        /** Keeps the window under 2^12 sections per axis and the squared distance under 2^27. */
        const val MAX_RADIUS = 1024

        fun sectionsOf(size: Int): Int = (size + SECTION - 1) shr SECTION_SHIFT

        private fun clampSection(local: Double, count: Int): Int = (floor(local).toInt() shr SECTION_SHIFT).coerceIn(0, count - 1)

        /** Squared distance from a point to a [min, max) box given as six ints. */
        fun boxDistanceSq(box: IntArray, x: Double, y: Double, z: Double): Double {
            val dx = axisGap(x, box[0], box[3])
            val dy = axisGap(y, box[1], box[4])
            val dz = axisGap(z, box[2], box[5])
            return dx * dx + dy * dy + dz * dz
        }

        private fun axisGap(p: Double, min: Int, max: Int): Double = when {
            p < min -> min - p
            p > max -> p - max
            else -> 0.0
        }

        /** True when the box, moved by (-[cx], -[cy], -[cz]) into camera space, meets [frustum]. */
        @Suppress("LongParameterList") // A box and the camera offset.
        fun inFrustum(frustum: FrustumIntersection, box: IntArray, cx: Double, cy: Double, cz: Double): Boolean =
            frustum.testAab(
                (box[0] - cx).toFloat(), (box[1] - cy).toFloat(), (box[2] - cz).toFloat(),
                (box[3] - cx).toFloat(), (box[4] - cy).toFloat(), (box[5] - cz).toFloat(),
            )
    }
}

/** Plan-local section coordinates to keep; an interface, not a lambda, so the ints are not boxed. */
internal fun interface SectionFilter {
    fun keep(sx: Int, sy: Int, sz: Int): Boolean

    companion object {
        val ALL = SectionFilter { _, _, _ -> true }
    }
}

/** World face steps as dx, dy, dz triples, in Direction ordinal order: down, up, north, south, west, east. */
internal val GHOST_FACES = intArrayOf(0, -1, 0, 0, 1, 0, 0, 0, -1, 0, 0, 1, -1, 0, 0, 1, 0, 0)

/** A plan block that hides the faces it shares with another such block. */
internal fun hidesSharedFaces(state: BlockState): Boolean = state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)

/**
 * The faces of a solid cell at section-local ([x], [y], [z]) that meet a solid neighbour, as a bit mask over the
 * face order of [steps] (from [PlanAxes.localFaceSteps]). [solidAt] takes section-local cells, one step outside too.
 */
internal inline fun ghostHiddenFaces(x: Int, y: Int, z: Int, steps: IntArray, solidAt: (Int, Int, Int) -> Boolean): Int {
    var mask = 0
    for (face in 0 until steps.size / PlanAxes.AXES) {
        val i = face * PlanAxes.AXES
        if (solidAt(x + steps[i], y + steps[i + 1], z + steps[i + 2])) mask = mask or (1 shl face)
    }
    return mask
}

/**
 * Picks GPU sections to free when their bytes pass [budget]: least recently drawn first, never one drawn in the frame
 * [now]. [stamps] and [bytes] are parallel; returns the indices to free.
 */
internal fun ghostEvictions(stamps: LongArray, bytes: LongArray, count: Int, budget: Long, now: Long): IntArray {
    var total = 0L
    for (i in 0 until count) total += bytes[i]
    if (total <= budget) return IntArray(0)
    var candidates = 0
    for (i in 0 until count) if (stamps[i] < now) candidates++
    if (candidates == 0) return IntArray(0)
    // Oldest first, lowest index on a tie; few picks are usually enough, so selection beats sorting a boxed list.
    val taken = BooleanArray(count)
    var picked = 0
    val out = IntArray(candidates)
    while (total > budget && picked < candidates) {
        var best = -1
        for (i in 0 until count) {
            if (!taken[i] && stamps[i] < now && (best < 0 || stamps[i] < stamps[best])) best = i
        }
        taken[best] = true
        total -= bytes[best]
        out[picked++] = best
    }
    return out.copyOf(picked)
}
