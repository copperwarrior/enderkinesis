package org.shipwrights.enderkinesis.vine

import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.ceil
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.sqrt
import org.shipwrights.enderkinesis.dimension.TreeSegment
import org.shipwrights.enderkinesis.dimension.TreeTip

/** The "Medium + Long" preset of the woggak-vine-worldgen preview, which the user picked. */
internal object VineGenRules {
    const val MOTHER_VINES = 140
    /** The attractor radius of the Mother crown; other crowns scale [MOTHER_VINES] by their area. */
    const val MOTHER_CROWN_RADIUS = 140.0
    const val LOOP_SHARE = 0.20
    const val REACH_SHARE = 0.05
    /** Of the free strands. */
    const val RIM_SHARE = 0.45
    const val RIM_DISTANCE = 95.0
    /** y 175 on the Mother Tree, whose base is y 74 and crown centre y 252. */
    const val REACH_HEIGHT_SHARE = (175.0 - 74.0) / (252.0 - 74.0)
    const val STRAND_MIN = 6.0
    const val STRAND_MAX = 28.0
    /** Most strands are near the short end. */
    const val STRAND_SKEW = 1.6
    const val MIN_STRAND = 1.0
    const val REACH_PICK = 0.3
    const val REACH_MIN_ROOM = 12.0
    const val LOOP_PICK = 0.25
    const val LOOP_GAP_MIN = 6.0
    const val LOOP_GAP_MAX = 18.0
    const val LOOP_RISE_MAX = 5
    const val LOOP_SLACK = 1.12
    const val LOOP_SLACK_SPREAD = 0.35
    const val LOOP_TRIES = 64
    const val FLOOR_GAP = 0.3
    const val LOOP_FLOOR_GAP = 0.4
    const val ARCH_STRANDS = 7
    const val ARCH_LOOP_SPAN = 14.0
    const val ARCH_CLEARANCE = 2
    const val ARCH_END_SHARE = 0.08
    const val ARCH_DROP_SHARE = 0.5
    const val ARCH_STRAND_MIN = 1.5
    const val ARCH_STRAND_MAX = 4.0
    const val ARCH_MIN_LENGTH = 0.6
    const val ARCH_LOOP_T = 0.2
    const val ARCH_LOOP_SLACK = 1.1
    const val ARCH_LOOP_SLACK_SPREAD = 0.2
    const val ARCH_LOOP_TRIES = 12
}

private const val BRANCH_MIN_LEVEL = 0.35
private const val BRANCH_SAMPLE_STEP = 2.0
private const val GRID = 8
private const val MAX_DROP = 384
private const val ARCH_SPHERE_WINDOW = 5
/** How far the real underside may sit from the planned one: bark and leaf noise move it. */
internal const val BRANCH_SNAP = 2
internal const val LEAF_SNAP = 3
internal const val LOOP_SNAP = 1

internal enum class PlannedKind { STRAND, DROP, LOOP }

/**
 * One vine of a plan. [ay] is the anchor block; the rope hangs from its bottom face.
 * A [PlannedKind.DROP] strand reaches down to [VineGenRules.FLOOR_GAP] above the floor; a loop ends under [bx], [by], [bz].
 */
internal class PlannedVine(
    val kind: PlannedKind,
    val ax: Int, val ay: Int, val az: Int,
    val length: Double,
    val snap: Int,
    val minLength: Double = VineGenRules.MIN_STRAND,
    val bx: Int = 0, val by: Int = 0, val bz: Int = 0,
    val id: Long = 0L,
) {
    val home: Long get() = chunkKey(ax shr 4, az shr 4)
}

/** The same packing as ChunkPos.asLong, so plans need no Minecraft class. */
private fun chunkKey(x: Int, z: Int): Long = (x.toLong() and 0xFFFFFFFFL) or ((z.toLong() and 0xFFFFFFFFL) shl 32)

internal fun interface FloorAt {
    /** The lowest open cell above the ground or the water at this column. */
    fun floorY(x: Int, z: Int): Int
}

internal fun interface SolidAt {
    fun solid(x: Int, y: Int, z: Int): Boolean
}

/** The plan of one tree or arch group, by the chunk of each home anchor. */
internal class VinePlanIndex(plans: List<PlannedVine>) {
    val all: List<PlannedVine> = plans
    private val byChunk: Map<Long, List<PlannedVine>> = plans.groupBy { it.home }
    fun inChunk(chunk: Long): List<PlannedVine> = byChunk[chunk] ?: emptyList()

