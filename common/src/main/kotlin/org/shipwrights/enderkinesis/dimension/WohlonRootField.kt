package org.shipwrights.enderkinesis.dimension

/**
 * The root cave field — a dimension-wide tunnel network generated as the
 * **edge skeleton of a 3D Voronoi diagram**, carved beneath Wohlon's land
 * biomes.
 *
 * ## Why F3 − F1
 *
 * In 2D `F2 − F1` finds Voronoi edges. In 3D it finds Voronoi *faces* —
 * 2D sheets — and carving on it yields a honeycomb foam, not roots. The
 * edge locus in 3D is where the three nearest sites are equidistant:
 *
 * | location | distances | F2−F1 | F3−F1 |
 * |---|---|---|---|
 * | cell interior | `a < b < c` | large | large |
 * | face (2 cells) | `a ≈ b < c` | ≈0 | >0 |
 * | **edge (3 cells)** | `a ≈ b ≈ c` | ≈0 | **≈0** |
 * | vertex (4 cells) | `a ≈ b ≈ c ≈ d` | ≈0 | ≈0 |
 *
 * so the carve is `(F3 − F1) < r`. The result is a connected 1D curve
 * network with degree-3 Y-junctions along edges and degree-4 nodes at
 * vertices — topologically a root mat, and connected by construction,
 * which is what makes the analytic entity traversal in [WohlonRootGraph]
 * possible at all.
 *
 * ## Anisotropy
 *
 * Only the **lattice** is anisotropic; the metric stays world-Euclidean.
 * Sites sit on cells of `(cell, cell·k, cell)`, so a squashed coarse
 * lattice (`k < 1`) packs sites vertically and the edges between them run
 * predominantly horizontal — broad runners. The fine lattice is stretched
 * (`k > 1`) and its edges plunge — feeders. Scaling the *distance* instead
 * would flatten the tubes themselves into unswimmable slots; keeping the
 * metric isotropic shapes the network's topology while leaving every tube
 * round.
 *
 * ## Two octaves, separated by role
 *
 *  - **Coarse** — the entity's rail network. Uniform generous radius,
 *    floored at [COARSE_MIN_RADIUS] so it is traversable everywhere. The
 *    heavy tail is deliberately kept off this octave: with
 *    `r = r_min·(1−u)^(−α)` at α≈0.4, only ~6 % of edges would reach 3×
 *    r_min, far under the 25–40 % bond-percolation threshold for a
 *    degree-4 3D network — the traversable subgraph would shatter into
 *    pockets and the entity would spawn caged.
 *  - **Fine** — visual variety only, heavy-tailed, ignored by the entity.
 *
 * ## Deviations from the handoff spec, forced by this dimension
 *
 *  - **Y budget is 54 blocks, not ~130.** Wohlon is `min_y 0, height 320`
 *    with sea level 64, so `seaLevel−10 → bedrock` is y1–54. The coarse
 *    lattice therefore runs `k = 0.40` (vertical cell 16) to fit ~3.4
 *    cells in the budget rather than degenerating to a planar sheet.
 *  - **Ceiling follows the surface.** Where a pool floor dips below
 *    [CAVE_TOP_Y], the roof drops to keep [ROOF_CLEARANCE] of mud between
 *    the tunnels and open water.
 *
 * ## Threading
 *
 * Pure functions of world position plus a per-chunk [Sites] snapshot. No
 * shared mutable state, so C2ME may evaluate chunks concurrently.
 */
object WohlonRootField {

    // ---- vertical budget ----
    /** Ceiling: sea level − 10. */
    const val CAVE_TOP_Y = WohlonnogondoniaChunkGenerator.SEA_LEVEL_Y - 10
    /** Floor: one above the bedrock layer. Follows the world floor, so
     *  the whole 32-block deepening goes to the cave band and the ceiling
     *  at [CAVE_TOP_Y] never moves. */
    const val CAVE_BOTTOM_Y = WohlonnogondoniaChunkGenerator.MIN_Y + 1
    /** Mud kept between a tunnel ceiling and the surface above it, so a
     *  tunnel never surfaces in a pool floor. */
    private const val ROOF_CLEARANCE = 6

