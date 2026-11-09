package org.shipwrights.enderkinesis.dimension

import kotlin.math.sqrt

/**
 * Heart-tunnel skeleton: the hollow lateral roots of the Mother Tree, grown by space colonisation
 * (Runions et al. 2007) from the heart cave out to the moat, then shaped after real root systems:
 * area-preserving branching with a rapid near-trunk taper, a few blocks under the ground and rising
 * toward the tips, curved runs, grafts where branches meet, and tips that end as moat outlets,
 * sinkholes or silt pinch-outs.
 *
 * Pure: the same seed and attractors give the same skeleton, so worldgen threads may build it
 * concurrently.
 */
internal object HeartTunnelSkeleton {

    /**
     * Build attractors for a heart-tunnel network. Three components:
     *
     *  - A **dense ring** of points in the moat band
     *    `[moatInnerR, moatOuterR]` — this is the cloud the trunk and
     *    its terminating side branches pull toward, ensuring the
     *    network reaches the moat from any reasonable origin
     *    direction.
     *  - A **sparse intermediate scatter** at radii
     *    `[startRing, moatInnerR]` — these are the "branching
     *    opportunities" between heart and moat; without them the
     *    trunk would walk straight outward with no side branches.
     *  - A small **starter cluster** near the origin within
     *    `attractionDist` so the SCA latches onto an attractor on
     *    iteration one (no need for bias-only ramp-up growth).
     *
     * All points are constrained to `y ∈ [seaLow, seaHigh]`.
     */
    fun buildAttractors(
        cx: Double, cy: Double, cz: Double,
        seaLow: Double, seaHigh: Double,
        startRing: Double,
        moatInnerR: Double, moatOuterR: Double,
        moatCount: Int,
        intermediateCount: Int,
        starterCount: Int,
        starterMaxRadius: Double,
        seed: Int,
    ): DoubleArray {
        val total = moatCount + intermediateCount + starterCount
        val out = DoubleArray(total * 3)
        var written = 0
        var attempt = 0
        var s = seed

        fun sampleRing(rMin: Double, rMax: Double, n: Int) {
            var added = 0
            while (added < n && attempt < total * 64 + 64) {
                s = s * 0x9E3779B1.toInt() + attempt + 1
                val theta = wohlonHash01(s, attempt, 11) * 2.0 * Math.PI
                val r = rMin + (rMax - rMin) * wohlonHash01(s, attempt, 13)
                val y = seaLow + (seaHigh - seaLow) * wohlonHash01(s, attempt, 17)
                out[written * 3 + 0] = cx + Math.cos(theta) * r
                out[written * 3 + 1] = y
                out[written * 3 + 2] = cz + Math.sin(theta) * r
                written++; added++
                attempt++
            }
        }

        sampleRing(moatInnerR, moatOuterR, moatCount)
        sampleRing(startRing, moatInnerR, intermediateCount)
        sampleRing(0.5, starterMaxRadius, starterCount)
        return out
    }

