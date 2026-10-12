package org.shipwrights.enderkinesis.physics

/**
 * Shoreline shaping for the lattice ocean's elliptical rim, shared by the client mesh
 * renderer and the server particle emitter so both agree on where the shore is.
 *
 * All noise is calibrated in WORLD units — a fixed inset depth (blocks) and fixed lobe
 * wavelengths (blocks, rounded to an integer count around the loop so it stays
 * seamless). Normalized-space noise squashed with the ellipse: amplitude scaled with
 * the ocean's size and stretched along its long axis.
 */
object LatticeShore {

    /**
     * Multiplicative warp on the normalized rim radius for the ray through local
     * physical offset ([lx], [lz]) (ship-frame blocks from the ellipse centre).
     * Always <= 1 (inward-only): the mesh grid is windowed to the ellipse extents,
     * so an outward wobble would have no vertices to land on.
     */
    fun rimWarp(lx: Double, lz: Double, waveTime: Double, ax: Double, az: Double): Double {
        val theta = Math.atan2(lz, lx)
        return rimWarpAt(theta, rDirAt(theta, ax, az), waveTime, Math.PI * (ax + az))
    }

    /** Core of [rimWarp] with the per-vertex-shareable parts precomputed by the caller
     *  (the mesh renderer computes theta/rDir once and reuses them for the taper AND
     *  the tendril edge). */
    fun rimWarpAt(theta: Double, rDir: Double, waveTime: Double, circumference: Double): Double {
        val t = waveTime * DRIFT_RATE
        val k1 = Math.max(2L, Math.round(circumference / WAVELENGTH_1)).toDouble()
        val k2 = Math.max(3L, Math.round(circumference / WAVELENGTH_2)).toDouble()
        val k3 = Math.max(5L, Math.round(circumference / WAVELENGTH_3)).toDouble()
        val n = Math.sin(theta * k1 + t) * 0.5 +
            Math.sin(theta * k2 - t * 1.7 + 1.3) * 0.3 +
            Math.sin(theta * k3 + t * 0.6 + 4.1) * 0.2
        // The inset is a constant block depth, expressed against the directional
        // radius, so the coastline wobble is ~AMP_BLOCKS deep everywhere.
        val inset = AMP_BLOCKS * (n * 0.5 + 0.5)
        return (1.0 - inset / rDir).coerceAtLeast(0.5)
    }

    /** Physical radius of the ellipse along the ray at [theta]. */
    fun rDirAt(theta: Double, ax: Double, az: Double): Double {
        val cosT = Math.cos(theta)
        val sinT = Math.sin(theta)
        return 1.0 / Math.sqrt((cosT / ax) * (cosT / ax) + (sinT / az) * (sinT / az))
    }

    /** Conservative normalized RAW radius below which a point is guaranteed fully
     *  interior — taper 1, edge 1 — letting hot loops skip all shore math. */
    fun interiorSafeRadius(ax: Double, az: Double): Double {
        val minAxis = Math.min(ax, az)
        val minWarp = Math.max(0.5, 1.0 - AMP_BLOCKS / minAxis)
        val rootMin = 1.0 - TENDRIL_LEN_BLOCKS / minAxis
        return minWarp * Math.min(TAPER_START, rootMin)
    }

    /**
     * Alpha at the shore: the fringe is a ring of DISCRETE tendrils — actual tubes,
     * one root every ~[TENDRIL_SPACING_BLOCKS] along the rim, each with its own
     * hashed length/width/wiggle. A vertex takes the strongest coverage among the
     * three nearest tendrils' sinuous centerlines. Noise-threshold fingers read as
     * spikes (straight) or splatter (domain-warped, disconnected); explicit tubes
     * stay connected root-to-tip by construction. [rWarped] from [rimWarp].
     */
    fun shoreEdge(rWarped: Double, lx: Double, lz: Double, waveTime: Double, ax: Double, az: Double): Float {
        val theta = Math.atan2(lz, lx)
        return shoreEdgeAt(rWarped, theta, rDirAt(theta, ax, az), waveTime, Math.PI * (ax + az))
    }

