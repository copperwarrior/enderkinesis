package org.shipwrights.enderkinesis.dimension

/**
 * Analytic traversal of the root cave network's **coarse** skeleton — no
 * navmesh, no pathfinding, no chunk loading.
 *
 * Voronoi is dual to Delaunay, and that duality is the whole trick:
 *
 *  - A Voronoi **edge** is the locus equidistant from 3 sites — a straight
 *    line in lattice space.
 *  - A Voronoi **vertex** is the point equidistant from 4 sites.
 *  - From a vertex `{a,b,c,d}` the four outgoing edges are the four
 *    triples you get by dropping one site. Degree is always 4.
 *
 * So "where does this tunnel go next" is a closed-form solve rather than a
 * search. Walking the edge `{a,b,c}` as `L(t) = c₀ + t·n̂`, the next vertex
 * is the nearest `t` at which some fourth site `d` becomes equidistant:
 *
 * ```
 * |L(t) − a|² = |L(t) − d|²
 *   ⇒ 2·L(t)·(d − a) = |d|² − |a|²
 *   ⇒ t = ( |d|² − |a|² − 2·c₀·(d − a) ) / ( 2·n̂·(d − a) )
 * ```
 *
 * One division per candidate site, exact, no iteration. The consequence is
 * that the entity can march through completely unloaded chunks at
 * near-zero cost — no terrain generation, no chunk tickets. And because a
 * Voronoi edge is the medial axis of its own tube, the path it produces is
 * maximally clear by construction.
 *
 * ## Lattice space vs world space
 *
 * The field is evaluated at `warp(p)`, so an edge solved here lives in
 * warped lattice space and is NOT where the entity should stand. [unwarp]
 * inverts it by fixed-point iteration, which converges only while
 * `|∇w| < 1` — the same constraint that governs the warp amplitude in
 * [WohlonRootField].
 */
object WohlonRootGraph {

    /** A directed position on the graph: the edge's three site ids, plus
     *  how far along its parameter line we are. */
    data class EdgeRef(val a: Long, val b: Long, val c: Long) {
        /** Canonical unordered form, so `{a,b,c}` hashes the same however
         *  it was discovered — this is what makes per-edge randomness and
         *  edge identity stable. */
        fun canonical(): EdgeRef {
            var x = a; var y = b; var z = c
            if (x > y) { val t = x; x = y; y = t }
            if (y > z) { val t = y; y = z; z = t }
            if (x > y) { val t = x; x = y; y = t }
            return EdgeRef(x, y, z)
        }

        fun contains(id: Long): Boolean = a == id || b == id || c == id
    }

    /** Result of a next-vertex solve. */
    class Step(
        @JvmField val d: Long,
        @JvmField val t: Double,
    )

    /** Candidate-site search radius in coarse lattice cells. A vertex is
     *  at most about one cell away along the edge, so ±2 covers it with
     *  margin while keeping the candidate set small. */
    private const val SEARCH_CELLS = 2

    /** A solved vertex further than this along the edge lies outside the
     *  candidate search radius, so its solve isn't trustworthy — a
     *  near-parallel candidate site produces an enormous `t` that would
     *  otherwise fling the marcher across the world. Reject rather than
     *  trust. */
    private val MAX_STEP = WohlonRootField.COARSE_CELL * 2.0

    /** The Voronoi skeleton is unbounded in Y, but only [WohlonRootField]'s
     *  band is carved and the radius tapers to nothing at both limits. The
     *  entity is confined to where the carve is actually wide enough to hold
     *  it — §9's "the traversability test must apply the identical mask used
     *  at carve time". 9 is [WohlonRootField.TAPER], so the marcher stays out
     *  of the pinched ends.
     *
     *  Measure dead-end rate across seeds, never off one walk: single walks
     *  scatter ~0–46 % for the same band. Over 8 seeds the current y-22..45
     *  band averages 0.3 % dead ends, worst case 2.3 %. */
    private const val BAND_MARGIN = 9
    private val TRAVERSABLE_MIN_Y = WohlonRootField.CAVE_BOTTOM_Y + BAND_MARGIN