    companion object {
        val EMPTY = VinePlanIndex(emptyList())
    }
}

/** Vines for one crown: the Medium counts, kept at the same density for each block of crown area. */
internal class TreeVineBudget(
    val rim: Int, val branch: Int, val loops: Int, val reach: Int,
    val rimDistance: Double, val reachMaxY: Double,
) {
    val total: Int get() = rim + branch + loops + reach
}

internal fun treeVineBudget(crownRadius: Double, baseY: Double, crownY: Double): TreeVineBudget {
    require(crownRadius > 0 && crownY > baseY) { "Crown radius $crownRadius, base $baseY, crown $crownY" }
    val k = crownRadius / VineGenRules.MOTHER_CROWN_RADIUS
    val total = (VineGenRules.MOTHER_VINES * k * k).roundToInt()
    val loops = (total * VineGenRules.LOOP_SHARE).roundToInt()
    val reach = (total * VineGenRules.REACH_SHARE).roundToInt()
    val free = total - loops - reach
    val rim = (free * VineGenRules.RIM_SHARE).roundToInt()
    return TreeVineBudget(
        rim, free - rim, loops, reach,
        VineGenRules.RIM_DISTANCE * k, baseY + VineGenRules.REACH_HEIGHT_SHARE * (crownY - baseY),
    )
}

/** Wood capsules and leaf ellipsoids of one tree, in an XZ grid, for the plan's room and loop tests. */
internal class ObstacleField : SolidAt {
    private var shapes = DoubleArray(256)
    private var size = 0
    private val grid = HashMap<Long, MutableList<Int>>()

    fun addCapsule(segment: TreeSegment) = segment.run {
        val r = max(startRadius, endRadius)
        add(CAPSULE, doubleArrayOf(startX, startY, startZ, endX, endY, endZ, startRadius, endRadius),
            min(startX, endX) - r, max(startX, endX) + r, min(startZ, endZ) - r, max(startZ, endZ) + r)
    }

    fun addEllipsoid(cx: Double, cy: Double, cz: Double, rxz: Double, ry: Double) =
        add(ELLIPSOID, doubleArrayOf(cx, cy, cz, rxz, ry, 0.0, 0.0, 0.0), cx - rxz, cx + rxz, cz - rxz, cz + rxz)

    private fun add(type: Int, values: DoubleArray, minX: Double, maxX: Double, minZ: Double, maxZ: Double) {
        if (size + STRIDE > shapes.size) shapes = shapes.copyOf(shapes.size * 2)
        val at = size
        shapes[at] = type.toDouble()
        System.arraycopy(values, 0, shapes, at + 1, values.size)
        size += STRIDE
        for (gx in Math.floorDiv(floor(minX).toInt(), GRID)..Math.floorDiv(floor(maxX).toInt(), GRID)) {
            for (gz in Math.floorDiv(floor(minZ).toInt(), GRID)..Math.floorDiv(floor(maxZ).toInt(), GRID)) {
                grid.getOrPut(chunkKey(gx, gz)) { ArrayList(4) }.add(at)
            }
        }
    }

    override fun solid(x: Int, y: Int, z: Int): Boolean = contains(x + 0.5, y + 0.5, z + 0.5)

    fun contains(px: Double, py: Double, pz: Double): Boolean {
        val cell = grid[chunkKey(Math.floorDiv(floor(px).toInt(), GRID), Math.floorDiv(floor(pz).toInt(), GRID))] ?: return false
        for (at in cell) if (inside(at, px, py, pz)) return true
        return false
    }

