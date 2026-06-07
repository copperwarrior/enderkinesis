package org.shipwrights.enderkinesis.dimension

import kotlin.math.sqrt

/**
 * Procedural branching-tree skeleton builder using the **space colonization**
 * algorithm (Runions, Lane, Prusinkiewicz 2007, *Modeling Trees with a Space
 * Colonization Algorithm*).
 *
 * One algorithm grows both the canopy branches (attractor cloud above the
 * trunk, bias direction up) and the roots (attractor cloud below ground, bias
 * direction down). Trunk, branches and roots emerge naturally from the same
 * mechanic: a tip-node-graph that competes for attractor points scattered
 * through the canopy/root volume. Branches form where multiple attractors
 * pull a tip in diverging directions; thickness propagates through the tree
 * by **Leonardo da Vinci's rule** (radius² of a parent equals the sum of
 * radius² of its children).
 *
 * ### Algorithm (one screen)
 *
 *  1. Scatter N attractor points inside a volume (the canopy / root volume).
 *  2. Place one node at the trunk base/top.
 *  3. Repeat for `maxIterations` (or until all attractors are consumed):
 *     - For each remaining attractor, find its nearest node within
 *       [attractionDist]; attribute the attractor to that node.
 *     - For each node with attributed attractors, average the directions to
 *       those attractors, blend in [defaultDir] weighted by [branchBias], and
 *       grow a new child node one [stepSize] along the resulting direction.
 *     - Mark as "consumed" any attractor within [killDist] of any node.
 *     - If no node was influenced, extend the latest tip toward [defaultDir]
 *       (gets the trunk off the ground before the first attractor latches on).
 *  4. Walk the tree to count leaf descendants per node, then assign thickness
 *     ≈ √(descendantLeaves) clamped to [maxThickness].
 *
 * The result is a list of [TreeSegment]s (parent→child links with start/end
 * thickness) plus a list of leaf-tip positions for the foliage placer.
 *
 * Pure of any caller state: same `seed` → same skeleton. Safe to call from
 * worldgen worker threads concurrently.
 */
internal object WogorTreeSkeleton {

    /**
     * Sample [count] points uniformly inside an axis-aligned ellipsoid
     * centred at `(cx, cy, cz)` with radii `(rx, ry, rz)`. Uses rejection
     * sampling against the unit ball — about 52% acceptance rate; for
     * typical counts (50–400) the overhead is negligible.
     *
     * @return packed `[x0,y0,z0, x1,y1,z1, …]` doubles, length `count * 3`.
     */
    fun ellipsoidAttractors(
        cx: Double, cy: Double, cz: Double,
        rx: Double, ry: Double, rz: Double,
        count: Int, seed: Int,
    ): DoubleArray {
        val out = DoubleArray(count * 3)
        var s = seed
        var written = 0
        var attempt = 0
        // Cap attempts so a degenerate request can't loop forever.
        val maxAttempts = count * 16 + 32
        while (written < count && attempt < maxAttempts) {
            s = s * 0x9E3779B1.toInt() + attempt + 1
            val x = (hash01(s, attempt, 1) - 0.5) * 2.0
            val y = (hash01(s, attempt, 2) - 0.5) * 2.0
            val z = (hash01(s, attempt, 3) - 0.5) * 2.0
            attempt++
            if (x * x + y * y + z * z > 1.0) continue
            out[written * 3] = cx + x * rx
            out[written * 3 + 1] = cy + y * ry
            out[written * 3 + 2] = cz + z * rz
            written++
        }
        // If the budget ran out, copy the last written point into any
        // remaining slots — never affects normal usage; this is a safety net.
        if (written < count) {
            val lx = if (written > 0) out[(written - 1) * 3] else cx
            val ly = if (written > 0) out[(written - 1) * 3 + 1] else cy
            val lz = if (written > 0) out[(written - 1) * 3 + 2] else cz
            while (written < count) {
                out[written * 3] = lx
                out[written * 3 + 1] = ly
                out[written * 3 + 2] = lz
                written++
            }
        }
        return out
    }