    /** Slack subtracted from the reconstructed surface to cover the fine
     *  octaves the generator adds on top of
     *  [WohlonnogondoniaChunkGenerator.coarseSurfaceY]
     *  (amplitude 2.0 over a ±1.5 sum). The hill lift only ever raises the
     *  surface, so ignoring it errs toward a lower roof. */
    private const val SURFACE_SLACK = 3.0

    // ------------------------------------------------------------------
    //  sites
    // ------------------------------------------------------------------

    /** World position of a site, into `out`. */
    fun sitePos(id: Long, out: DoubleArray) {
        WohlonRootField.coarseSite(
            WohlonRootField.unpackI(id),
            WohlonRootField.unpackJ(id),
            WohlonRootField.unpackL(id),
            out,
        )
    }

    /** Every candidate site id within [SEARCH_CELLS] of a lattice-space
     *  point. */
    fun nearbySites(px: Double, py: Double, pz: Double): LongArray {
        val cell = WohlonRootField.COARSE_CELL
        val cellY = cell * WohlonRootField.COARSE_K
        val ci = Math.floor(px / cell).toInt()
        val cj = Math.floor(py / cellY).toInt()
        val cl = Math.floor(pz / cell).toInt()
        val span = SEARCH_CELLS * 2 + 1
        val out = LongArray(span * span * span)
        var n = 0
        for (i in ci - SEARCH_CELLS..ci + SEARCH_CELLS) {
            for (j in cj - SEARCH_CELLS..cj + SEARCH_CELLS) {
                for (l in cl - SEARCH_CELLS..cl + SEARCH_CELLS) {
                    out[n++] = WohlonRootField.siteId(i, j, l)
                }
            }
        }
        return out
    }

    /** The three nearest sites to a lattice-space point — the edge whose
     *  tube that point sits in. Used to seed an entity onto the graph. */
    fun edgeAt(px: Double, py: Double, pz: Double): EdgeRef {
        val ids = nearbySites(px, py, pz)
        val s = DoubleArray(3)
        var b1 = Double.MAX_VALUE; var b2 = Double.MAX_VALUE; var b3 = Double.MAX_VALUE
        var i1 = 0L; var i2 = 0L; var i3 = 0L
        for (id in ids) {
            sitePos(id, s)
            val dx = px - s[0]; val dy = py - s[1]; val dz = pz - s[2]
            val d = dx * dx + dy * dy + dz * dz
            when {
                d < b1 -> { b3 = b2; i3 = i2; b2 = b1; i2 = i1; b1 = d; i1 = id }
                d < b2 -> { b3 = b2; i3 = i2; b2 = d; i2 = id }
                d < b3 -> { b3 = d; i3 = id }
            }
        }
        return EdgeRef(i1, i2, i3).canonical()
    }

    // ------------------------------------------------------------------
    //  edge geometry
    // ------------------------------------------------------------------

    /**
     * The parameter line of edge `{a,b,c}`: origin into `origin`, unit
     * direction into `dir`. The line is perpendicular to the plane of the
     * three sites and passes through their circumcentre.
     *
     * Returns false for degenerate (collinear) triples, which the caller
     * must skip — the jittered lattice makes these vanishingly rare but
     * not impossible.
     */
    fun edgeLine(edge: EdgeRef, origin: DoubleArray, dir: DoubleArray): Boolean {
        val A = DoubleArray(3); val B = DoubleArray(3); val C = DoubleArray(3)
        sitePos(edge.a, A); sitePos(edge.b, B); sitePos(edge.c, C)

        val ux = B[0] - A[0]; val uy = B[1] - A[1]; val uz = B[2] - A[2]
        val vx = C[0] - A[0]; val vy = C[1] - A[1]; val vz = C[2] - A[2]
        // n = u × v — normal of the site plane, i.e. the edge direction.
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val n2 = nx * nx + ny * ny + nz * nz
        if (n2 < 1e-9) return false

        // Circumcentre: A + ((|u|²·v − |v|²·u) × n) / (2|n|²)
        val u2 = ux * ux + uy * uy + uz * uz
        val v2 = vx * vx + vy * vy + vz * vz
        val wx = u2 * vx - v2 * ux
        val wy = u2 * vy - v2 * uy
        val wz = u2 * vz - v2 * uz
        val cx = wy * nz - wz * ny
        val cy = wz * nx - wx * nz
        val cz = wx * ny - wy * nx
        val inv = 1.0 / (2.0 * n2)
        origin[0] = A[0] + cx * inv
        origin[1] = A[1] + cy * inv
        origin[2] = A[2] + cz * inv

        val nLen = Math.sqrt(n2)
        dir[0] = nx / nLen; dir[1] = ny / nLen; dir[2] = nz / nLen
        return true
    }