    /** Blocks over which the radius tapers to nothing at each limit.
     *  Hard-clamping instead would slice cells and leave a visible sheet
     *  of horizontal runs along the ceiling and floor. */
    private const val TAPER = 9.0

    // ---- coarse octave: the rail network ----
    const val COARSE_CELL = 40.0
    /** `k < 1` — vertical cell 16 blocks, so ~3.4 cells span the budget. */
    const val COARSE_K = 0.40
    /** Uniform, generous. Must clear `halfWidth·√2 + 1` for the entity's
     *  AABB on diagonal runs — 3.2 covers a 1-block-wide marcher with
     *  room to spare. */
    private const val COARSE_RADIUS = 3.4
    /** Floor under mask modulation. Never below ~1.2 or the passage
     *  pinches shut in voxel space and connectivity is lost. */
    private const val COARSE_MIN_RADIUS = 2.4

    // ---- fine octave: visual variety ----
    private const val FINE_CELL = 28.0
    /** `k > 1` — tall cells, so these edges plunge. */
    private const val FINE_K = 1.6
    private const val FINE_MIN_RADIUS = 0.7
    /** Heavy-tail exponent for `r = r_min·(1−u)^(−α)`. */
    private const val FINE_ALPHA = 0.4
    private const val FINE_MAX_RADIUS = 2.6

    /** Conservative upper bound across both octaves, the flare and the
     *  displacement. The F2−F1 early-out must reject against this or it
     *  clips real geometry. */
    private const val R_MAX = 5.0

    // ---- domain warp ----
    /** Amplitude ≈ 0.3 coarse cells. */
    const val WARP_AMP = 12.0
    /** Frequency chosen so `|∇w| < 1` holds with margin: value noise
     *  slope ≤ ~3 per lattice unit, so `|∇w| ≲ 3·A·f = 0.36`. Required
     *  both for warp inversion ([WohlonRootGraph]) and to stop the warp
     *  folding geometry onto itself — not a style knob. */
    const val WARP_FREQ = 0.010

    // ---- density mask ----
    private const val MASK_FREQ = 0.0035
    /** Mask never closes a coarse edge (§7): it only slides the coarse
     *  radius between [COARSE_MIN_RADIUS] and [COARSE_RADIUS], while
     *  modulating fine density freely. Masked-out regions become tight
     *  veins, never solid stone — topological integrity by construction
     *  rather than by tuning mask statistics and hoping. */
    private const val MASK_FINE_CUTOFF = 0.15

    /** Softening constant for the soft-sort, and the dominant smoothness
     *  knob in this field. At the edge locus all three distances are equal,
     *  so a hard sort swaps orderings constantly there and creases every
     *  surface. Measured over four sample regions, raising it 0.55 -> 1.2
     *  cuts single-voxel nubs and pits from ~1.0 % of tunnel volume to
     *  ~0.3 %; past ~1.3 it stops helping and only narrows the tubes, which
     *  is what [COARSE_RADIUS] is bumped to offset. */
    private const val SOFT_K = 1.2

    /** Extra radius at a Voronoi vertex, where four coarse tunnels meet.
     *  Without it the four tubes intersect in a sharp X; with it they share
     *  a rounded node. Small on purpose — 0.6 already takes the p99 normal
     *  jump at junctions from 46° to 13°, and nothing measured above 20°. */
    private const val JOINT_FILLET = 0.6

    /** Distance in `f4 − f1` over which [JOINT_FILLET] fades in. */
    private const val JOINT_RANGE = 6.0

    /** Blend width where a fine feeder meets a coarse runner. Wide enough
     *  that the join reads as a fillet rather than a crease. */
    private const val OCTAVE_UNION_K = 1.9