    /**
     * Run space colonisation from [originX,Y,Z] on the [attractors]
     * cloud. Same loop as [WogorTreeSkeleton.build]; the only behaviour
     * differences from the tree are:
     *
     *  - Grown node Y is clamped to `[seaLow, seaHigh]` after every
     *    step, so the tunnel can never escape the around-sea-level
     *    band even when an attractor would pull it past.
     *  - No buttress flare, no gravity sag — neither applies to
     *    underground tunnels.
     *  - Radius clamp is `[rMin, rMax]` with a thicknessScale tuned so
     *    the trunk (highest descendant count) saturates near `rMax`
     *    and tips (descendant count = 1) sit near `rMin`.
     */
    fun build(
        originX: Double, originY: Double, originZ: Double,
        attractors: DoubleArray,
        biasX: Double, biasY: Double, biasZ: Double,
        attractionDist: Double,
        killDist: Double,
        stepSize: Double,
        maxIterations: Int,
        rMin: Double, rMax: Double,
        thicknessScale: Double,
        seaLow: Double, seaHigh: Double,
        shape: Shape,
    ): Skeleton {
        val root = Node(originX, originY, originZ, parent = null)
        val nodes = ArrayList<Node>(attractors.size / 3 + 8)
        nodes.add(root)

        val numAttractors = attractors.size / 3
        val active = BooleanArray(numAttractors) { true }
        var activeCount = numAttractors
        val attractionDistSq = attractionDist * attractionDist
        val killDistSq = killDist * killDist

        val influence = HashMap<Node, DoubleArray>()  // node → [Σdx, Σdy, Σdz, count]

        @Suppress("UnusedPrivateProperty") // A bounded loop that breaks early; detekt reads the counter as a property.
        for (iter in 0 until maxIterations) {
            if (activeCount == 0) break
            influence.clear()
            var anyKilled = false

            attractorLoop@ for (a in 0 until numAttractors) {
                if (!active[a]) continue
                val ax = attractors[a * 3 + 0]
                val ay = attractors[a * 3 + 1]
                val az = attractors[a * 3 + 2]
                var nearest: Node? = null
                var nearestDsq = attractionDistSq
                for (node in nodes) {
                    val dx = ax - node.x
                    val dy = ay - node.y
                    val dz = az - node.z
                    val dsq = dx * dx + dy * dy + dz * dz
                    if (dsq < killDistSq) {
                        active[a] = false
                        activeCount--
                        anyKilled = true
                        continue@attractorLoop
                    }
                    if (dsq < nearestDsq) {
                        nearestDsq = dsq
                        nearest = node
                    }
                }
                if (nearest != null) {
                    val dx = ax - nearest.x
                    val dy = ay - nearest.y
                    val dz = az - nearest.z
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    if (d > 0.0) {
                        val acc = influence.getOrPut(nearest) { DoubleArray(4) }
                        acc[0] += dx / d
                        acc[1] += dy / d
                        acc[2] += dz / d
                        acc[3] += 1.0
                    }
                }
            }

            if (influence.isEmpty()) {
                // Bias-only growth — extend the most recent tip in the
                // bias direction until an attractor latches on. With a
                // starter cluster of attractors near the origin this
                // path almost never runs in practice.
                val tip = nodes.lastOrNull { it.children.isEmpty() } ?: break
                val mag = sqrt(biasX * biasX + biasY * biasY + biasZ * biasZ)
                if (mag <= 0.0) break
                val nx = tip.x + biasX / mag * stepSize
                val ny = (tip.y + biasY / mag * stepSize).coerceIn(seaLow, seaHigh)
                val nz = tip.z + biasZ / mag * stepSize
                val child = Node(nx, ny, nz, parent = tip)
                tip.children.add(child)
                nodes.add(child)
                continue
            }

            for ((node, acc) in influence) {
                var dx = acc[0] + biasX
                var dy = acc[1] + biasY
                var dz = acc[2] + biasZ
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                if (d == 0.0) continue
                dx /= d; dy /= d; dz /= d
                val nx = node.x + dx * stepSize
                val ny = (node.y + dy * stepSize).coerceIn(seaLow, seaHigh)
                val nz = node.z + dz * stepSize
                val child = Node(nx, ny, nz, parent = node)
                node.children.add(child)
                nodes.add(child)
            }

            if (!anyKilled && influence.isEmpty()) break
        }

        countLeavesRecursive(root)
        smooth(nodes)
        assignPathDistance(root)
        followTerrain(root, shape)
        for (n in nodes) {
            // Area-preserving branching (r² ∝ leaves) with the rapid taper real lateral roots show
            // in their first 10–20 m, then a slow one.
            val taper = (TAPER_FLOOR + (1.0 - TAPER_FLOOR) * Math.exp(-n.pathDist / TAPER_NEAR_LENGTH)) *
                (1.0 - TAPER_FAR_PER_BLOCK * n.pathDist).coerceAtLeast(TAPER_FAR_FLOOR)
            n.radius = (sqrt(n.descendantLeaves.toDouble()) * thicknessScale * taper).coerceIn(rMin, rMax)
        }

        val segments = ArrayList<HeartTunnelSegment>(nodes.size * 2)
        var maxReachSq = 0.0
        for (n in nodes) {
            val ddx = n.x - originX
            val ddz = n.z - originZ
            maxReachSq = maxOf(maxReachSq, ddx * ddx + ddz * ddz)
            for (c in n.children) addSinuous(segments, n, c, shape.seed)
            if (n.children.isEmpty() && n.parent != null) addEnd(segments, n, shape)
        }
        addGrafts(segments, nodes, shape.seed)
        return Skeleton(segments, sqrt(maxReachSq).toInt())
    }

    /** Terrain and moat inputs for shaping, from the chunk generator. */
    class Shape(
        val surfaceAt: (Int, Int) -> Int,
        val moatInnerR: Double,
        val minY: Double,
        val maxY: Double,
        val seed: Int,
    )

