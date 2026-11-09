package org.shipwrights.enderkinesis.dimension

import org.shipwrights.enderkinesis.util.smoothstep

/**
 * The flooded root network. Junction points are scattered unevenly (density and depth drift over
 * hundreds of blocks), and each point links to its natural neighbours: a link exists only if no
 * third point sits between its ends (a relative-neighbourhood graph, the shape cave and root mats
 * tend toward). Each link is drawn as a winding, swelling tube; some throw short dead-end spurs,
 * and junctions open into small chambers. The Mother Tree's junction sits under the heart shaft.
 *
 * Every link is a pure function of its two ends and their neighbourhood, so both chunks along a
 * link compute the same tube. The chunk generator caches each cell's links per world and paints
 * each chunk's slice with [paintChunk] before its column pass.
 */
object WohlonRootField {

    /** Top of the flooded heart-shaft descent: sea level − 10. */
    const val CAVE_TOP_Y = WohlonnogondoniaChunkGenerator.SEA_LEVEL_Y - 10
    /** Highest block a tube can carve; the rare shallow tubes break out of low ground under it. */
    const val BAND_TOP_Y = WohlonnogondoniaChunkGenerator.SEA_LEVEL_Y + 16
    /** Floor: one above the bedrock layer. */
    const val CAVE_BOTTOM_Y = WohlonnogondoniaChunkGenerator.MIN_Y + 1
    /** Shell of root block lining each tube, in blocks past the carve. */
    const val ROOT_SHELL = 1.35

    const val SOLID = 0
    const val TUNNEL = 1
    const val LINING = 2

    // ---- junction points ----
    const val CELL_SIZE = 40
    /** Cells scanned on each side: covers [MAX_LINK] plus meander and radius. */
    const val CELL_SEARCH = 3
    private const val NODE_MIN_Y = CAVE_BOTTOM_Y + 6
    /** Deep junctions stay below most pool floors, so breaks into the open stay rare. */
    private const val NODE_MAX_Y = 40
    private const val DENSITY_FREQ = 0.12
    private const val LAYER_FREQ = 0.09
    private const val LAYER_MID_Y = 10.0
    private const val LAYER_AMP = 34.0
    private const val LAYER_SPREAD = 30.0
    /** Share of junctions just under the ground; their tubes are the ones that break out. */
    private const val SHALLOW_CHANCE = 0.04
    private const val SHALLOW_MIN_Y = 56.0
    private const val SHALLOW_SPREAD = 10.0
    private const val NODE_RADIUS_MIN = 1.6
    private const val NODE_RADIUS_SPREAD = 1.7

    // ---- links ----
    private const val MAX_LINK = 90.0
    /** Vertical gaps count extra, so most links run near-level and steep ones stay rare. */
    private const val VERTICAL_WEIGHT = 1.5
    /** Dropped links leave dead ends and keep loops occasional: mostly branching, like real root mats. */
    private const val LINK_DROP_CHANCE = 0.2
    private const val LINK_STEP = 3.0
    private const val MEANDER_SIDE = 0.2
    private const val MEANDER_UP = 0.08
    /** Second, faster wiggle on top of the main bend, as a share of it. */
    private const val MEANDER_DETAIL = 0.35
    /** Links meet a junction anywhere in this share of its radius, not all at its centre. */
    private const val END_JITTER = 0.85
    private const val LINK_RADIUS_VARIATION = 0.3
    private const val CHAMBER_SCALE = 1.2

    // ---- spurs: short dead-end side branches ----
    private const val SPUR_CHANCE = 0.35
    private const val SPUR_LENGTH_MIN = 6.0
    private const val SPUR_LENGTH_SPREAD = 12.0
    private const val SPUR_SHARE = 0.6
    private const val PINCH_RADIUS = 0.4

    // ---- the Mother Tree's junction, under the heart shaft ----
    private const val MOTHER_Y = 34.0
    private const val MOTHER_RADIUS = 4.5

    // ---- carving ----
    /** Sections run taller than wide, like peat-pipe outlets. */
    private const val VERTICAL_STRETCH = 1.35
    private const val INV_STRETCH_SQ = 1.0 / (VERTICAL_STRETCH * VERTICAL_STRETCH)
    /** Wall roughness, so tubes do not read as perfect cylinders. */
    private const val WALL_ROUGHNESS = 0.35
    private const val WALL_FREQ = 0.15