    /** Shell of root block lining each tube, in blocks past the carve. */
    const val ROOT_SHELL = 1.35

    /** One octave's sites for a chunk, **binned by lattice Y-layer**.
     *
     *  Hoisting alone was not enough once the cave band deepened: scanning
     *  every gathered site for every block makes cost grow with BOTH band
     *  height and site count, i.e. quadratically in world depth. Binning
     *  by layer and walking outward from the query's own layer keeps the
     *  per-block cost flat, so depth costs only the blocks it adds.
     *
     *  The walk is **exactly** equivalent to a full scan, not an
     *  approximation: layers are visited nearest-first and the loop stops
     *  only once a layer's minimum possible vertical distance already
     *  exceeds the current third-nearest distance, at which point no
     *  unvisited layer can contain a closer site. */
    class Octave(
        @JvmField val layers: Array<DoubleArray>,
        @JvmField val layerMinY: DoubleArray,
        @JvmField val layerMaxY: DoubleArray,
        @JvmField val j0: Int,
        @JvmField val cellY: Double,
    )

    class Sites(
        @JvmField val coarse: Octave,
        @JvmField val fine: Octave,
    )

    /** Gather every site that could influence the cave band in this chunk. */
    fun sitesForChunk(chunkX0: Int, chunkZ0: Int): Sites = Sites(
        gather(chunkX0, chunkZ0, COARSE_CELL, COARSE_K, COARSE_SALT),
        gather(chunkX0, chunkZ0, FINE_CELL, FINE_K, FINE_SALT),
    )

    private fun gather(chunkX0: Int, chunkZ0: Int, cell: Double, k: Double, salt: Int): Octave {
        val cellY = cell * k
        // One cell of margin past the chunk + the warp amplitude, so a
        // warped sample never reaches for a site we didn't gather.
        val pad = cell + WARP_AMP
        val i0 = Math.floorDiv((chunkX0 - pad).toInt(), cell.toInt())
        val i1 = Math.floorDiv((chunkX0 + 15 + pad).toInt(), cell.toInt())
        val k0 = Math.floorDiv((chunkZ0 - pad).toInt(), cell.toInt())
        val k1 = Math.floorDiv((chunkZ0 + 15 + pad).toInt(), cell.toInt())
        val j0 = Math.floor((CAVE_BOTTOM_Y - pad) / cellY).toInt()
        val j1 = Math.floor((CAVE_TOP_Y + pad) / cellY).toInt()

        val perLayer = (i1 - i0 + 1) * (k1 - k0 + 1)
        val layerCount = j1 - j0 + 1
        val layers = Array(layerCount) { DoubleArray(perLayer * 3) }
        val minY = DoubleArray(layerCount) { Double.MAX_VALUE }
        val maxY = DoubleArray(layerCount) { -Double.MAX_VALUE }
        for (jIdx in 0 until layerCount) {
            val j = j0 + jIdx
            val arr = layers[jIdx]
            var n = 0
            for (i in i0..i1) for (l in k0..k1) {
                // Jitter hard toward Poisson — a lightly-jittered lattice
                // shows regular junction spacing that players read quickly.
                val sy = (j + jitter(i, j, l, salt + 1)) * cellY
                arr[n * 3] = (i + jitter(i, j, l, salt)) * cell
                arr[n * 3 + 1] = sy
                arr[n * 3 + 2] = (l + jitter(i, j, l, salt + 2)) * cell
                if (sy < minY[jIdx]) minY[jIdx] = sy
                if (sy > maxY[jIdx]) maxY[jIdx] = sy
                n++
            }
        }
        return Octave(layers, minY, maxY, j0, cellY)
    }

    /** Cave ceiling for the column whose surface is at [surfaceY]. */
    fun ceilingFor(surfaceY: Int): Int = Math.min(CAVE_TOP_Y, surfaceY - ROOF_CLEARANCE)

    /** Carve/lining classification for one block. */
    const val SOLID = 0
    const val TUNNEL = 1
    const val LINING = 2