    /** Lattice-space point on `edge` at parameter `t`. */
    fun pointOnEdge(edge: EdgeRef, t: Double, out: DoubleArray): Boolean {
        val o = DoubleArray(3); val d = DoubleArray(3)
        if (!edgeLine(edge, o, d)) return false
        out[0] = o[0] + d[0] * t
        out[1] = o[1] + d[1] * t
        out[2] = o[2] + d[2] * t
        return true
    }

    // ------------------------------------------------------------------
    //  next-vertex solve
    // ------------------------------------------------------------------

    /**
     * March along `edge` from parameter `fromT` in the direction given by
     * `forward`, and return the first vertex encountered — the fourth site
     * `d` and the parameter `t` at which it becomes equidistant.
     *
     * Returns null only if the candidate set produced nothing ahead, which
     * means the search radius was too small for this edge.
     */
    fun nextVertex(edge: EdgeRef, fromT: Double, forward: Boolean): Step? {
        val o = DoubleArray(3); val n = DoubleArray(3)
        if (!edgeLine(edge, o, n)) return null

        val A = DoubleArray(3)
        sitePos(edge.a, A)
        val a2 = A[0] * A[0] + A[1] * A[1] + A[2] * A[2]

        // Search around the point we're actually standing on, not the
        // circumcentre — on a long edge those can be far apart.
        val px = o[0] + n[0] * fromT
        val py = o[1] + n[1] * fromT
        val pz = o[2] + n[2] * fromT

        val s = DoubleArray(3)
        var bestT = if (forward) Double.MAX_VALUE else -Double.MAX_VALUE
        var bestD = 0L
        var found = false
        val eps = 1e-4

        for (id in nearbySites(px, py, pz)) {
            if (edge.contains(id)) continue
            sitePos(id, s)
            val ex = s[0] - A[0]; val ey = s[1] - A[1]; val ez = s[2] - A[2]
            val denom = 2.0 * (n[0] * ex + n[1] * ey + n[2] * ez)
            if (Math.abs(denom) < 1e-9) continue   // d lies on the edge's own plane
            val d2 = s[0] * s[0] + s[1] * s[1] + s[2] * s[2]
            val numer = d2 - a2 - 2.0 * (o[0] * ex + o[1] * ey + o[2] * ez)
            val t = numer / denom
            if (Math.abs(t - fromT) > MAX_STEP) continue
            if (forward) {
                if (t > fromT + eps && t < bestT) { bestT = t; bestD = id; found = true }
            } else {
                if (t < fromT - eps && t > bestT) { bestT = t; bestD = id; found = true }
            }
        }
        return if (found) Step(bestD, bestT) else null
    }

    /**
     * Is this lattice-space point somewhere the marcher can actually be?
     *
     * Two gates, both mirroring the carve exactly so the entity can never
     * path into stone:
     *  - **Floor.** The carve tapers to zero radius at the world floor, so
     *    the usable band is inset by [BAND_MARGIN].
     *  - **Roof.** Likewise inset below the column's own cave ceiling —
     *    which tracks the surface under deep pools, so the marcher stays
     *    under the pool floor instead of pathing into it.
     *
     * Takes a lattice-space point and unwarps internally, because that is
     * the space the graph solves in.
     */
    fun isTraversable(latX: Double, latY: Double, latZ: Double): Boolean {
        val w = DoubleArray(3)
        unwarp(latX, latY, latZ, w)
        if (w[1] < TRAVERSABLE_MIN_Y) return false
        val wx = Math.floor(w[0]).toInt()
        val wz = Math.floor(w[2]).toInt()
        val surface = WohlonnogondoniaChunkGenerator.coarseSurfaceY(wx, wz) - SURFACE_SLACK
        return w[1] <= WohlonRootField.ceilingFor(surface.toInt()) - BAND_MARGIN
    }