    /**
     * Build a tree skeleton. See class doc for the algorithm; parameters:
     *
     * @param originX/Y/Z  trunk start point (typically the base for canopy,
     *                     the base for roots).
     * @param attractors   packed `[x0,y0,z0, x1,y1,z1, …]` — the canopy or
     *                     root cloud (see [ellipsoidAttractors]).
     * @param defaultDir   length-3 unit vector. The bias growth direction:
     *                     `(0, +1, 0)` for canopy, `(0, -1, 0)` for roots.
     * @param attractionDist  max distance at which an attractor pulls a node.
     *                        Larger → fewer, longer branches.
     * @param killDist     distance at which a node "consumes" an attractor.
     *                     Must be ≥ [stepSize] or the algorithm overshoots.
     * @param stepSize     length of each grown segment, in world blocks.
     * @param maxIterations safety cap on iterations.
     * @param branchBias   weight given to [defaultDir] when blending with
     *                     attractor directions. 0 = pure attractor flow,
     *                     0.5 = strong upward (or downward) drive.
     * @param maxThickness clamp on the Da Vinci thickness at the trunk.
     * @param thicknessScale multiplier applied to the raw √(descendantLeaves)
     *                     before [maxThickness] clamping. **Use a value < 1 to
     *                     widen the thickness range:** without this, internal
     *                     branches with even modest leaf counts all hit the
     *                     cap and the whole tree paints as trunk-thick.
     *                     0.4 is a good default for big trees.
     * @param buttressFlare add this much thickness to every node within
     *                      [buttressRange] vertical blocks of the trunk
     *                      origin (linear falloff). Gives the bottom of the
     *                      trunk a wider buttressed base; 0 = no flare.
     * @param buttressRange vertical extent of the buttress flare from the
     *                      origin (in blocks). Both axes — flare applies on
     *                      whichever side the canopy or roots grow.
     * @param trunkLean    natural-curve drift applied to the bias-only growth
     *                     direction. The drift is smooth-noise-interpolated
     *                     against iteration count (slow, continuous) so the
     *                     trunk leans and twists organically — NOT
     *                     zigzags. The X and Z components sample noise at
     *                     slightly different frequencies, so the lean
     *                     direction rotates as the trunk rises (giving
     *                     "twist"). Pass 0 for a perfectly straight pole.
     *                     0.3 gives a noticeable but realistic lean.
     * @param gravity      Cepero-style gravity sag, applied as a post-process
     *                     Y shift to every node:
     *                     `sag = distFromAxis · gravity / (1 + √descendantLeaves)`.
     *                     Outer light tips droop most (high distFromAxis,
     *                     low descendant count); heavy interior branches
     *                     stay rigid (high descendant count → 1/√N tiny);
     *                     the trunk (distFromAxis ≈ 0) is unaffected. Pass
     *                     0 for no droop. 0.15 gives a visible canopy sag
     *                     without making the outer leaves dangle below the
     *                     trunk.
     */
    fun build(
        originX: Double, originY: Double, originZ: Double,
        attractors: DoubleArray,
        defaultDir: DoubleArray,
        attractionDist: Double,
        killDist: Double,
        stepSize: Double,
        maxIterations: Int,
        branchBias: Double,
        maxThickness: Int,
        thicknessScale: Double = 1.0,
        buttressFlare: Int = 0,
        buttressRange: Double = 0.0,
        trunkLean: Double = 0.0,
        gravity: Double = 0.0,
    ): TreeSkeleton {
        // Per-origin lean seeds — different trees lean different directions.
        val leanSeedBase = (originX.toInt() * 0x9E3779B1.toInt()) xor
            (originY.toInt() * 0x85EBCA77.toInt()) xor
            (originZ.toInt() * 0xC2B2AE3D.toInt())
        val leanSeedX = leanSeedBase
        val leanSeedZ = leanSeedBase xor 0x12345678.toInt()
        // Different X/Z frequencies → the lean direction rotates as the
        // trunk grows, producing twist instead of straight-line lean.
        val leanFreqX = 0.07
        val leanFreqZ = 0.05
        require(defaultDir.size == 3) { "defaultDir must have length 3" }

        val root = TreeNode(originX, originY, originZ, parent = null)
        root.index = 0
        val nodes = ArrayList<TreeNode>(attractors.size / 3 + 8)
        nodes.add(root)

        val numAttractors = attractors.size / 3
        val active = BooleanArray(numAttractors) { true }
        var activeCount = numAttractors

        val attractionDistSq = attractionDist * attractionDist
        val killDistSq = killDist * killDist
        val biasX = defaultDir[0] * branchBias
        val biasY = defaultDir[1] * branchBias
        val biasZ = defaultDir[2] * branchBias

        // Per-iteration influence accumulators as PARALLEL ARRAYS
        // indexed by [TreeNode.index] instead of an
        // HashMap<TreeNode, DoubleArray>. Pre-sized to a generous
        // upper bound on the final node count so we never need to
        // resize mid-build.
        //
        // **The bound is `numAttractors * 4 + maxIterations + 16`**:
        // the influence-grow loop below adds ONE new node per
        // influenced node per iteration (NOT one per iteration as
        // an earlier version of this comment claimed) — over a
        // run an attractor influences ~2–3 nodes before being
        // killed, so 4×numAttractors is a safe ceiling. The
        // bias-only path adds 1 node per iteration, hence the
        // `+ maxIterations`. The `+ 16` is slack for the initial
        // root and any single-iteration overshoots.
        //
        // The HashMap version paid ~25 ns per influence update for
        // hashing + bucket walk + per-update DoubleArray
        // allocation; the array version pays ~3 ns of index math.
        // `influencedIndices` tracks which indices have been
        // touched this iteration so we can zero only what we wrote
        // at the start of the next iteration (avoids an
        // Arrays.fill over the full upper-bound length each iter).
        val maxNodes = numAttractors * 4 + maxIterations + 16
        val influenceDx = DoubleArray(maxNodes)
        val influenceDy = DoubleArray(maxNodes)
        val influenceDz = DoubleArray(maxNodes)
        val influenceCount = IntArray(maxNodes)
        val influencedIndices = IntArray(maxNodes)
        var influencedSize = 0

        for (iter in 0 until maxIterations) {
            if (activeCount == 0) break

            // Zero only the indices we wrote last iteration.
            for (i in 0 until influencedSize) {
                val idx = influencedIndices[i]
                influenceDx[idx] = 0.0
                influenceDy[idx] = 0.0
                influenceDz[idx] = 0.0
                influenceCount[idx] = 0
            }
            influencedSize = 0
            var anyKilled = false

            // Per-iteration lean perturbation — computed ONCE here and
            // applied to both the bias-only and attractor-influenced
            // growth paths below. Sampling the same iter→noise mapping
            // in both paths means the lean direction varies smoothly
            // across the algorithm's bias→attractor transition, so the
            // trunk has no visible joint where it switches from "growing
            // up toward the canopy cloud" to "branching through it" —
            // it's one continuous S-curve.
            var leanPertX = 0.0
            var leanPertZ = 0.0
            if (trunkLean > 0.0) {
                val nX = (smoothNoise1D(iter * leanFreqX, leanSeedX) - 0.5) * 2.0
                val nZ = (smoothNoise1D(iter * leanFreqZ, leanSeedZ) - 0.5) * 2.0
                leanPertX = nX * trunkLean
                leanPertZ = nZ * trunkLean
            }

            // Per attractor: find nearest node within attractionDist, OR
            // mark consumed if any node is within killDist.
            attractorLoop@ for (a in 0 until numAttractors) {
                if (!active[a]) continue
                val ax = attractors[a * 3]
                val ay = attractors[a * 3 + 1]
                val az = attractors[a * 3 + 2]
                var nearest: TreeNode? = null
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
                    val n = nearest
                    val dx = ax - n.x
                    val dy = ay - n.y
                    val dz = az - n.z
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    if (d > 0.0) {
                        val idx = n.index
                        if (influenceCount[idx] == 0) {
                            // First influence this iter for this node;
                            // track its index so the next iter only
                            // zeroes what it needs to.
                            influencedIndices[influencedSize++] = idx
                        }
                        val invD = 1.0 / d
                        influenceDx[idx] += dx * invD
                        influenceDy[idx] += dy * invD
                        influenceDz[idx] += dz * invD
                        influenceCount[idx]++
                    }
                }
            }

            if (influencedSize == 0) {
                // Bias-only growth: extend the most recent un-tipped node
                // toward `defaultDir` so the trunk lifts off the ground
                // until attractors latch on.
                //
                // **Natural lean & twist** via smooth-noise on (iter): both
                // X and Z components drift continuously, but at slightly
                // different frequencies, so the lean direction rotates as
                // the trunk rises. The combined effect is a tree that
                // curves smoothly, leans in a slow direction, and twists
                // as it grows — instead of zigzagging from random per-step
                // jitter or standing as a stiff pole.
                val tip = nodes.lastOrNull { it.children.isEmpty() } ?: break
                var gdx = defaultDir[0] + leanPertX
                val gdy = defaultDir[1]
                var gdz = defaultDir[2] + leanPertZ
                val mag = sqrt(gdx * gdx + gdy * gdy + gdz * gdz)
                if (mag > 0.0) {
                    gdx /= mag; gdz /= mag
                }
                val gdyN = if (mag > 0.0) gdy / mag else gdy
                val nx = tip.x + gdx * stepSize
                val ny = tip.y + gdyN * stepSize
                val nz = tip.z + gdz * stepSize
                if (nodes.size >= maxNodes) break
                val newNode = TreeNode(nx, ny, nz, parent = tip)
                newNode.index = nodes.size
                tip.children.add(newNode)
                nodes.add(newNode)
                continue
            }

            // Grow new nodes from each influenced node. The same per-iter
            // lean perturbation that drives bias-only growth is layered
            // onto the attractor pull here — so the global "the trunk
            // leans this way at this height" effect carries through the
            // branching phase too, no joint between trunk and canopy.
            for (i in 0 until influencedSize) {
                if (nodes.size >= maxNodes) break
                val idx = influencedIndices[i]
                val node = nodes[idx]
                var dx = influenceDx[idx] + biasX + leanPertX
                var dy = influenceDy[idx] + biasY
                var dz = influenceDz[idx] + biasZ + leanPertZ
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                if (d == 0.0) continue
                val invD = 1.0 / d
                dx *= invD; dy *= invD; dz *= invD
                val nx = node.x + dx * stepSize
                val ny = node.y + dy * stepSize
                val nz = node.z + dz * stepSize
                val newNode = TreeNode(nx, ny, nz, parent = node)
                newNode.index = nodes.size
                node.children.add(newNode)
                nodes.add(newNode)
            }

            // If nothing was killed AND nothing influenced, we're stuck.
            if (!anyKilled && influencedSize == 0) break
        }