    /**
     * Classify `(wx, wy, wz)`. Returns [SOLID], [TUNNEL] (water) or
     * [LINING] (root block shell).
     *
     * @param ceilingY this column's cave ceiling, from [ceilingFor].
     */
    fun classify(sites: Sites, wx: Int, wy: Int, wz: Int, ceilingY: Int): Int {
        if (wy < CAVE_BOTTOM_Y || wy > ceilingY) return SOLID

        // Domain warp — the single biggest win in making straight Voronoi
        // polylines read as roots rather than crystal.
        val px = wx + WARP_AMP * noise3(wx * WARP_FREQ, wy * WARP_FREQ, wz * WARP_FREQ, 11)
        val py = wy + WARP_AMP * noise3(wx * WARP_FREQ + 5.1, wy * WARP_FREQ + 3.7, wz * WARP_FREQ + 9.2, 23)
        val pz = wz + WARP_AMP * noise3(wx * WARP_FREQ + 1.3, wy * WARP_FREQ + 8.9, wz * WARP_FREQ + 4.4, 37)

        val mask = 0.5 + 0.5 * noise3(wx * MASK_FREQ, wy * MASK_FREQ, wz * MASK_FREQ, 71)
        val taper = taperAt(wy, ceilingY)
        if (taper <= 0.0) return SOLID

        // --- coarse octave: uniform radius, mask only slides it between
        //     the floor and the full value, never to zero. ---
        val coarseR = (COARSE_MIN_RADIUS + (COARSE_RADIUS - COARSE_MIN_RADIUS) * mask) * taper
        var best = Double.MAX_VALUE
        val coarseEdge = edgeValue(sites.coarse, px, py, pz, JOINT_FILLET * taper)
        if (coarseEdge < Double.MAX_VALUE) {
            best = coarseEdge - (coarseR + displacement(wx, wy, wz))
        }

        // --- fine octave: heavy-tailed, freely masked out. ---
        if (mask > MASK_FINE_CUTOFF) {
            val fineEdge = edgeValue(sites.fine, px, py, pz, 0.0)
            if (fineEdge < Double.MAX_VALUE) {
                // Heavy tail r = r_min·(1−u)^(−α), stably per-edge via the
                // position hash — most edges thin veins, a few larger.
                val u = 0.5 + 0.5 * noise3(px * 0.018, py * 0.018, pz * 0.018, 91)
                val tail = FINE_MIN_RADIUS * Math.pow((1.0 - u).coerceAtLeast(1e-3), -FINE_ALPHA)
                val fineR = Math.min(tail, FINE_MAX_RADIUS) * taper *
                    ((mask - MASK_FINE_CUTOFF) / (1.0 - MASK_FINE_CUTOFF))
                val fineSdf = fineEdge - (fineR + displacement(wx, wy, wz))
                best = smin(best, fineSdf, OCTAVE_UNION_K)
            }
        }

        if (best <= 0.0) return TUNNEL
        if (best <= ROOT_SHELL) return LINING
        return SOLID
    }