    // ------------------------------------------------------------------
    //  branch selection
    // ------------------------------------------------------------------

    /**
     * Choose the next edge at vertex `{a,b,c,d}`, having arrived along
     * `incoming` heading in direction `(dx, dy, dz)`.
     *
     * Uniform choice over the four outgoing edges gives an aimless random
     * walk that revisits heavily. Instead each candidate is scored by
     * `dot(edgeDir, currentDir)` and drawn from a low-temperature softmax,
     * which produces long roughly-straight courses that turn only where
     * geometry forces them.
     *
     * The reverse edge is **weighted down, not excluded** — hard exclusion
     * creates dead-end traps wherever the local graph offers nothing else.
     *
     * `roll` is the caller's uniform `[0,1)`, so the choice is a pure
     * function of `(vertex, heading, roll)` and can be replayed.
     */
    fun chooseBranch(
        a: Long, b: Long, c: Long, d: Long,
        incoming: EdgeRef,
        dx: Double, dy: Double, dz: Double,
        roll: Double,
    ): EdgeRef? {
        val candidates = arrayOf(
            EdgeRef(a, b, c).canonical(),
            EdgeRef(a, b, d).canonical(),
            EdgeRef(a, c, d).canonical(),
            EdgeRef(b, c, d).canonical(),
        )
        val inc = incoming.canonical()
        val weights = DoubleArray(4)
        val o = DoubleArray(3); val dir = DoubleArray(3)
        var total = 0.0
        for (i in 0 until 4) {
            val e = candidates[i]
            if (!edgeLine(e, o, dir)) { weights[i] = 0.0; continue }
            // Reject branches that leave the carved band. Without this the
            // marcher climbs the (unbounded) skeleton straight out of the
            // cave system and into solid mud.
            if (!isTraversable(o[0], o[1], o[2])) { weights[i] = 0.0; continue }
            // The edge line's sign is arbitrary (it comes from a cross
            // product whose orientation depends on site ordering), so
            // alignment is measured unsigned — what matters is how
            // parallel the tunnel is to our heading, not which way its
            // parameter happens to run.
            val align = Math.abs(dir[0] * dx + dir[1] * dy + dir[2] * dz)
            var w = Math.exp(align / TEMPERATURE)
            if (e == inc) w *= REVERSE_WEIGHT
            weights[i] = w
            total += w
        }
        if (total <= 0.0) return null
        var acc = roll * total
        for (i in 0 until 4) {
            acc -= weights[i]
            if (acc <= 0.0) return candidates[i]
        }
        return candidates[3]
    }

    /** Softmax temperature. Low, so the straightest continuation
     *  dominates and courses read as purposeful. */
    private const val TEMPERATURE = 0.35

    /** Multiplier on the edge we arrived by. Small but non-zero: reversal
     *  stays possible so the entity can escape a cul-de-sac, but is rare
     *  enough that it doesn't pace back and forth. */
    private const val REVERSE_WEIGHT = 0.04

    // ------------------------------------------------------------------
    //  warp inversion
    // ------------------------------------------------------------------

    /**
     * Invert the domain warp: given a point `q` in warped lattice space,
     * find the world position `p` with `p + w(p) = q`, by the fixed-point
     * iteration `p ← q − w(p)`.
     *
     * Converges only while `|∇w| < 1`, which is exactly the constraint
     * [WohlonRootField]'s warp amplitude and frequency are chosen to
     * satisfy. Four iterations is comfortably inside the convergence
     * envelope for the ~0.36 gradient bound in use.
     */
    fun unwarp(qx: Double, qy: Double, qz: Double, out: DoubleArray) {
        var px = qx; var py = qy; var pz = qz
        val w = DoubleArray(3)
        repeat(4) {
            WohlonRootField.warp(px, py, pz, w)
            px = qx - w[0]; py = qy - w[1]; pz = qz - w[2]
        }
        out[0] = px; out[1] = py; out[2] = pz
    }
}
