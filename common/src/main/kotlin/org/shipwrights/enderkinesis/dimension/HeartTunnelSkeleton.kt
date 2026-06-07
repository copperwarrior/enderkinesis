package org.shipwrights.enderkinesis.dimension

import kotlin.math.sqrt

/**
 * Heart-tunnel skeleton builder — **space colonization** algorithm
 * (Runions, Lane, Prusinkiewicz 2007), the same body of code the Mother
 * Tree uses ([WogorTreeSkeleton]) but tuned for an underground tunnel
 * network that grows from the heart cave at world origin out to the
 * moat ring, branches naturally where attractor clusters pull tips in
 * diverging directions, and dies out at tips where the chunks of
 * attractor-space deplete.
 *
 * Identical mechanics to the tree:
 *
 *  1. Scatter attractor points in the volume the tunnel network should
 *     cover — for us, a dense ring around the moat plus a sparse
 *     intermediate scattering through the mound interior. All
 *     attractors are constrained to a horizontal band `[seaLow, seaHigh]`
 *     so the resulting tunnel stays within ±[yRange] of sea level.
 *  2. Plant one node at the heart `(0, SEA_LEVEL_Y, 0)`.
 *  3. Each iteration:
 *      - every attractor finds its nearest node within [attractionDist];
 *      - every node accumulates the unit directions to its attributed
 *        attractors, blends in [bias], grows one [stepSize] new child
 *        in that averaged direction (Y clamped to the band);
 *      - any attractor within [killDist] of any node is consumed.
 *  4. After growth, walk the tree to count descendant leaves per node
 *     and assign radius ≈ √(descendantLeaves) (Cepero's pipe model,
 *     after da Vinci), clamped to `[rMin, rMax]`. **The trunk —
 *     covering the most descendants — comes out thickest naturally;
 *     leaf-tips come out thinnest.**
 *
 * The output is a flat list of parent→child [HeartTunnelSegment]s plus
 * the list of leaf [HeartTunnelTip]s (where the chunk generator will
 * place craggy dead-end pool chambers). Voxelisation lives in the
 * chunk generator's `paintHeartTunnelSegment`, which mirrors
 * `paintTreeSegment` voxel-for-voxel — same tapered-capsule SDF, same
 * bark perturbation — but writes air / water for the interior and a
 * thin wogor-wood shell band for the wall, instead of solid wood.
 *
 * Pure of caller state — same `seed` + same attractor cloud →
 * identical skeleton. Safe to call from worldgen worker threads
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
                val theta = hash01(s, attempt, 11) * 2.0 * Math.PI
                val r = rMin + (rMax - rMin) * hash01(s, attempt, 13)
                val y = seaLow + (seaHigh - seaLow) * hash01(s, attempt, 17)
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

        // Pipe-model thickness: radius ≈ √(descendantLeaves) ·
        // thicknessScale, clamped. Trunk (many descendants) saturates
        // toward rMax; tips (1 descendant) sit at rMin.
        countLeavesRecursive(root)
        for (n in nodes) {
            val raw = sqrt(n.descendantLeaves.toDouble())
            n.radius = (raw * thicknessScale).coerceIn(rMin, rMax)
        }

        // Emit segments + tips. A tip's forward direction is the
        // unit vector from its parent — used by the pool painter to
        // extend the chamber a little further past the dead-end.
        val segments = ArrayList<HeartTunnelSegment>(nodes.size)
        val tips = ArrayList<HeartTunnelTip>()
        var maxReachSq = 0.0
        for (n in nodes) {
            val ddx = n.x - originX
            val ddz = n.z - originZ
            val rSq = ddx * ddx + ddz * ddz
            if (rSq > maxReachSq) maxReachSq = rSq
            if (n.children.isEmpty() && n.parent != null) {
                val p = n.parent
                val tdx = n.x - p.x
                val tdy = n.y - p.y
                val tdz = n.z - p.z
                val tmag = sqrt(tdx * tdx + tdy * tdy + tdz * tdz)
                val (dxN, dyN, dzN) = if (tmag > 1e-6) {
                    Triple(tdx / tmag, tdy / tmag, tdz / tmag)
                } else {
                    Triple(1.0, 0.0, 0.0)
                }
                tips.add(HeartTunnelTip(n.x, n.y, n.z, n.radius, dxN, dyN, dzN))
            }
            for (c in n.children) {
                segments.add(HeartTunnelSegment(
                    n.x, n.y, n.z, c.x, c.y, c.z,
                    n.radius, c.radius,
                ))
            }
        }
        return Skeleton(segments, tips, sqrt(maxReachSq).toInt())
    }

    /** Result bundle — segments + tips + outermost XZ reach. */
    class Skeleton(
        val segments: List<HeartTunnelSegment>,
        val tips: List<HeartTunnelTip>,
        val maxXZReach: Int,
    )

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

    /** xxhash32-style mix → [0, 1). Same bit-mixer the chunk
     *  generator uses (lifted from [WogorTreeSkeleton]). */
    private fun hash01(seed: Int, k1: Int, k2: Int): Double {
        var h = seed * 0x9E3779B1.toInt() xor
            (k1 * 0x85EBCA77.toInt()) xor
            (k2 * 0xC2B2AE3D.toInt())
        h = (h xor (h ushr 15)) * 0x2C1B3C6D.toInt()
        h = (h xor (h ushr 12)) * 0x297A2D39.toInt()
        h = h xor (h ushr 15)
        return (h and 0x7FFFFFFF) / 2147483648.0
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

/** A leaf-tip — where the chunk generator places a craggy dead-end
 *  pool chamber. The forward direction is the unit vector from the
 *  tip's parent to the tip itself, so the pool can extend past the
 *  tip along that heading instead of just plopping a sphere at the
 *  exact dead-end. */
internal data class HeartTunnelTip(
    val x: Double, val y: Double, val z: Double,
    val radius: Double,
    val dirX: Double, val dirY: Double, val dirZ: Double,
)

/** Internal node used during growth — mutable to support the
 *  pipe-model radius post-pass. */
private class Node(
    val x: Double, val y: Double, val z: Double,
    val parent: Node?,
) {
    val children: MutableList<Node> = ArrayList(2)
    var radius: Double = 1.0
    var descendantLeaves: Int = 0
}