    /** Laplacian smoothing of chain nodes only, so branch points and tips stay where they grew. */
    private fun smooth(nodes: List<Node>) {
        repeat(SMOOTH_PASSES) {
            for (n in nodes) {
                val p = n.parent ?: continue
                if (n.children.size != 1) continue
                val c = n.children[0]
                n.x = 0.5 * n.x + 0.25 * (p.x + c.x)
                n.z = 0.5 * n.z + 0.25 * (p.z + c.z)
            }
        }
    }

    private fun assignPathDistance(root: Node) {
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            for (c in n.children) {
                c.pathDist = n.pathDist + sqrt((c.x - n.x) * (c.x - n.x) + (c.z - n.z) * (c.z - n.z))
                stack.addLast(c)
            }
        }
    }

    /**
     * Plate roots run a few blocks under the ground and rise toward their tips, the furthest
     * roots being the shallowest. Parents are visited first so each step eases toward its target.
     */
    private fun followTerrain(root: Node, shape: Shape) {
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val p = n.parent
            if (p != null) {
                val depth = DEPTH_FAR + (DEPTH_NEAR - DEPTH_FAR) * Math.exp(-n.pathDist / DEPTH_FALLOFF)
                val target = (shape.surfaceAt(Math.floor(n.x).toInt(), Math.floor(n.z).toInt()) - depth)
                    .coerceIn(shape.minY, shape.maxY)
                val run = sqrt((n.x - p.x) * (n.x - p.x) + (n.z - p.z) * (n.z - p.z))
                val step = ((target - p.y) * TERRAIN_EASE).coerceIn(-run * MAX_SLOPE, run * MAX_SLOPE)
                n.y = p.y + step
            }
            for (c in n.children) stack.addLast(c)
        }
    }

    /** Parent→child as two segments with a sideways-displaced midpoint, so long runs curve. */
    private fun addSinuous(out: MutableList<HeartTunnelSegment>, a: Node, b: Node, seed: Int) {
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = sqrt(dx * dx + dz * dz)
        if (len < 1e-6) {
            out += HeartTunnelSegment(a.x, a.y, a.z, b.x, b.y, b.z, a.radius, b.radius)
            return
        }
        val offset = (wohlonHash01(seed, Math.floor(a.x * 7.0).toInt(), Math.floor(b.z * 7.0).toInt()) - 0.5) *
            2.0 * SINUOSITY * (len / SINUOSITY_REFERENCE_LENGTH).coerceAtMost(1.0)
        val mx = (a.x + b.x) * 0.5 - dz / len * offset
        val mz = (a.z + b.z) * 0.5 + dx / len * offset
        val my = (a.y + b.y) * 0.5
        val mr = (a.radius + b.radius) * 0.5
        out += HeartTunnelSegment(a.x, a.y, a.z, mx, my, mz, a.radius, mr)
        out += HeartTunnelSegment(mx, my, mz, b.x, b.y, b.z, mr, b.radius)
    }

    /**
     * How a root tip ends. Near the moat it breaks out of the bank as a pipe outlet; elsewhere it
     * either collapses to the surface as a sinkhole (light and a way in) or pinches out in silt.
     * Never a dead-end chamber.
     */
    private fun addEnd(out: MutableList<HeartTunnelSegment>, n: Node, shape: Shape) {
        val p = n.parent ?: return
        var dx = n.x - p.x
        var dz = n.z - p.z
        val len = sqrt(dx * dx + dz * dz)
        if (len > 1e-6) { dx /= len; dz /= len } else { dx = 1.0; dz = 0.0 }
        val radial = sqrt(n.x * n.x + n.z * n.z)
        if (radial >= shape.moatInnerR - OUTLET_REACH) {
            out += HeartTunnelSegment(n.x, n.y, n.z, n.x + dx * OUTLET_LENGTH, n.y, n.z + dz * OUTLET_LENGTH,
                n.radius, n.radius * OUTLET_FLARE)
            return
        }
        val surface = shape.surfaceAt(Math.floor(n.x).toInt(), Math.floor(n.z).toInt())
        if (wohlonHash01(shape.seed, Math.floor(n.x).toInt(), Math.floor(n.z).toInt()) < SINKHOLE_SHARE && surface > n.y + n.radius) {
            out += HeartTunnelSegment(n.x, n.y, n.z, n.x + dx * SINKHOLE_DRIFT, surface + SINKHOLE_OVERSHOOT, n.z + dz * SINKHOLE_DRIFT,
                n.radius, n.radius + SINKHOLE_FLARE)
            return
        }
        out += HeartTunnelSegment(n.x, n.y, n.z, n.x + dx * PINCH_LENGTH, n.y, n.z + dz * PINCH_LENGTH,
            n.radius, PINCH_END_RADIUS)
    }

    /** Root grafts: two branches that pass close fuse, closing loops in the network. */
    private fun addGrafts(out: MutableList<HeartTunnelSegment>, nodes: List<Node>, seed: Int) {
        var grafts = 0
        val graftSq = GRAFT_DISTANCE * GRAFT_DISTANCE
        for (i in nodes.indices) {
            val a = nodes[i]
            if (a.radius < GRAFT_MIN_RADIUS) continue
            for (j in i + 1 until nodes.size) {
                val b = nodes[j]
                if (b.radius < GRAFT_MIN_RADIUS) continue
                val dx = a.x - b.x
                val dy = a.y - b.y
                val dz = a.z - b.z
                if (dx * dx + dy * dy + dz * dz > graftSq) continue
                if (treeDistance(a, b) < GRAFT_MIN_TREE_DISTANCE) continue
                if (wohlonHash01(seed, i, j) >= GRAFT_CHANCE) continue
                val r = minOf(a.radius, b.radius) * GRAFT_RADIUS_SHARE
                out += HeartTunnelSegment(a.x, a.y, a.z, b.x, b.y, b.z, r, r)
                if (++grafts >= GRAFT_MAX) return
            }
        }
    }

    /** Distance along the tree between two nodes, through their lowest common ancestor. */
    private fun treeDistance(a: Node, b: Node): Double {
        val ancestors = HashSet<Node>()
        var cur: Node? = a
        while (cur != null) { ancestors += cur; cur = cur.parent }
        cur = b
        while (cur != null && cur !in ancestors) cur = cur.parent
        val lca = cur ?: return Double.MAX_VALUE
        return a.pathDist + b.pathDist - 2.0 * lca.pathDist
    }

    class Skeleton(
        val segments: List<HeartTunnelSegment>,
        val maxXZReach: Int,
    )

    private const val SMOOTH_PASSES = 3
    /** Radius keeps this share far from the trunk; the rest is lost in the first ~[TAPER_NEAR_LENGTH]. */
    private const val TAPER_FLOOR = 0.55
    private const val TAPER_NEAR_LENGTH = 12.0
    /** About 4 % per 10 blocks after the near taper. */
    private const val TAPER_FAR_PER_BLOCK = 0.004
    private const val TAPER_FAR_FLOOR = 0.6
    private const val DEPTH_NEAR = 9.0
    private const val DEPTH_FAR = 4.0
    private const val DEPTH_FALLOFF = 60.0
    private const val TERRAIN_EASE = 0.6
    /** Rise per horizontal block, so the tunnel floor stays walkable. */
    private const val MAX_SLOPE = 0.5
    private const val SINUOSITY = 1.6
    private const val SINUOSITY_REFERENCE_LENGTH = 8.0
    private const val OUTLET_REACH = 10.0
    const val OUTLET_LENGTH = 10.0
    private const val OUTLET_FLARE = 1.1
    private const val SINKHOLE_SHARE = 0.35
    private const val SINKHOLE_DRIFT = 2.0
    private const val SINKHOLE_OVERSHOOT = 2.0
    private const val SINKHOLE_FLARE = 1.5
    private const val PINCH_LENGTH = 6.0
    private const val PINCH_END_RADIUS = 0.6
    private const val GRAFT_DISTANCE = 6.0
    private const val GRAFT_MIN_RADIUS = 2.0
    private const val GRAFT_MIN_TREE_DISTANCE = 40.0
    private const val GRAFT_CHANCE = 0.5
    private const val GRAFT_RADIUS_SHARE = 0.8
    private const val GRAFT_MAX = 6

    private fun countLeavesRecursive(n: Node): Int {
        if (n.children.isEmpty()) {
            n.descendantLeaves = 1
            return 1
        }
        var sum = 0
        for (c in n.children) sum += countLeavesRecursive(c)
        n.descendantLeaves = sum
        return sum
    }
}

/** A single parent→child segment with tapered radius — fed straight
 *  to `paintHeartTunnelSegment` (chunk-generator). */
internal data class HeartTunnelSegment(
    val startX: Double, val startY: Double, val startZ: Double,
    val endX: Double, val endY: Double, val endZ: Double,
    val startRadius: Double,
    val endRadius: Double,
)

/** Internal node used during growth — mutable to support the
 *  pipe-model radius post-pass. */
private class Node(
    var x: Double, var y: Double, var z: Double,
    val parent: Node?,
) {
    val children: MutableList<Node> = ArrayList(2)
    var radius: Double = 1.0
    var descendantLeaves: Int = 0
    var pathDist: Double = 0.0
}