        // Pipe-model (Cepero, after da Vinci): radius² accumulates from
        // leaves to trunk; equivalently, radius ≈ √(descendantLeaves)
        // scaled by [thicknessScale] and clamped to [maxThickness]. Kept
        // as a **continuous Double** so the SDF rasterizer can lerp
        // along each segment without flooring the gradient.
        countLeavesRecursive(root)
        for (n in nodes) {
            val raw = sqrt(n.descendantLeaves.toDouble())
            n.radius = (raw * thicknessScale).coerceIn(0.5, maxThickness.toDouble())
        }

        // Buttress flare: continuous radius bonus on nodes within
        // `buttressRange` of the origin Y, linear falloff. Flare can push
        // past `maxThickness` because the buttress is supposed to be
        // visibly wider than the rest of the trunk.
        if (buttressFlare > 0 && buttressRange > 0.0) {
            val flareCap = (maxThickness + buttressFlare).toDouble()
            for (n in nodes) {
                val dy = Math.abs(n.y - originY)
                if (dy >= buttressRange) continue
                val bonus = (1.0 - dy / buttressRange) * buttressFlare
                n.radius = (n.radius + bonus).coerceAtMost(flareCap)
            }
        }

        // Gravity sag (Cepero "space-warp on attraction vectors"). Applied
        // as a post-process Y shift on each node, scaled by the lever-arm
        // (distance from the central trunk axis) and *inverse* of the
        // node's branch weight: outer light tips droop strongly, heavy
        // interior branches stay rigid, the trunk (distFromAxis ≈ 0) is
        // untouched. Segments built next read the shifted positions, so
        // the droop is rendered as a continuous bend through the SDF
        // rasterizer.
        if (gravity > 0.0) {
            for (n in nodes) {
                val dx = n.x - originX
                val dz = n.z - originZ
                val distFromAxis = sqrt(dx * dx + dz * dz)
                val weight = 1.0 + sqrt(n.descendantLeaves.toDouble())
                n.y -= distFromAxis * gravity / weight
            }
        }