    /** Core of [shoreEdge] with theta/rDir/circumference precomputed by the caller. */
    fun shoreEdgeAt(rWarped: Double, theta: Double, rDir: Double, waveTime: Double, circumference: Double): Float {
        if (rWarped >= 1.0) return 0f
        val lenNorm = TENDRIL_LEN_BLOCKS / rDir
        val root = 1.0 - lenNorm
        if (rWarped <= root) return 1f
        val u = (rWarped - root) / lenNorm            // 0 at the solid shoreline → 1 at full reach

        val count = Math.max(6L, Math.round(circumference / TENDRIL_SPACING_BLOCKS))
        val slice = 2.0 * Math.PI / count
        val t = waveTime * CHURN_RATE
        val centre = Math.floor(theta / slice + 0.5).toLong()

        // Cove fade: the gaps BETWEEN tendrils get a soft outward gradient instead of
        // cutting to void — layered under the solid scalloped coast and the streamers.
        val cf = (1.0 - u / COVE_FADE_REACH).coerceIn(0.0, 1.0)
        val coveFade = cf * cf * (3.0 - 2.0 * cf) * COVE_FADE_MAX

        var best = 0.0
        for (dj in -1..1) {
            val j = Math.floorMod(centre + dj, count)
            // Per-tendril character from a hash — deterministic, no allocation.
            // Length PULSES over time (per-tendril phase) — the churn: streamers
            // extend and retreat rather than holding a frozen reach.
            val lenBase = 0.45 + 0.55 * h01(j, 1L)           // reach, fraction of fringe
            val len = lenBase * (0.8 + 0.2 * Math.sin(t * 0.7 + h01(j, 6L) * 2.0 * Math.PI))
            if (u >= len) continue
            val halfW = TENDRIL_HALF_WIDTH * (0.6 + 0.4 * h01(j, 2L))
            val wigglePhase = h01(j, 3L) * 2.0 * Math.PI
            val wiggleAmp = TENDRIL_WIGGLE_BLOCKS * (0.5 + 0.5 * h01(j, 4L))
            val sway = if (h01(j, 5L) > 0.5) 1.0 else -1.0
            // Sinuous centerline: anchored at the root, snaking harder with reach.
            val cLine = wiggleAmp * u * Math.sin(u * TENDRIL_WIGGLE_TURNS + wigglePhase + t * sway)
            val rootAngle = (centre + dj) * slice
            val lateral = wrapAngle(theta - rootAngle) * rDir - cLine   // blocks off-centerline
            val vu = u / len                                            // 0..1 along THIS tendril
            // Width = max of the tendril's own taper and the ROOT MERGE: near the
            // coast every tendril is wide enough to fuse with its neighbours, so the
            // sea's outline scallops smoothly into the streamers — the water stays
            // fully solid; only its SHAPE flows from coastline into tendril. The
            // merge width dies off over the first stretch of the fringe.
            val rootMerge = ROOT_MERGE_HALF_WIDTH * (1.0 - u / MERGE_END)
            val w = Math.max(halfW * (1.0 - vu * vu), rootMerge)
            if (w <= 0.0) continue
            val a = ((w - Math.abs(lateral)) / EDGE_SOFT_BLOCKS).coerceIn(0.0, 1.0) *
                (1.0 - vu * TIP_THINNING)
            if (a > best) best = a
        }
        return Math.max(best, coveFade).toFloat()
    }

    private fun wrapAngle(a: Double): Double {
        var x = a
        while (x > Math.PI) x -= 2.0 * Math.PI
        while (x < -Math.PI) x += 2.0 * Math.PI
        return x
    }

    /** Deterministic per-tendril hash → [0, 1). */
    private fun h01(j: Long, salt: Long): Double {
        var x = j * 0x9E3779B97F4A7C15uL.toLong() + salt * 0xC2B2AE3D27D4EB4FuL.toLong()
        x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        x = (x xor (x ushr 27)) * 0x94D049BB133111EBuL.toLong()
        x = x xor (x ushr 31)
        return (x ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    /** Wave-amplitude taper vs warped radius: 1 in the interior, smoothstep to 0 at
     *  the shore — the sea calms before it ends instead of being clipped mid-swell. */
    fun amplitudeTaper(rWarped: Double): Double {
        val x = ((1.0 - rWarped) / (1.0 - TAPER_START)).coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    /** Coastline wobble depth (blocks), constant across ocean sizes. */
    private const val AMP_BLOCKS = 8.0
    /** Lobe wavelengths along the rim (blocks) — three octaves of coastline detail. */
    private const val WAVELENGTH_1 = 64.0
    private const val WAVELENGTH_2 = 27.0
    private const val WAVELENGTH_3 = 15.0
    /** Coastline breathing rate (radians per waveTime tick) — a slow drift. */
    private const val DRIFT_RATE = 0.008
    /** Tendril churn rate — snaking and length-pulsing run notably faster than the
     *  coastline's breathing. */
    private const val CHURN_RATE = 0.045
    /** Warped radius where the amplitude taper begins. */
    private const val TAPER_START = 0.55

    /** Radial length of the tendril fringe (blocks). */
    private const val TENDRIL_LEN_BLOCKS = 14.0
    /** Rim distance between tendril roots (blocks). */
    private const val TENDRIL_SPACING_BLOCKS = 7.0
    /** Max half-width of a tendril at its root (blocks). */
    private const val TENDRIL_HALF_WIDTH = 4.5
    /** Lateral snake amplitude at full reach (blocks) and its along-length turns. */
    private const val TENDRIL_WIGGLE_BLOCKS = 3.0
    private const val TENDRIL_WIGGLE_TURNS = 5.0
    /** Soft anti-aliasing width at each tendril's flank (blocks). */
    private const val EDGE_SOFT_BLOCKS = 3.5
    /** Alpha loss at tendril tips (0..1) — streamers thin as they reach. */
    private const val TIP_THINNING = 0.55
    /** Root-merge half-width (blocks): ≥ spacing/2 + edge softness, so tendril bases
     *  fuse into a fully solid scalloped coast. */
    private const val ROOT_MERGE_HALF_WIDTH = 7.0
    /** Fraction of the fringe over which the root merge dies off. */
    private const val MERGE_END = 0.35
    /** Cove fade: gaps between tendrils dim outward to this far through the fringe… */
    private const val COVE_FADE_REACH = 0.7
    /** …peaking at this alpha (kept below full so streamers read against it). */
    private const val COVE_FADE_MAX = 0.55
}
