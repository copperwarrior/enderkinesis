package org.shipwrights.enderkinesis.physics

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap

/**
 * The "terrain Y" used to gauge how deep the virtual ocean is (fed to
 * [GerstnerOcean.intensityFor]). Must be identical on client and server so the visual and physics
 * waves stay coherent.
 *
 * Open-sky dimensions use the `MOTION_BLOCKING` heightmap (the one vanilla syncs to clients). But
 * in a **closed-ceiling** dimension (the Nether) that heightmap is the *bedrock roof* — constant
 * everywhere — so it gives a flat, dimension-wide intensity. There is no client-synced *floor*
 * heightmap, so for ceiling dims we instead scan downward from the wave plane to the first
 * non-air block. The scan is bounded to [MAX_DEPTH_SCAN]; anything deeper is already past full
 * wave intensity, so a miss simply reports max depth.
 */
object OceanDepth {
    /** Past this many blocks the ocean is already at full intensity, so stop scanning. */
    private const val MAX_DEPTH_SCAN = 64

    fun terrainY(level: Level, blockX: Int, blockZ: Int, planeY: Double): Double {
        if (!level.dimensionType().hasCeiling()) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ).toDouble()
        }
        val startY = Math.min(Math.floor(planeY).toInt() - 1, level.maxBuildHeight - 1)
        val floor = Math.max(level.minBuildHeight, startY - MAX_DEPTH_SCAN)
        val cursor = BlockPos.MutableBlockPos()
        var y = startY
        while (y >= floor) {
            if (!level.getBlockState(cursor.set(blockX, y, blockZ)).isAir) return y.toDouble()
            y--
        }
        return floor.toDouble()
    }
}