    // ---- liveness and size trend with distance from the Mother Tree ----
    private const val SCALE_NEAR = 1.25
    private const val SCALE_FAR = 0.6
    private const val TREND_START = 150.0
    private const val TREND_END = 1500.0
    private const val DEAD_LINING_THRESHOLD = 0.75
    private const val LIVE_RADIUS = 200.0
    private const val DEAD_RADIUS = 1400.0
    private const val LIVE_FREQ = 0.03
    private const val LIVE_FREQ_Y = 0.06

    /** One tapered capsule, `(x0, y0, z0, r0) → (x1, y1, z1, r1)`. */
    class Segment(
        @JvmField val x0: Double, @JvmField val y0: Double, @JvmField val z0: Double, @JvmField val r0: Double,
        @JvmField val x1: Double, @JvmField val y1: Double, @JvmField val z1: Double, @JvmField val r1: Double,
    )

    @JvmField
    val EMPTY: Array<Segment> = emptyArray()

    /** 0 under the tree (all lining is living root), rising to 1 far out (dead channels). */
    fun deadness(wx: Int, wz: Int): Double =
        smoothstep((Math.sqrt(wx.toDouble() * wx + wz.toDouble() * wz) - LIVE_RADIUS) / (DEAD_RADIUS - LIVE_RADIUS))

    /** Whether rotting lining survives at this block; far out only patches remain. */
    fun liningSurvives(wx: Int, wy: Int, wz: Int, dead: Double): Boolean {
        if (dead <= 0.0) return true
        val live = 0.5 + 0.5 * noise3(wx * LIVE_FREQ, wy * LIVE_FREQ_Y, wz * LIVE_FREQ, 101)
        return live >= dead * DEAD_LINING_THRESHOLD
    }

    /** Systems thicken toward the Mother Tree. */
    private fun radiusScale(x: Double, z: Double): Double {
        val t = smoothstep((Math.sqrt(x * x + z * z) - TREND_START) / (TREND_END - TREND_START))
        return SCALE_NEAR + (SCALE_FAR - SCALE_NEAR) * t
    }

    // ------------------------------------------------------------------
    //  building
    // ------------------------------------------------------------------

    /**
     * Junction points in cell `(cx, cz)`, packed `[x, y, z, r]` per node, followed by any [extra]
     * nodes (child-tree heart shafts) that fall in the cell.
     */
    private fun nodesIn(cx: Int, cz: Int, extra: (Int, Int) -> DoubleArray): DoubleArray {
        val natural = naturalNodesIn(cx, cz)
        val added = extra(cx, cz)
        return if (added.isEmpty()) natural else natural + added
    }

    private fun naturalNodesIn(cx: Int, cz: Int): DoubleArray {
        val rng = Rng(hash3(cx, 7, cz, 0x40DE))
        val density = 0.5 + 0.5 * noise3(cx * DENSITY_FREQ, 0.0, cz * DENSITY_FREQ, 211)
        var count = (if (rng.next() < density * 1.2) 1 else 0) + (if (rng.next() < density * 0.5) 1 else 0)
        val mother = cx == 0 && cz == 0
        if (mother) count++
        val out = DoubleArray(count * 4)
        val layer = LAYER_MID_Y + LAYER_AMP * noise3(cx * LAYER_FREQ, 0.0, cz * LAYER_FREQ, 223)
        var n = 0
        if (mother) {
            out[0] = 0.5; out[1] = MOTHER_Y; out[2] = 0.5; out[3] = MOTHER_RADIUS
            n = 1
        }
        while (n < count) {
            val x = cx * CELL_SIZE + rng.next() * CELL_SIZE
            val z = cz * CELL_SIZE + rng.next() * CELL_SIZE
            out[n * 4] = x
            val deep = (layer + (rng.next() - 0.5) * LAYER_SPREAD).coerceIn(NODE_MIN_Y.toDouble(), NODE_MAX_Y.toDouble())
            val shallowRoll = rng.next()
            val shallowY = SHALLOW_MIN_Y + rng.next() * SHALLOW_SPREAD
            out[n * 4 + 1] = if (shallowRoll < SHALLOW_CHANCE) shallowY else deep
            out[n * 4 + 2] = z
            out[n * 4 + 3] = (NODE_RADIUS_MIN + rng.next() * NODE_RADIUS_SPREAD) * radiusScale(x, z)
            n++
        }
        return out
    }

