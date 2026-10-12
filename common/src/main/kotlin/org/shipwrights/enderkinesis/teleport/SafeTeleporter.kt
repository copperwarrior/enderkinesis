package org.shipwrights.enderkinesis.teleport

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Finds a "suitably open" landing spot in a target dimension and moves entities there, giving up
 * after a handful of tries (as requested in the design).
 */
object SafeTeleporter {

    private const val MAX_TRIES = 8

    /** The volume a landing spot must keep clear: a 1x3x1 column on top of solid ground. */
    private const val CLEAR_HEIGHT = 3

    /** Blocks of clearance kept under a ship's box above the terrain surface (it floats). */
    private const val SHIP_GROUND_CLEARANCE = 6

    /** Closed-ceiling dims (Nether): keep the ship box this far below the logical roof so it
     *  stays clear of the patchy bedrock layer, and this far above the dimension floor. */
    private const val SHIP_CEILING_MARGIN = 8
    private const val SHIP_FLOOR_MARGIN = 5
    /** Vertical step when scanning a ceiling dim downward for an open pocket. */
    private const val CEILING_SCAN_STRIDE = 3

    /**
     * Picks a safe block within [searchRadius] of the scaled source coordinates, or null if no
     * candidate was found within [MAX_TRIES] attempts.
     */
    fun findSafeSpot(
        target: ServerLevel,
        approxX: Int,
        approxZ: Int,
        searchRadius: Int = 48,
    ): BlockPos? {
        val rand = target.random
        repeat(MAX_TRIES) {
            val x = approxX + rand.nextInt(searchRadius * 2 + 1) - searchRadius
            val z = approxZ + rand.nextInt(searchRadius * 2 + 1) - searchRadius
            // Force the chunk to load before sampling; reading blocks/heightmap from an unloaded
            // cross-dimension chunk is what was blowing up.
            target.getChunk(x shr 4, z shr 4)
            if (target.dimensionType().hasCeiling()) {
                openSpotBelowCeiling(target, x, z)?.let { return it }
                return@repeat
            }
            val surface = target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            val feet = BlockPos(x, surface, z)
            if (isOpen(target, feet)) return feet
        }
        return null
    }

    /** In a dimension with a ceiling (Nether) the heightmap gives the bedrock roof. */
    private fun openSpotBelowCeiling(target: ServerLevel, x: Int, z: Int): BlockPos? {
        val top = target.minBuildHeight + target.dimensionType().logicalHeight() - SHIP_CEILING_MARGIN
        val cursor = BlockPos.MutableBlockPos()
        for (y in top downTo target.minBuildHeight + 2) {
            if (isOpen(target, cursor.set(x, y, z))) return cursor.immutable()
        }
        return null
    }

    /**
     * Plain OBB-vs-blocks test: the landing box must sit on sturdy ground and overlap only
     * non-solid, fluid-free blocks. No entity collision / voxel polygon intersection.
     */
    private fun isOpen(level: ServerLevel, feet: BlockPos): Boolean {
        if (feet.y <= level.minBuildHeight + 1) return false
        if (feet.y + CLEAR_HEIGHT >= level.maxBuildHeight) return false

        val ground = feet.below()
        if (!level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) return false

        val box = AABB(
            feet.x.toDouble(), feet.y.toDouble(), feet.z.toDouble(),
            feet.x + 1.0, feet.y + CLEAR_HEIGHT.toDouble(), feet.z + 1.0
        )
        for (pos in BlockPos.betweenClosed(
            Math.floor(box.minX).toInt(), Math.floor(box.minY).toInt(), Math.floor(box.minZ).toInt(),
            Math.ceil(box.maxX).toInt() - 1, Math.ceil(box.maxY).toInt() - 1, Math.ceil(box.maxZ).toInt() - 1
        )) {
            val state = level.getBlockState(pos)
            if (!state.fluidState.isEmpty) return false
            if (!state.getCollisionShape(level, pos).isEmpty) return false
        }
        return true
    }