        // Build segment + tip lists.
        val segments = ArrayList<TreeSegment>(nodes.size)
        val tips = ArrayList<TreeTip>()
        var maxXZReachSq = 0.0
        for (n in nodes) {
            val ddx = n.x - originX
            val ddz = n.z - originZ
            val rSq = ddx * ddx + ddz * ddz
            if (rSq > maxXZReachSq) maxXZReachSq = rSq
            if (n.children.isEmpty() && n.parent != null) {
                tips.add(TreeTip(n.x.toInt(), n.y.toInt(), n.z.toInt()))
            }
            for (c in n.children) {
                segments.add(TreeSegment(
                    n.x, n.y, n.z, c.x, c.y, c.z,
                    n.radius, c.radius,
                ))
            }
        }
        return TreeSkeleton(segments, tips, sqrt(maxXZReachSq).toInt())
    }

    private fun countLeavesRecursive(n: TreeNode): Int {
        if (n.children.isEmpty()) {
            n.descendantLeaves = 1
            return 1
        }
        var sum = 0
        for (c in n.children) sum += countLeavesRecursive(c)
        n.descendantLeaves = sum
        return sum
    }

    /**
     * 1D smooth value-noise → [0, 1). Linearly-interpolated hash values at
     * integer keys with a smoothstep curve on the interpolation parameter,
     * so the output is continuously varying — the right primitive for
     * macro-scale natural curvature in trunk growth, vs. per-step
     * uncorrelated random jitter which would zigzag.
     */
    private fun smoothNoise1D(t: Double, seed: Int): Double {
        val i = Math.floor(t).toInt()
        val f = t - i
        val a = hash01(seed, i, 0)
        val b = hash01(seed, i + 1, 0)
        val s = f * f * (3.0 - 2.0 * f)   // smoothstep
        return a + (b - a) * s
    }

    /** xxhash32-style mix → [0, 1). Same bit-mixer the chunk generator uses. */
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