    /**
     * Tubes for every link owned by a node in cell `(cx, cz)` (a link belongs to its lower-id end),
     * plus a chamber at each of the cell's nodes. Deterministic and bounded. [extra] adds fixed
     * junctions (child-tree heart shafts); it must be a pure function of the cell.
     */
    fun buildCell(cx: Int, cz: Int, extra: (Int, Int) -> DoubleArray): Array<Segment> {
        val own = nodesIn(cx, cz, extra)
        if (own.isEmpty()) return EMPTY
        // Neighbourhood: every node that can be a link end or a witness for this cell's nodes.
        val near = ArrayList<DoubleArray>()
        val ids = ArrayList<Long>()
        for (dx in -CELL_SEARCH..CELL_SEARCH) {
            for (dz in -CELL_SEARCH..CELL_SEARCH) {
                val nodes = nodesIn(cx + dx, cz + dz, extra)
                for (k in 0 until nodes.size / 4) {
                    near += doubleArrayOf(nodes[k * 4], nodes[k * 4 + 1], nodes[k * 4 + 2], nodes[k * 4 + 3])
                    ids += nodeId(cx + dx, cz + dz, k)
                }
            }
        }
        val out = ArrayList<Segment>(64)
        for (k in 0 until own.size / 4) {
            val aId = nodeId(cx, cz, k)
            val a = doubleArrayOf(own[k * 4], own[k * 4 + 1], own[k * 4 + 2], own[k * 4 + 3])
            out += Segment(a[0], a[1], a[2], a[3] * CHAMBER_SCALE, a[0], a[1], a[2], a[3] * CHAMBER_SCALE)
            for (j in near.indices) {
                val bId = ids[j]
                if (bId <= aId) continue
                val b = near[j]
                val dab = weightedDistance(a, b)
                if (dab > MAX_LINK) continue
                if (hasWitness(a, b, dab, near)) continue
                val rng = Rng(hash3(aId.toInt() xor (bId ushr 32).toInt(), bId.toInt(), (aId ushr 32).toInt(), 0x11CC))
                if (rng.next() < LINK_DROP_CHANCE) continue
                drawLink(a, b, rng, out)
            }
        }
        return out.toTypedArray()
    }

    private fun nodeId(cx: Int, cz: Int, k: Int): Long =
        ((cx.toLong() and 0xFFFFFF) shl 32) or ((cz.toLong() and 0xFFFFFF) shl 8) or k.toLong()

    private fun weightedDistance(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]; val dy = (a[1] - b[1]) * VERTICAL_WEIGHT; val dz = a[2] - b[2]
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** A third node closer to both ends than they are to each other: the link would pass it by. */
    private fun hasWitness(a: DoubleArray, b: DoubleArray, limit: Double, near: List<DoubleArray>): Boolean {
        for (c in near) {
            if (c === a || c === b) continue
            if (c[0] == a[0] && c[1] == a[1] && c[2] == a[2]) continue
            if (weightedDistance(a, c) < limit && weightedDistance(b, c) < limit) return true
        }
        return false
    }

    /** One winding link from [a] to [b], with an optional dead-end spur. */
    private fun drawLink(nodeA: DoubleArray, nodeB: DoubleArray, rng: Rng, out: MutableList<Segment>) {
        val a = jitterEnd(nodeA, rng)
        val b = jitterEnd(nodeB, rng)
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
        val length = Math.sqrt(dx * dx + dy * dy + dz * dz)
        val flat = Math.sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6)
        val sideX = -dz / flat; val sideZ = dx / flat
        val steps = Math.max(2, Math.ceil(length / LINK_STEP).toInt())
        val phaseSide = rng.next() * 100.0
        val phaseUp = rng.next() * 100.0
        val phaseR = rng.next() * 100.0
        val wiggles = 1.0 + rng.next() * 2.0
        val linkR = 0.5 * (a[3] + b[3]) * (0.75 + rng.next() * 0.4)
        val maxY = Math.max(Math.max(a[1], b[1]), NODE_MAX_Y.toDouble()) + 2.0
        val spurAt = if (rng.next() < SPUR_CHANCE) 1 + rng.int(steps - 1) else -1

