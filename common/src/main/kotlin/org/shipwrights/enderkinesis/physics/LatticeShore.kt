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

    /** Core of [rimWarp] with theta/rDir/circumference precomputed by the caller. */
    fun rimWarpAt(theta: Double, rDir: Double, waveTime: Double, circumference: Double): Double {
        val n = Math.sin(theta * lobeCount(0, circumference) + driftPhase(0, waveTime)) * 0.5 +
            Math.sin(theta * lobeCount(1, circumference) + driftPhase(1, waveTime)) * 0.3 +
            Math.sin(theta * lobeCount(2, circumference) + driftPhase(2, waveTime)) * 0.2
        // The inset is a constant block depth, expressed against the directional
        // radius, so the coastline wobble is ~AMP_BLOCKS deep everywhere.
        val inset = AMP_BLOCKS * (n * 0.5 + 0.5)
        return (1.0 - inset / rDir).coerceAtLeast(0.5)
    }

    /** Coastline lobes around the rim for [octave] 0..2: a whole number, so the loop has no seam. */
    fun lobeCount(octave: Int, circumference: Double): Double = when (octave) {
        0 -> Math.max(2L, Math.round(circumference / WAVELENGTH_1))
        1 -> Math.max(3L, Math.round(circumference / WAVELENGTH_2))
        else -> Math.max(5L, Math.round(circumference / WAVELENGTH_3))
    }.toDouble()

    /** Phase of coastline [octave] 0..2 at [waveTime]. */
    fun driftPhase(octave: Int, waveTime: Double): Double {
        val t = waveTime * DRIFT_RATE
        return when (octave) {
            0 -> t
            1 -> 1.3 - t * 1.7
            else -> t * 0.6 + 4.1
        }
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
        val rootMin = 1.0 - FALLOFF_BLOCKS / minAxis
        return minWarp * Math.min(TAPER_START, rootMin)
    }

    /** Alpha at the shore: a smoothstep from 1 to 0 over the last [FALLOFF_BLOCKS] inside the warped rim. */
    fun shoreEdgeAt(rWarped: Double, rDir: Double): Float {
        val x = ((1.0 - rWarped) * rDir / FALLOFF_BLOCKS).coerceIn(0.0, 1.0)
        return (x * x * (3.0 - 2.0 * x)).toFloat()
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
    /** Warped radius where the amplitude taper begins. */
    private const val TAPER_START = 0.55

    // rendertype_lattice_ocean.fsh has copies of AMP_BLOCKS and FALLOFF_BLOCKS. Change them together.
    /** Width of the shore falloff (blocks). */
    private const val FALLOFF_BLOCKS = 14.0
}
