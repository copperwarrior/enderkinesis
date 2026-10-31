package org.shipwrights.enderkinesis.worldgen

import net.minecraft.util.Mth
import org.shipwrights.enderkinesis.block.WogorVineNoise

/**
 * Wetland zonation of the Wohlon shore and floor plants, shared by worldgen ([WohlonUnderstoryFeature])
 * and the spreader, so a spread swamp grows the same plants in the same places as the dimension.
 * Reeds in a band along the shore at depth 0–1; duckweed on enclosed still pools, thinning on open
 * water; lily pads on open water 1–3 deep under open sky; leaf litter on shaded ground.
 * Eligibility and chance are split, so worldgen keeps its random sequence.
 */
object WohlonWetlandZones {

    /** How far the neighbourhood tests look, in blocks. */
    const val WATER_REACH = 3

    /** Standing water around one column, as offsets in -[WATER_REACH]..[WATER_REACH]. */
    interface WetGrid {
        fun wet(dx: Int, dz: Int): Boolean
    }

    /** Chebyshev distance to the nearest standing water, or [WATER_REACH] + 1 if none is in reach. */
    fun waterDistance(grid: WetGrid): Int = nearest(grid, wanted = true)

    /** Chebyshev distance to the nearest non-water column, or [WATER_REACH] + 1 if none is in reach. */
    fun landDistance(grid: WetGrid): Int = nearest(grid, wanted = false)

    private fun nearest(grid: WetGrid, wanted: Boolean): Int {
        var best = WATER_REACH + 1
        for (dx in -WATER_REACH..WATER_REACH) {
            for (dz in -WATER_REACH..WATER_REACH) {
                if (grid.wet(dx, dz) != wanted) continue
                best = minOf(best, maxOf(Math.abs(dx), Math.abs(dz)))
            }
        }
        return best
    }

    /** Share of land columns within [WATER_REACH]: high in small still pools, low on open water. */
    fun enclosure(grid: WetGrid): Float {
        var land = 0
        for (dx in -WATER_REACH..WATER_REACH) {
            for (dz in -WATER_REACH..WATER_REACH) {
                if (!grid.wet(dx, dz)) land++
            }
        }
        return land.toFloat() / ((2 * WATER_REACH + 1) * (2 * WATER_REACH + 1))
    }

    /** Ground flush with the water line, close enough to the water: the landward edge of the reed band. */
    fun reedOnLand(waterDistance: Int): Boolean = waterDistance <= REED_LAND_REACH

    /** Water one deep near land: the waterward edge of the reed band. */
    fun reedInWater(depth: Int, landDistance: Int): Boolean = depth == 1 && landDistance <= REED_WATER_REACH

    fun reedChance(x: Int, z: Int): Float = REED_CHANCE * patch(WogorVineNoise.rawNoise(x, REED_NOISE_Y, z))

    fun duckweedChance(enclosure: Float, landDistance: Int): Float = when {
        enclosure >= ENCLOSED_POOL -> DUCKWEED_ENCLOSED_CHANCE
        enclosure >= SEMI_OPEN_POOL && landDistance <= 1 -> DUCKWEED_BANK_CHANCE
        enclosure >= SEMI_OPEN_POOL -> DUCKWEED_OPEN_CHANCE
        else -> 0f
    }

    /** Open water 1–3 deep. The caller also tests for open sky. */
    fun lilyWater(depth: Int, enclosure: Float): Boolean = enclosure < ENCLOSED_POOL && depth in 1..LILY_MAX_DEPTH

    fun lilyChance(x: Int, z: Int): Float {
        val cluster = Mth.clamp(((WogorVineNoise.rawNoise(x, LILY_NOISE_Y, z) - LILY_PATCH_LOW) / LILY_PATCH_SPAN).toFloat(), 0f, 1f)
        return LILY_CHANCE * cluster
    }

    /** Leaf litter gathers on shaded ground only. */
    fun litterChance(x: Int, z: Int, shaded: Boolean): Float =
        if (shaded) LITTER_CHANCE * patch(WogorVineNoise.rawNoise(x, LITTER_NOISE_Y, z)) else 0f

    /** Maps raw value noise to a patch weight: bare between patches, full inside them. */
    fun patch(noise: Double): Float = Mth.clamp(((noise - PATCH_LOW) / PATCH_SPAN).toFloat(), 0f, 1f)

    const val LILY_MAX_DEPTH = 3

    private const val PATCH_LOW = 0.35
    private const val PATCH_SPAN = 0.35

    private const val LILY_NOISE_Y = 20480
    private const val LILY_PATCH_LOW = 0.5
    private const val LILY_PATCH_SPAN = 0.2
    private const val LILY_CHANCE = 0.65f

    private const val REED_NOISE_Y = 24576
    private const val REED_CHANCE = 0.6f
    /** Reed band reaches this far into the water from land, and this far onto land from water. */
    private const val REED_WATER_REACH = 3
    private const val REED_LAND_REACH = 2

    /** [enclosure] at or above this is a small still pool that duckweed covers. */
    private const val ENCLOSED_POOL = 0.45f
    private const val SEMI_OPEN_POOL = 0.2f
    private const val DUCKWEED_ENCLOSED_CHANCE = 0.85f
    private const val DUCKWEED_BANK_CHANCE = 0.45f
    private const val DUCKWEED_OPEN_CHANCE = 0.12f

    private const val LITTER_NOISE_Y = 28672
    private const val LITTER_CHANCE = 0.3f
}