    /**
     * `F3 − F1` with a soft sort over the **four** nearest sites, or
     * [Double.MAX_VALUE] when the cheap `F2 − F1` test rejects this point as
     * cell interior.
     *
     * f4 has to be in the sort. A Voronoi vertex is where four sites are
     * equidistant, so f3 and f4 tie there — and while rank selection is
     * continuous through a tie it is not differentiable, which puts a
     * genuine crease in the level set exactly where four tunnels meet.
     * Softening only f1..f3 leaves that kink untouched. Measured on a
     * quarter-block grid near vertices, the p99 angle between neighbouring
     * surface normals runs 107° with three values and 46° with four.
     *
     * @param fillet swells the tube as `f4 → f1`, i.e. approaching a vertex,
     *        rounding the node the four tubes share. Folded in here rather
     *        than added to the radius by the caller so the site scan is
     *        walked once and nothing is allocated to carry `f4` back out.
     *        Pass 0 to disable.
     */
    private fun edgeValue(
        oct: Octave,
        px: Double,
        py: Double,
        pz: Double,
        fillet: Double,
    ): Double {
        var f1 = Double.MAX_VALUE
        var f2 = Double.MAX_VALUE
        var f3 = Double.MAX_VALUE
        var f4 = Double.MAX_VALUE

        val centre = (Math.floor(py / oct.cellY).toInt() - oct.j0)
            .coerceIn(0, oct.layers.size - 1)
        var spread = 0
        while (true) {
            var visitedAny = false
            // At spread 0 the two walls coincide — visit the centre layer
            // ONCE. Visiting it twice would compare every site to itself
            // and drive f1 and f2 to the same value.
            val idxs = if (spread == 0) {
                intArrayOf(centre)
            } else {
                intArrayOf(centre - spread, centre + spread)
            }
            for (idx in idxs) {
                if (idx < 0 || idx >= oct.layers.size) continue
                // Exact early termination: if this layer's closest possible
                // point is already further than the current fourth-nearest,
                // neither it nor anything beyond it can contribute.
                val gap = when {
                    py < oct.layerMinY[idx] -> oct.layerMinY[idx] - py
                    py > oct.layerMaxY[idx] -> py - oct.layerMaxY[idx]
                    else -> 0.0
                }
                if (gap * gap >= f4) continue
                visitedAny = true
                val arr = oct.layers[idx]
                var i = 0
                while (i < arr.size) {
                    val dx = px - arr[i]
                    val dy = py - arr[i + 1]
                    val dz = pz - arr[i + 2]
                    val d = dx * dx + dy * dy + dz * dz
                    if (d < f4) {
                        if (d < f3) {
                            if (d < f2) {
                                if (d < f1) {
                                    f4 = f3; f3 = f2; f2 = f1; f1 = d
                                } else {
                                    f4 = f3; f3 = f2; f2 = d
                                }
                            } else {
                                f4 = f3; f3 = d
                            }
                        } else f4 = d
                    }
                    i += 3
                }
            }
            // Stop once both walls are past the frontier and we have four.
            if (!visitedAny && f4 != Double.MAX_VALUE) break
            spread++
            if (centre - spread < 0 && centre + spread >= oct.layers.size) break
        }

        if (f4 == Double.MAX_VALUE) return Double.MAX_VALUE
        val a = Math.sqrt(f1)
        val b = Math.sqrt(f2)
        // Conservative reject: F3−F1 ≥ F2−F1, so if even F2−F1 clears the
        // widest possible radius this point is cell interior. Cell
        // interiors dominate space, which is what makes this pay.
        if (b - a > R_MAX) return Double.MAX_VALUE
        val c = Math.sqrt(f3)
        val d = Math.sqrt(f4)

        // Soft sorting network over four values; we need only the 1st and
        // 3rd order statistics out of it.
        val a1 = smin(a, b, SOFT_K)
        val b1 = smax(a, b, SOFT_K)
        val c1 = smin(c, d, SOFT_K)
        val d1 = smax(c, d, SOFT_K)
        val lo = smin(a1, c1, SOFT_K)
        val mid1 = smax(a1, c1, SOFT_K)
        val mid2 = smin(b1, d1, SOFT_K)
        val third = smax(mid1, mid2, SOFT_K)
        val value = third - lo

        if (fillet <= 0.0) return value
        // d − a collapses to 0 at a vertex; smoothstep it so the swell has
        // no edge of its own to crease along.
        val u = ((JOINT_RANGE - (d - a)) / JOINT_RANGE).coerceIn(0.0, 1.0)
        return value - fillet * smoothstep(u)
    }

