package org.shipwrights.enderkinesis.util

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * A server `Level.clip` loads or generates each chunk on its line. A long ray must end at
 * the edge of the loaded area first.
 */
object LoadedRay {

    private const val CHUNK_EDGE_MARGIN: Double = 0.01

    /** `hasChunkAt` is also true for a chunk that still generates, and a block read there
     *  stops the server thread until generation is complete. */
    @JvmStatic
    fun blockLoaded(level: net.minecraft.world.level.Level, pos: net.minecraft.core.BlockPos): Boolean =
        if (level is ServerLevel) {
            level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) != null
        } else {
            level.hasChunkAt(pos)
        }

    /** Length of the ray from [origin] along unit [dir], up to [range], that stays in loaded
     *  chunks. A grid walk over chunk columns; the step count is bounded by the range. */
    @JvmStatic
    fun range(level: ServerLevel, origin: Vec3, dir: Vec3, range: Double): Double {
        val chunks = level.chunkSource
        var cx = Math.floor(origin.x / 16.0).toInt()
        var cz = Math.floor(origin.z / 16.0).toInt()
        if (chunks.getChunkNow(cx, cz) == null) return 0.0
        val stepX = if (dir.x > 0.0) 1 else if (dir.x < 0.0) -1 else 0
        val stepZ = if (dir.z > 0.0) 1 else if (dir.z < 0.0) -1 else 0
        val tDeltaX = if (stepX != 0) 16.0 / Math.abs(dir.x) else Double.POSITIVE_INFINITY
        val tDeltaZ = if (stepZ != 0) 16.0 / Math.abs(dir.z) else Double.POSITIVE_INFINITY
        var tMaxX = when {
            stepX > 0 -> ((cx + 1) * 16.0 - origin.x) / dir.x
            stepX < 0 -> (cx * 16.0 - origin.x) / dir.x
            else -> Double.POSITIVE_INFINITY
        }
        var tMaxZ = when {
            stepZ > 0 -> ((cz + 1) * 16.0 - origin.z) / dir.z
            stepZ < 0 -> (cz * 16.0 - origin.z) / dir.z
            else -> Double.POSITIVE_INFINITY
        }
        val maxSteps = (range / 16.0).toInt() * 2 + 4
        for (step in 0 until maxSteps) {
            val t: Double
            if (tMaxX < tMaxZ) {
                t = tMaxX; tMaxX += tDeltaX; cx += stepX
            } else {
                t = tMaxZ; tMaxZ += tDeltaZ; cz += stepZ
            }
            if (t >= range) return range
            if (chunks.getChunkNow(cx, cz) == null) return (t - CHUNK_EDGE_MARGIN).coerceAtLeast(0.0)
        }
        return range
    }
}