    /**
     * OBB scan for a ship: a clear box of [sizeX]×[sizeY]×[sizeZ] (no collision, no fluid) sitting
     * [SHIP_GROUND_CLEARANCE] above the terrain. [approxX]/[approxZ] is the scaled ship centre —
     * tried *exactly* first so a clear destination is ship-centre→ship-centre; only if obstructed
     * does it search outward. Returns the box centre, or null after [MAX_TRIES] tries.
     */
    fun findShipLanding(
        target: ServerLevel,
        approxX: Int,
        approxZ: Int,
        sizeX: Double,
        sizeY: Double,
        sizeZ: Double,
        searchRadius: Int = 64,
    ): Vec3? {
        val rand = target.random
        val hx = sizeX * 0.5
        val hz = sizeZ * 0.5

        // Closed-ceiling dims (the Nether) have a bedrock roof; the surface heightmap returns it,
        // so a heightmap landing would sit *on top of* the roof. Instead the ship must fit in the
        // open band below the top bedrock and above the bottom bedrock.
        val hasCeiling = target.dimensionType().hasCeiling()
        val ceilTop = target.minBuildHeight + target.dimensionType().logicalHeight() -
            SHIP_CEILING_MARGIN
        val floorBottom = target.minBuildHeight + SHIP_FLOOR_MARGIN


        repeat(MAX_TRIES) { attempt ->
            // First attempt is exactly the scaled ship centre; later ones search around it.
            val x = if (attempt == 0) approxX
                else approxX + rand.nextInt(searchRadius * 2 + 1) - searchRadius
            val z = if (attempt == 0) approxZ
                else approxZ + rand.nextInt(searchRadius * 2 + 1) - searchRadius
            target.getChunk(x shr 4, z shr 4)   // force-load before sampling
            val cx = x + 0.5
            val cz = z + 0.5

            if (hasCeiling) {
                // Scan downward from just under the roof for the highest clear pocket that fits.
                var baseY = ceilTop - Math.ceil(sizeY).toInt()
                while (baseY >= floorBottom) {
                    val box = AABB(
                        cx - hx, baseY.toDouble(), cz - hz,
                        cx + hx, baseY + sizeY, cz + hz,
                    )
                    if (boxClear(target, box)) {
                        val result = Vec3(cx, baseY + sizeY * 0.5, cz)
                        return result
                    }
                    baseY -= CEILING_SCAN_STRIDE
                }
            } else {
                val surfaceY = target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                val baseY = surfaceY + SHIP_GROUND_CLEARANCE
                if (baseY <= target.minBuildHeight + 1) {
                    return@repeat
                }
                if (baseY + sizeY + 1 >= target.maxBuildHeight) {
                    return@repeat
                }
                val box = AABB(
                    cx - hx, baseY.toDouble(), cz - hz,
                    cx + hx, baseY + sizeY, cz + hz,
                )
                if (boxClear(target, box)) {
                    val result = Vec3(cx, baseY + sizeY * 0.5, cz)
                    return result
                }
            }
        }
        return null
    }


    /** No fluid and no solid collision anywhere in [box]. */
    private fun boxClear(level: ServerLevel, box: AABB): Boolean {
        // One fetch for each chunk column, not for each block.
        for (cx in (Math.floor(box.minX).toInt() shr 4)..((Math.ceil(box.maxX).toInt() - 1) shr 4)) {
            for (cz in (Math.floor(box.minZ).toInt() shr 4)..((Math.ceil(box.maxZ).toInt() - 1) shr 4)) {
                level.getChunk(cx, cz)
            }
        }
        for (pos in BlockPos.betweenClosed(
            Math.floor(box.minX).toInt(), Math.floor(box.minY).toInt(), Math.floor(box.minZ).toInt(),
            Math.ceil(box.maxX).toInt() - 1, Math.ceil(box.maxY).toInt() - 1,
            Math.ceil(box.maxZ).toInt() - 1,
        )) {
            val state = level.getBlockState(pos)
            if (!state.fluidState.isEmpty) return false
            if (!state.getCollisionShape(level, pos).isEmpty) return false
        }
        return true
    }

    /** Teleports [entity] to exact world coords in [target] (cross-dimension if needed). */
    fun teleportTo(entity: Entity, target: ServerLevel, x: Double, y: Double, z: Double): Boolean =
        when (entity) {
            is ServerPlayer -> {
                entity.teleportTo(target, x, y, z, entity.yRot, entity.xRot)
                true
            }
            else -> {
                val moved = if (entity.level() == target) entity else entity.changeDimension(target)
                moved?.teleportTo(x, y, z) != null
            }
        }

    /** Teleports [entity] into [target] at [pos]. Returns false if it could not be moved. */
    fun teleport(entity: Entity, target: ServerLevel, pos: BlockPos): Boolean {
        val x = pos.x + 0.5
        val y = pos.y.toDouble()
        val z = pos.z + 0.5
        return when (entity) {
            is ServerPlayer -> {
                entity.teleportTo(target, x, y, z, entity.yRot, entity.xRot)
                true
            }
            else -> entity.changeDimension(target) != null
        }
    }
}