    /** Axially-stretched surface displacement. The root block carries no
     *  directional property, so ALL of the "this is a root" read lives in
     *  geometry — isotropic noise gives lumpy tubes, noise stretched
     *  along the run gives fibrous ones. Stretching X/Z against Y biases
     *  the striation along the predominantly-horizontal coarse runs. */
    private fun displacement(wx: Int, wy: Int, wz: Int): Double =
        0.22 * noise3(wx * 0.038, wy * 0.11, wz * 0.038, 53) +
            0.06 * noise3(wx * 0.09, wy * 0.24, wz * 0.09, 59)

    /** Radius taper toward both Y limits. */
    private fun taperAt(wy: Int, ceilingY: Int): Double {
        val fromBottom = (wy - CAVE_BOTTOM_Y) / TAPER
        val fromTop = (ceilingY - wy) / TAPER
        return smoothstep(Math.min(fromBottom, fromTop).coerceIn(0.0, 1.0))
    }

    // ---- soft min / max (polynomial; cheap, C¹) ----
    private fun smin(a: Double, b: Double, k: Double): Double {
        val h = (0.5 + 0.5 * (b - a) / k).coerceIn(0.0, 1.0)
        return b + (a - b) * h - k * h * (1.0 - h)
    }

    private fun smax(a: Double, b: Double, k: Double): Double {
        val h = (0.5 - 0.5 * (b - a) / k).coerceIn(0.0, 1.0)
        return b + (a - b) * h + k * h * (1.0 - h)
    }

    private fun smoothstep(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return c * c * (3.0 - 2.0 * c)
    }

    // ---- hashing / noise ----

    internal const val COARSE_SALT = 0x51A1
    internal const val FINE_SALT = 0x77C3


    /** Stable id for a lattice cell. Edges are unordered triples of these
     *  and vertices unordered quads — sorting then hashing gives the
     *  per-edge / per-vertex stable randomness the spec asks for. */
    fun siteId(i: Int, j: Int, l: Int): Long =
        (i.toLong() and 0x1FFFFF shl 42) or (j.toLong() and 0x1FFFFF shl 21) or (l.toLong() and 0x1FFFFF)

    /** World position of the coarse site owning lattice cell `(i, j, l)`.
     *  The graph rides the coarse octave exclusively. */
    fun coarseSite(i: Int, j: Int, l: Int, out: DoubleArray) {
        val cellY = COARSE_CELL * COARSE_K
        out[0] = (i + jitter(i, j, l, COARSE_SALT)) * COARSE_CELL
        out[1] = (j + jitter(i, j, l, COARSE_SALT + 1)) * cellY
        out[2] = (l + jitter(i, j, l, COARSE_SALT + 2)) * COARSE_CELL
    }

    /** Sign-extending inverse of [siteId]. */
    fun unpackI(id: Long): Int = ((id shr 42) and 0x1FFFFF).toInt().let { if (it >= 0x100000) it - 0x200000 else it }
    fun unpackJ(id: Long): Int = ((id shr 21) and 0x1FFFFF).toInt().let { if (it >= 0x100000) it - 0x200000 else it }
    fun unpackL(id: Long): Int = (id and 0x1FFFFF).toInt().let { if (it >= 0x100000) it - 0x200000 else it }

    /** The domain warp applied before lattice evaluation, as a vector. */
    fun warp(x: Double, y: Double, z: Double, out: DoubleArray) {
        out[0] = WARP_AMP * noise3(x * WARP_FREQ, y * WARP_FREQ, z * WARP_FREQ, 11)
        out[1] = WARP_AMP * noise3(x * WARP_FREQ + 5.1, y * WARP_FREQ + 3.7, z * WARP_FREQ + 9.2, 23)
        out[2] = WARP_AMP * noise3(x * WARP_FREQ + 1.3, y * WARP_FREQ + 8.9, z * WARP_FREQ + 4.4, 37)
    }

    /** Site jitter in `[0, 1)`. Full-range so the lattice reads Poisson
     *  rather than as a regular grid with wobble. */
    internal fun jitter(i: Int, j: Int, l: Int, salt: Int): Double =
        (hash3(i, j, l, salt) and 0xFFFFFF) / 16777216.0

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