/** Internal node used while growing — mutates as the algorithm walks. */
private class TreeNode(
    val x: Double, y: Double, val z: Double,
    val parent: TreeNode?,
) {
    /** Y is `var` so the post-build gravity-sag pass can shift the node
     *  downward in place. X and Z stay immutable. */
    var y: Double = y
    val children: MutableList<TreeNode> = ArrayList(2)
    /** Continuous **radius** in world blocks. Pipe-model derived: at a
     *  branching point, radius² = Σ(children radius²) → so radius is
     *  proportional to √(descendantLeaves). Stored as Double so the
     *  rasterizer can apply the full continuous gradient and bark
     *  perturbation without flooring artifacts. */
    var radius: Double = 1.0
    var descendantLeaves: Int = 0
    /** Position of this node in the build's `nodes` list. Set by
     *  [WogorTreeSkeleton.build] when the node is added. Used as the
     *  index into the parallel influence-accumulator arrays so the
     *  per-iteration influence map doesn't need an
     *  `HashMap<TreeNode, DoubleArray>` (which paid an object
     *  identityHashCode + bucket walk + per-update allocation per
     *  influence update). −1 means "not yet placed in the list". */
    var index: Int = -1
}

/** A single parent→child segment with continuous radius lerping along
 *  its length (Cepero's tapered-capsule SDF). */
internal data class TreeSegment(
    val startX: Double, val startY: Double, val startZ: Double,
    val endX: Double, val endY: Double, val endZ: Double,
    val startRadius: Double,
    val endRadius: Double,
)

/** Position of a terminal branch tip — where the foliage placer hangs a
 *  leaf cluster. */
internal data class TreeTip(val x: Int, val y: Int, val z: Int)

/** Result of the algorithm: every parent→child segment and every tip. */
internal class TreeSkeleton(
    val segments: List<TreeSegment>,
    val tips: List<TreeTip>,
    /** Greatest XZ distance any node sits from the origin. Used by the
     *  chunk generator's per-chunk reject. */
    val maxXZReach: Int,
)
