package org.shipwrights.enderkinesis.dimension

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Identity for **Sselith's Repertory** — an infinite dimension of stacked bookshelf boxes
 * framed by deepslate-brick columns, sliced by a polished-deepslate cross (a Nexus of
 * Meridians) at each [MERIDIAN_INTERVAL] blocks on X and Z. Sun fixed at 34.5° pitch/yaw/roll, dark orange sky with deep blue drifting
 * stars. World is void below — no floor, no bedrock.
 *
 *  - Chunk generation: [SselithRepertoryChunkGenerator]
 *  - Sky rendering: `SselithRepertorySky` (client-side, via Mixin into LevelRenderer.renderSky)
 */
object SselithRepertory {
    val ID: ResourceLocation = EnderkinesisMod.id("sselith_repertory")
    val LEVEL_KEY: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ID)
    val DIMENSION_TYPE_KEY: ResourceKey<DimensionType> = ResourceKey.create(Registries.DIMENSION_TYPE, ID)

    /** |y| above which the fog density bottoms out at [FOG_MIN_DENSITY]. */
    const val FOG_FALLOFF_Y = 128.0

    /** Lower bound on fog density even at extreme altitudes — keeps a thin haze visible so
     *  the dimension never looks like pure overworld clarity. 0 would mean no fog at all
     *  past [FOG_FALLOFF_Y], which the player rejected as too clear. */
    const val FOG_MIN_DENSITY = 0.25f

    /** Blocks from each meridian to the edge of the band between the library quadrants. */
    const val LIBRARY_QUADRANT_SHIFT = 5

    const val MAZE_CELL_XZ = 49

    /** Library cells between two adjacent meridians, on each horizontal axis. */
    const val MERIDIAN_INTERVAL_CELLS = 20

    private const val BAND_WIDTH = 2 * LIBRARY_QUADRANT_SHIFT + 1
    private const val LATTICE_PER_INTERVAL = MAZE_CELL_XZ * MERIDIAN_INTERVAL_CELLS

    /** Blocks between two adjacent Nexus of Meridians. Must be one band plus whole cells: 991. */
    const val MERIDIAN_INTERVAL = BAND_WIDTH + LATTICE_PER_INTERVAL

    const val MERIDIAN_OFFSET_MIN = -(MERIDIAN_INTERVAL / 2)
    const val MERIDIAN_OFFSET_MAX = MERIDIAN_INTERVAL - 1 + MERIDIAN_OFFSET_MIN

    /** Signed distance of [w] from the nearest meridian, in [MERIDIAN_OFFSET_MIN]..[MERIDIAN_OFFSET_MAX]. */
    @JvmStatic
    fun meridianOffset(w: Int): Int =
        Math.floorMod(w - MERIDIAN_OFFSET_MIN, MERIDIAN_INTERVAL) + MERIDIAN_OFFSET_MIN

    @JvmStatic
    fun nearestMeridian(w: Int): Int = w - meridianOffset(w)

    /** [w] with all meridian bands removed. A band column maps to the first column after its band. */
    @JvmStatic
    fun latticeCoord(w: Int): Int {
        val shifted = w + LIBRARY_QUADRANT_SHIFT
        val region = Math.floorDiv(shifted, MERIDIAN_INTERVAL)
        val pastBand = shifted - region * MERIDIAN_INTERVAL - BAND_WIDTH
        return region * LATTICE_PER_INTERVAL + if (pastBand > 0) pastBand else 0
    }

    /** Inverse of [latticeCoord] for columns out of the bands. */
    @JvmStatic
    fun worldCoord(lattice: Int): Int =
        lattice + Math.floorDiv(lattice, LATTICE_PER_INTERVAL) * BAND_WIDTH + LIBRARY_QUADRANT_SHIFT + 1

    @JvmStatic
    fun isAxialBand(x: Int, z: Int): Boolean =
        meridianOffset(x) in -LIBRARY_QUADRANT_SHIFT..LIBRARY_QUADRANT_SHIFT ||
            meridianOffset(z) in -LIBRARY_QUADRANT_SHIFT..LIBRARY_QUADRANT_SHIFT

    /**
     * Fog density at altitude [y]: 1.0 at y=0, linearly falling to [FOG_MIN_DENSITY] at
     * |y| ≥ [FOG_FALLOFF_Y]. Used by both
     * [org.shipwrights.enderkinesis.mixin.FogRendererSselithRepertoryMixin] (for
     * terrain/world fog alpha) and `SselithRepertorySky.renderSky` (for the sky's
     * clear-colour blend), so the two stay locked in step.
     */
    @JvmStatic
    fun fogDensityAt(y: Double): Float = 1.0f - fogHeightFraction(y) * (1.0f - FOG_MIN_DENSITY)

    /** 0 at y=0, rising linearly to 1 at |y| ≥ [FOG_FALLOFF_Y]. Fog colour scales by it, so the
     *  fog is black at y=0 on the same curve the density follows. */
    @JvmStatic
    fun fogHeightFraction(y: Double): Float {
        val t = Math.abs(y) / FOG_FALLOFF_Y
        return if (t > 1.0) 1.0f else t.toFloat()
    }
}