        var px = a[0]; var py = a[1]; var pz = a[2]; var pr = a[3]
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val envelope = Math.sin(Math.PI * t)
            val side = length * MEANDER_SIDE * envelope *
                (noise1(t * wiggles + phaseSide) + MEANDER_DETAIL * noise1(t * wiggles * 3.7 + phaseSide + 50.0))
            val up = length * MEANDER_UP * envelope *
                (noise1(t * wiggles + phaseUp) + MEANDER_DETAIL * noise1(t * wiggles * 3.1 + phaseUp + 50.0))
            val nx = a[0] + dx * t + sideX * side
            val ny = (a[1] + dy * t + up).coerceIn(NODE_MIN_Y.toDouble(), maxY)
            val nz = a[2] + dz * t + sideZ * side
            val ends = if (t < 0.15) a[3] + (linkR - a[3]) * (t / 0.15)
            else if (t > 0.85) linkR + (b[3] - linkR) * ((t - 0.85) / 0.15) else linkR
            val nr = (ends * (1.0 + LINK_RADIUS_VARIATION * noise1(t * 3.0 + phaseR))).coerceAtLeast(0.9)
            out += Segment(px, py, pz, pr, nx, ny, nz, nr)
            if (i == spurAt) drawSpur(nx, ny, nz, nx - px, nz - pz, nr, rng, out)
            px = nx; py = ny; pz = nz; pr = nr
        }
    }

    /** A link's end point somewhere inside its junction's chamber, so links do not converge on one point. */
    private fun jitterEnd(node: DoubleArray, rng: Rng): DoubleArray {
        val angle = rng.next() * 2.0 * Math.PI
        val reach = node[3] * END_JITTER * Math.sqrt(rng.next())
        return doubleArrayOf(
            node[0] + Math.cos(angle) * reach,
            node[1] + (rng.next() - 0.5) * node[3] * 0.6,
            node[2] + Math.sin(angle) * reach,
            node[3],
        )
    }

    /** A short side branch that wanders off and pinches shut. */
    private fun drawSpur(x: Double, y: Double, z: Double, dirX: Double, dirZ: Double, parentR: Double, rng: Rng, out: MutableList<Segment>) {
        val base = Math.atan2(dirZ, dirX)
        var yaw = base + (if (rng.next() < 0.5) 1.0 else -1.0) * Math.toRadians(50.0 + rng.next() * 40.0)
        val pitch = (rng.next() - 0.6) * 0.5
        val length = SPUR_LENGTH_MIN + rng.next() * SPUR_LENGTH_SPREAD
        val steps = Math.max(2, (length / LINK_STEP).toInt())
        var px = x; var py = y; var pz = z
        var pr = parentR * SPUR_SHARE
        val maxY = Math.max(y, NODE_MAX_Y.toDouble()) + 2.0
        for (i in 1..steps) {
            yaw += (rng.next() - 0.5) * 0.5
            val nx = px + Math.cos(yaw) * LINK_STEP
            val ny = (py + Math.sin(pitch) * LINK_STEP).coerceIn(NODE_MIN_Y.toDouble(), maxY)
            val nz = pz + Math.sin(yaw) * LINK_STEP
            val nr = if (i == steps) PINCH_RADIUS else pr * 0.9
            out += Segment(px, py, pz, pr, nx, ny, nz, nr)
            px = nx; py = ny; pz = nz; pr = nr
        }
    }

    /** Smooth 1D value noise in `[-1, 1]`. */
    private fun noise1(t: Double): Double = noise3(t, 0.0, 0.0, 227)

    /** Small deterministic generator (LCG) for one build. */
    private class Rng(seed: Int) {
        private var state = seed.toLong() * -7046029254386353131L + 1
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 11) and ((1L shl 53) - 1)).toDouble() / (1L shl 53).toDouble()
        }
        fun int(bound: Int): Int = (next() * bound).toInt().coerceAtMost(bound - 1)
    }

    // ------------------------------------------------------------------
    //  painting
    // ------------------------------------------------------------------

    /** Height of the band [paintChunk] covers. */
    const val BAND_HEIGHT = BAND_TOP_Y - CAVE_BOTTOM_Y + 1

    /**
     * Rasterise every segment that reaches this chunk into [out], indexed
     * `(lx * 16 + lz) * BAND_HEIGHT + (y − CAVE_BOTTOM_Y)`, as [TUNNEL] or [LINING]. Tunnel wins.
     */
    fun paintChunk(systems: List<Array<Segment>>, chunkX0: Int, chunkZ0: Int, out: ByteArray) {
        java.util.Arrays.fill(out, SOLID.toByte())
        for (system in systems) {
            for (s in system) paintSegment(s, chunkX0, chunkZ0, out)
        }
    }

    private fun paintSegment(s: Segment, chunkX0: Int, chunkZ0: Int, out: ByteArray) {
        val rMax = Math.max(s.r0, s.r1) + ROOT_SHELL + WALL_ROUGHNESS
        val minX = Math.max(Math.floor(Math.min(s.x0, s.x1) - rMax).toInt(), chunkX0)
        val maxX = Math.min(Math.ceil(Math.max(s.x0, s.x1) + rMax).toInt(), chunkX0 + 15)
        if (minX > maxX) return
        val minZ = Math.max(Math.floor(Math.min(s.z0, s.z1) - rMax).toInt(), chunkZ0)
        val maxZ = Math.min(Math.ceil(Math.max(s.z0, s.z1) + rMax).toInt(), chunkZ0 + 15)
        if (minZ > maxZ) return
        val padY = rMax * VERTICAL_STRETCH
        val minY = Math.max(Math.floor(Math.min(s.y0, s.y1) - padY).toInt(), CAVE_BOTTOM_Y)
        val maxY = Math.min(Math.ceil(Math.max(s.y0, s.y1) + padY).toInt(), BAND_TOP_Y)
        if (minY > maxY) return

        val ex = s.x1 - s.x0; val ey = s.y1 - s.y0; val ez = s.z1 - s.z0
        val lenSq = ex * ex + ey * ey + ez * ez
        for (wx in minX..maxX) {
            val vx = wx + 0.5
            for (wz in minZ..maxZ) {
                val vz = wz + 0.5
                val colBase = ((wx - chunkX0) * 16 + (wz - chunkZ0)) * BAND_HEIGHT
                for (wy in minY..maxY) {
                    val idx = colBase + (wy - CAVE_BOTTOM_Y)
                    if (out[idx] == TUNNEL.toByte()) continue
                    val vy = wy + 0.5
                    val t = if (lenSq < 1e-6) 0.0
                    else (((vx - s.x0) * ex + (vy - s.y0) * ey + (vz - s.z0) * ez) / lenSq).coerceIn(0.0, 1.0)
                    val dx = vx - (s.x0 + ex * t)
                    val dy = vy - (s.y0 + ey * t)
                    val dz = vz - (s.z0 + ez * t)
                    val d2 = dx * dx + dy * dy * INV_STRETCH_SQ + dz * dz
                    val r = s.r0 + (s.r1 - s.r0) * t
                    val outer = r + ROOT_SHELL + WALL_ROUGHNESS
                    if (d2 > outer * outer) continue
                    val rough = r + WALL_ROUGHNESS * noise3(wx * WALL_FREQ, wy * WALL_FREQ, wz * WALL_FREQ, 53)
                    if (d2 <= rough * rough) {
                        out[idx] = TUNNEL.toByte()
                    } else if (d2 <= (rough + ROOT_SHELL) * (rough + ROOT_SHELL)) {
                        out[idx] = LINING.toByte()
                    }
                }
            }
        }
    }

    // ---- helpers ----

    /** Value noise in `[-1, 1]`, trilinear with smoothstep weights. */
    internal fun noise3(x: Double, y: Double, z: Double, salt: Int): Double {
        val xi = Math.floor(x).toInt(); val yi = Math.floor(y).toInt(); val zi = Math.floor(z).toInt()
        val xf = x - xi; val yf = y - yi; val zf = z - zi
        val u = xf * xf * (3.0 - 2.0 * xf)
        val v = yf * yf * (3.0 - 2.0 * yf)
        val w = zf * zf * (3.0 - 2.0 * zf)
        var acc = 0.0
        for (dz in 0..1) {
            val wz = if (dz == 1) w else 1.0 - w
            for (dy in 0..1) {
                val wy = if (dy == 1) v else 1.0 - v
                for (dx in 0..1) {
                    val wx = if (dx == 1) u else 1.0 - u
                    acc += wx * wy * wz * lattice(xi + dx, yi + dy, zi + dz, salt)
                }
            }
        }
        return acc
    }

    private fun lattice(x: Int, y: Int, z: Int, salt: Int): Double =
        (hash3(x, y, z, salt) and 0xFFFFFF) / 8388608.0 - 1.0

    internal fun hash3(x: Int, y: Int, z: Int, salt: Int): Int {
        var h = x * 0x27D4EB2D
        h = h xor (y * -0x61C88647)
        h = h xor (z * 0x165667B1)
        h = h xor (salt * 0x9E3779B1.toInt()) xor WohlonWorldSeed.salt
        h = h xor (h ushr 15); h *= 0x2C1B3C6D
        h = h xor (h ushr 12); h *= 0x297A2D39
        h = h xor (h ushr 15)
        return h and 0x7FFFFFFF
    }
}