    private fun inside(at: Int, px: Double, py: Double, pz: Double): Boolean {
        val s = shapes
        if (s[at] == ELLIPSOID.toDouble()) {
            val dx = (px - s[at + 1]) / s[at + 4]
            val dy = (py - s[at + 2]) / s[at + 5]
            val dz = (pz - s[at + 3]) / s[at + 4]
            return dx * dx + dy * dy + dz * dz <= 1.0
        }
        val ax = s[at + 1]; val ay = s[at + 2]; val az = s[at + 3]
        val dx = s[at + 4] - ax; val dy = s[at + 5] - ay; val dz = s[at + 6] - az
        val len2 = dx * dx + dy * dy + dz * dz
        val t = if (len2 < 1e-12) 0.0 else (((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / len2).coerceIn(0.0, 1.0)
        val qx = px - ax - t * dx; val qy = py - ay - t * dy; val qz = pz - az - t * dz
        val r = s[at + 7] + (s[at + 8] - s[at + 7]) * t
        return qx * qx + qy * qy + qz * qz <= r * r
    }

    private companion object {
        const val CAPSULE = 0
        const val ELLIPSOID = 1
        const val STRIDE = 9
    }
}

/** Anchor candidates of a tree, x, y, z triples: under leaves at the crown edge ([rim]) and under limbs and leaves. */
internal class TreeVineSite(val rim: IntArray, val branch: IntArray, val field: ObstacleField)

/**
 * Candidates from a skeleton: the underside of each limb that is not steep (a trunk has no underside),
 * and points under each leaf blob. The tests run against the same shapes the chunk generator paints.
 */
@Suppress("LongParameterList") // the skeleton, its leaf blob size and the crown centre
internal fun skeletonVineSite(
    segments: List<TreeSegment>, tips: List<TreeTip>, blobRxz: Int, blobRy: Int,
    crownX: Double, crownZ: Double, rimDistance: Double,
): TreeVineSite {
    val field = ObstacleField()
    for (s in segments) field.addCapsule(s)
    for (t in tips) field.addEllipsoid(t.x.toDouble(), t.y.toDouble(), t.z.toDouble(), blobRxz.toDouble(), blobRy.toDouble())
    val rim = IntList()
    val branch = IntList()
    for (s in segments) {
        val dx = s.endX - s.startX; val dy = s.endY - s.startY; val dz = s.endZ - s.startZ
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6 || hypot(dx, dz) / len < BRANCH_MIN_LEVEL) continue
        val steps = ceil(len / BRANCH_SAMPLE_STEP).toInt()
        for (k in 0..steps) {
            val t = k.toDouble() / steps
            val r = s.startRadius + (s.endRadius - s.startRadius) * t
            val x = floor(s.startX + dx * t).toInt()
            val z = floor(s.startZ + dz * t).toInt()
            // The lowest voxel whose centre is inside the limb.
            val y = ceil(s.startY + dy * t - r - 0.5).toInt()
            if (isUnderside(field, x, y, z)) branch.add(x, y, z)
        }
    }
    for (t in tips) {
        val step = max(2, blobRxz / 3)
        val reach = (blobRxz * 0.8).toInt()
        var ox = -reach
        while (ox <= reach) {
            var oz = -reach
            while (oz <= reach) {
                val q = 1.0 - (ox * ox + oz * oz).toDouble() / (blobRxz * blobRxz)
                if (q > 0) {
                    val x = t.x + ox
                    val z = t.z + oz
                    val y = ceil(t.y - blobRy * sqrt(q) - 0.5).toInt()
                    if (isUnderside(field, x, y, z)) {
                        if (hypot(x + 0.5 - crownX, z + 0.5 - crownZ) >= rimDistance) rim.add(x, y, z) else branch.add(x, y, z)
                    }
                }
                oz += step
            }
            ox += step
        }
    }
    return TreeVineSite(rim.toArray(), branch.toArray(), field)
}

private fun isUnderside(field: SolidAt, x: Int, y: Int, z: Int) = field.solid(x, y, z) && !field.solid(x, y - 1, z)

/** Free blocks under the anchor block's bottom face, down to the floor or the first solid cell. */
internal fun dropRoom(solid: SolidAt, floorAt: FloorAt, x: Int, y: Int, z: Int): Int {
    val floorY = floorAt.floorY(x, z)
    var yy = y - 1
    var steps = 0
    while (yy >= floorY && !solid.solid(x, yy, z) && steps < MAX_DROP) {
        yy--
        steps++
    }
    return y - (yy + 1)
}

/**
 * The plan of one tree, after the preview's genMother: canopy-edge and branch strands, then drops to the
 * water from low limbs, then loops between two limb undersides. Each candidate is used once.
 */
internal fun planTreeVines(site: TreeVineSite, budget: TreeVineBudget, floorAt: FloorAt, seed: Long): List<PlannedVine> {
    val field = site.field
    val rim = shuffled(site.rim, seed xor 0x52494DL)
    val branch = shuffled(site.branch, seed xor 0x4252L)
    val used = HashSet<Long>()
    val out = ArrayList<PlannedVine>(budget.total)
    fun cellKey(x: Int, y: Int, z: Int) = chunkKey(x shr 1, z shr 1) * 31 + (y shr 1)
    fun take(list: IntArray, want: Int, make: (Int, Int, Int, Int) -> PlannedVine?) {
        var got = 0
        var i = 0
        while (i < list.size / 3 && got < want) {
            val x = list[i * 3]; val y = list[i * 3 + 1]; val z = list[i * 3 + 2]
            val key = cellKey(x, y, z)
            if (key !in used) {
                val vine = make(x, y, z, i)
                if (vine != null) {
                    used += key
                    if (vine.kind == PlannedKind.LOOP) used += cellKey(vine.bx, vine.by, vine.bz)
                    out += vine
                    got++
                }
            }
            i++
        }
    }
    fun strand(x: Int, y: Int, z: Int, i: Int, salt: Int, snap: Int): PlannedVine? {
        val room = dropRoom(field, floorAt, x, y, z) - VineGenRules.FLOOR_GAP
        if (room < VineGenRules.MIN_STRAND) return null
        val u = unit(seed, i, salt, 7).pow(VineGenRules.STRAND_SKEW)
        val length = min(VineGenRules.STRAND_MIN + (VineGenRules.STRAND_MAX - VineGenRules.STRAND_MIN) * u, room)
        return PlannedVine(PlannedKind.STRAND, x, y, z, length, snap, id = vineId(seed, x, y, z, salt))
    }
    take(rim, budget.rim) { x, y, z, i -> strand(x, y, z, i, 1, LEAF_SNAP) }
    take(branch, budget.branch) { x, y, z, i -> strand(x, y, z, i, 2, LEAF_SNAP) }
    take(branch, budget.reach) { x, y, z, i ->
        if (y > budget.reachMaxY || unit(seed, i, 9, 9) > VineGenRules.REACH_PICK) return@take null
        val room = dropRoom(field, floorAt, x, y, z) - VineGenRules.FLOOR_GAP
        if (room < VineGenRules.REACH_MIN_ROOM) null
        else PlannedVine(PlannedKind.DROP, x, y, z, room, BRANCH_SNAP, id = vineId(seed, x, y, z, 5))
    }
    val near = CandidateGrid(branch)
    take(branch, budget.loops) { x, y, z, i ->
        if (unit(seed, i, 3, 3) > VineGenRules.LOOP_PICK) return@take null
        planTreeLoop(x, y, z, i, near, branch, used, field, floorAt, seed, ::cellKey)
    }
    return out
}

@Suppress("LongParameterList") // the loop's anchor and the plan state it reads
private fun planTreeLoop(
    x: Int, y: Int, z: Int, i: Int, near: CandidateGrid, branch: IntArray, used: Set<Long>,
    field: SolidAt, floorAt: FloorAt, seed: Long, cellKey: (Int, Int, Int) -> Long,
): PlannedVine? {
    var tries = 0
    for (j in near.around(x, z, VineGenRules.LOOP_GAP_MAX)) {
        if (tries >= VineGenRules.LOOP_TRIES) break
        val bx = branch[j * 3]; val by = branch[j * 3 + 1]; val bz = branch[j * 3 + 2]
        val gapXZ = hypot((bx - x).toDouble(), (bz - z).toDouble())
        if (gapXZ < VineGenRules.LOOP_GAP_MIN || gapXZ > VineGenRules.LOOP_GAP_MAX || Math.abs(by - y) > VineGenRules.LOOP_RISE_MAX) continue
        if (cellKey(bx, by, bz) in used) continue
        tries++
        val gap = hypot(gapXZ, (by - y).toDouble())
        val length = gap * (VineGenRules.LOOP_SLACK + VineGenRules.LOOP_SLACK_SPREAD * unit(seed, i, 5, 5))
        val nodes = catenaryNodes(x + 0.5, y.toDouble(), z + 0.5, bx + 0.5, by.toDouble(), bz + 0.5, length, VINE_TARGET_SPACING)
        if (!ropeClear(nodes, field, floorAt)) continue
        return PlannedVine(PlannedKind.LOOP, x, y, z, length, LOOP_SNAP, bx = bx, by = by, bz = bz, id = vineId(seed, x, y, z, 3))
    }
    return null
}

/** True when no inner node is in a solid cell or nearer than [VineGenRules.LOOP_FLOOR_GAP] to the floor. */
internal fun ropeClear(nodes: DoubleArray, solid: SolidAt, floorAt: FloorAt): Boolean {
    val n = nodes.size / 3
    for (k in 2 until n - 2) {
        val x = floor(nodes[k * 3]).toInt()
        val y = nodes[k * 3 + 1]
        val z = floor(nodes[k * 3 + 2]).toInt()
        if (solid.solid(x, floor(y).toInt(), z)) return false
        if (y < floorAt.floorY(x, z) + VineGenRules.LOOP_FLOOR_GAP) return false
    }
    return true
}

/**
 * The plan of the arches of one root path, after the preview's genArch. An arch is a run of path
 * points whose underside is off the floor; strands hang only where it is [VineGenRules.ARCH_CLEARANCE]
 * or more blocks above the floor, and not from the arch ends.
 */
internal fun planArchVines(points: IntArray, radii: DoubleArray, floorAt: FloorAt, seed: Long): List<PlannedVine> {
    val n = radii.size
    require(points.size >= n * 3) { "${points.size / 3} points for $n radii" }
    val out = ArrayList<PlannedVine>()
    val spheres = SolidAt { x, y, z -> inSpheres(points, radii, x, y, z, 0, n - 1) }
    var start = 0
    var arch = 0
    while (start < n) {
        if (archRoom(points, radii, start, floorAt) < 1) { start++; continue }
        var end = start
        while (end + 1 < n && archRoom(points, radii, end + 1, floorAt) >= 1) end++
        if (end - start >= 2) planOneArch(points, radii, start, end, spheres, floorAt, seed + arch * 0x9E3779B97F4A7C15uL.toLong(), out)
        arch++
        start = end + 1
    }
    return out
}

@Suppress("LongParameterList") // one arch and the shapes and floor it is tested against
private fun planOneArch(
    points: IntArray, radii: DoubleArray, first: Int, last: Int, spheres: SolidAt, floorAt: FloorAt, seed: Long,
    out: MutableList<PlannedVine>,
) {
    val arc = DoubleArray(last - first + 1)
    for (i in first + 1..last) {
        arc[i - first] = arc[i - first - 1] + hypot((points[i * 3] - points[i * 3 - 3]).toDouble(), (points[i * 3 + 2] - points[i * 3 - 1]).toDouble())
    }
    val total = arc.last()
    if (total <= 0) return
    // Candidates: x, y, z, t (x1000), room; sorted by a hash so the pick is spread along the arch.
    val cands = ArrayList<IntArray>()
    val seen = HashSet<Long>()
    for (i in first..last) {
        val t = arc[i - first] / total
        if (t < VineGenRules.ARCH_END_SHARE || t > 1 - VineGenRules.ARCH_END_SHARE) continue
        val x = points[i * 3]; val z = points[i * 3 + 2]
        if (!seen.add(chunkKey(x, z))) continue
        val y = undersideY(points, radii, x, z, i) ?: continue
        val room = dropRoom(spheres, floorAt, x, y, z)
        if (room < VineGenRules.ARCH_CLEARANCE) continue
        cands += intArrayOf(x, y, z, (t * 1000).toInt(), room)
    }
    cands.sortBy { unit(seed, it[0], it[2], 8) }
    val cols = HashSet<Long>()
    var got = 0
    for (c in cands) {
        if (got >= VineGenRules.ARCH_STRANDS) break
        val room = c[4] - VineGenRules.FLOOR_GAP
        val drop = unit(seed, c[0], c[2], 11) < VineGenRules.ARCH_DROP_SHARE
        val length = if (drop) room else min(
            VineGenRules.ARCH_STRAND_MIN + (VineGenRules.ARCH_STRAND_MAX - VineGenRules.ARCH_STRAND_MIN) * unit(seed, c[0], c[2], 9), room,
        )
        if (length < VineGenRules.ARCH_MIN_LENGTH) continue
        cols += chunkKey(c[0], c[2])
        out += PlannedVine(
            if (drop) PlannedKind.DROP else PlannedKind.STRAND, c[0], c[1], c[2], length, LOOP_SNAP,
            VineGenRules.ARCH_MIN_LENGTH, id = vineId(seed, c[0], c[1], c[2], 8),
        )
        got++
    }
    val span = hypot((points[last * 3] - points[first * 3]).toDouble(), (points[last * 3 + 2] - points[first * 3 + 2]).toDouble())
    if (span < VineGenRules.ARCH_LOOP_SPAN || cands.size < 2) return
    fun near(t: Double): IntArray? = cands.minByOrNull { abs(it[3] / 1000.0 - t) + if (chunkKey(it[0], it[2]) in cols) 0.5 else 0.0 }
    val a = near(VineGenRules.ARCH_LOOP_T) ?: return
    val b = near(1 - VineGenRules.ARCH_LOOP_T) ?: return
    val gapXZ = hypot((b[0] - a[0]).toDouble(), (b[2] - a[2]).toDouble())
    if (gapXZ < 1.0) return
    val gap = hypot(gapXZ, (b[1] - a[1]).toDouble())
    var slack = VineGenRules.ARCH_LOOP_SLACK + VineGenRules.ARCH_LOOP_SLACK_SPREAD * unit(seed, 1, 2, 12)
    repeat(VineGenRules.ARCH_LOOP_TRIES) {
        val length = gap * slack
        val nodes = catenaryNodes(a[0] + 0.5, a[1].toDouble(), a[2] + 0.5, b[0] + 0.5, b[1].toDouble(), b[2] + 0.5, length, VINE_TARGET_SPACING)
        if (ropeClear(nodes, spheres, floorAt)) {
            out += PlannedVine(PlannedKind.LOOP, a[0], a[1], a[2], length, LOOP_SNAP, bx = b[0], by = b[1], bz = b[2], id = vineId(seed, a[0], a[1], a[2], 12))
            return
        }
        slack = 1 + (slack - 1) * 0.7
    }
}

/** The underside of point [i]'s centre column: the lowest voxel of the nearby path spheres there. */
private fun undersideY(points: IntArray, radii: DoubleArray, x: Int, z: Int, i: Int): Int? {
    var low = Int.MAX_VALUE
    for (j in max(0, i - ARCH_SPHERE_WINDOW)..min(radii.size - 1, i + ARCH_SPHERE_WINDOW)) {
        val dx = (x - points[j * 3]).toDouble(); val dz = (z - points[j * 3 + 2]).toDouble()
        val left = radii[j] * radii[j] - dx * dx - dz * dz
        if (left < 0) continue
        low = min(low, points[j * 3 + 1] - floor(sqrt(left)).toInt())
    }
    return if (low == Int.MAX_VALUE) null else low
}

private fun archRoom(points: IntArray, radii: DoubleArray, i: Int, floorAt: FloorAt): Int {
    val x = points[i * 3]; val z = points[i * 3 + 2]
    return points[i * 3 + 1] - floor(radii[i]).toInt() - floorAt.floorY(x, z)
}

/** The painter's test: a voxel is wood when its offset from an integer path point is within the radius. */
private fun inSpheres(points: IntArray, radii: DoubleArray, x: Int, y: Int, z: Int, first: Int, last: Int): Boolean {
    for (j in first..last) {
        val dx = (x - points[j * 3]).toDouble(); val dy = (y - points[j * 3 + 1]).toDouble(); val dz = (z - points[j * 3 + 2]).toDouble()
        if (dx * dx + dy * dy + dz * dz <= radii[j] * radii[j]) return true
    }
    return false
}

/** Nodes of a strand hanging straight down from ([x], [y], [z]), at most [spacing] apart. */
internal fun strandNodes(x: Double, y: Double, z: Double, length: Double, spacing: Double): DoubleArray {
    require(length > 0 && spacing > 0) { "Strand length $length, spacing $spacing" }
    val n = max(2, ceil(length / spacing).toInt() + 1)
    val out = DoubleArray(n * 3)
    for (i in 0 until n) {
        out[i * 3] = x
        out[i * 3 + 1] = y - length * i / (n - 1)
        out[i * 3 + 2] = z
    }
    return out
}

/**
 * Nodes of a rope of [length] that hangs at rest from a to b: a true catenary, so a loop does not swing
 * when it is made. The nodes are at equal arc steps of at most [spacing].
 */
@Suppress("LongParameterList") // two points and the rope
internal fun catenaryNodes(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, length: Double, spacing: Double): DoubleArray {
    val h = hypot(bx - ax, bz - az)
    val v = by - ay
    require(h > 1e-3) { "A catenary needs a horizontal span; got $h" }
    require(length > hypot(h, v)) { "Rope $length is not longer than the gap ${hypot(h, v)}" }
    val s = sqrt(length * length - v * v)
    // 2a·sinh(h/2a) falls from infinity to h as a grows; bisect on it.
    var lo = h / 1400.0
    var hi = max(h, sqrt(h * h * h / (24.0 * max(s - h, 1e-9)))) * 4.0
    repeat(200) {
        val mid = (lo + hi) / 2
        if (2 * mid * sinh(h / (2 * mid)) > s) lo = mid else hi = mid
    }
    val a = (lo + hi) / 2
    val u0 = h / 2 - a * asinh(v / (2 * a * sinh(h / (2 * a))))
    val c = -a * cosh(-u0 / a)
    val s0 = sinh(-u0 / a)
    val n = max(2, ceil(length / spacing).toInt() + 1)
    val out = DoubleArray(n * 3)
    for (i in 0 until n) {
        val arc = length * i / (n - 1)
        val u = if (i == n - 1) h else u0 + a * asinh(arc / a + s0)
        val f = u / h
        out[i * 3] = ax + (bx - ax) * f
        out[i * 3 + 1] = if (i == n - 1) by else ay + a * cosh((u - u0) / a) + c
        out[i * 3 + 2] = az + (bz - az) * f
    }
    return out
}

/** Polyline length of x, y, z triples. */
internal fun polylineLength(nodes: DoubleArray): Double {
    var sum = 0.0
    for (i in 1 until nodes.size / 3) {
        val dx = nodes[i * 3] - nodes[i * 3 - 3]; val dy = nodes[i * 3 + 1] - nodes[i * 3 - 2]; val dz = nodes[i * 3 + 2] - nodes[i * 3 - 1]
        sum += sqrt(dx * dx + dy * dy + dz * dz)
    }
    return sum
}

/** A stable id from the plan seed and the anchor; never 0. */
internal fun vineId(seed: Long, x: Int, y: Int, z: Int, salt: Int): Long {
    val id = mix64(seed xor mix64(x.toLong() * 0x9E3779B1L + y * 0x85EBCA77L + z.toLong() * 0xC2B2AE3DL + salt))
    return if (id == 0L) 1L else id
}

private fun unit(seed: Long, a: Int, b: Int, c: Int): Double =
    (mix64(seed + a * 0x632BE59BD9B4E019L + b * 0x9E3779B97F4A7C15uL.toLong() + c * 0x94D049BB133111EBuL.toLong()) ushr 11) / (1L shl 53).toDouble()

private fun mix64(v: Long): Long {
    var z = v + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

private fun shuffled(flat: IntArray, seed: Long): IntArray {
    val n = flat.size / 3
    val idx = IntArray(n) { it }
    for (i in n - 1 downTo 1) {
        val j = (unit(seed, i, 0, 0) * (i + 1)).toInt().coerceAtMost(i)
        val t = idx[i]; idx[i] = idx[j]; idx[j] = t
    }
    val out = IntArray(flat.size)
    for (i in 0 until n) System.arraycopy(flat, idx[i] * 3, out, i * 3, 3)
    return out
}

/** Candidate indices by XZ cell, so a loop looks for its second anchor only nearby. */
private class CandidateGrid(private val flat: IntArray) {
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        for (i in 0 until flat.size / 3) cells.getOrPut(chunkKey(Math.floorDiv(flat[i * 3], GRID), Math.floorDiv(flat[i * 3 + 2], GRID))) { ArrayList(4) }.add(i)
    }

    /** In list order, so the shuffled order of the candidates stays. */
    fun around(x: Int, z: Int, reach: Double): List<Int> {
        val r = ceil(reach / GRID).toInt()
        val gx = Math.floorDiv(x, GRID); val gz = Math.floorDiv(z, GRID)
        val out = ArrayList<Int>()
        for (dx in -r..r) for (dz in -r..r) cells[chunkKey(gx + dx, gz + dz)]?.let(out::addAll)
        out.sort()
        return out
    }
}

private class IntList {
    private var data = IntArray(96)
    private var size = 0
    fun add(x: Int, y: Int, z: Int) {
        if (size + 3 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x; data[size++] = y; data[size++] = z
    }
    fun toArray(): IntArray = data.copyOf(size)
}
