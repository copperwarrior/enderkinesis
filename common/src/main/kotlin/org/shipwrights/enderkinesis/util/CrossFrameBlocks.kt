package org.shipwrights.enderkinesis.util

import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.valkyrienskies.mod.common.toWorldCoordinates

/**
 * A world block grid around [centre], which can be in either frame, and the ships within [reach] of it.
 * Not thread-safe: each visit reuses one cursor.
 */
class CrossFrameBlocks(val level: Level, centre: Vec3, reach: Double, shipsNear: List<Ship>? = null) {
    val worldCentre: Vec3 = level.toWorldCoordinates(centre)
    val origin: BlockPos = BlockPos.containing(worldCentre)

    /** The VS intersecting query gives saved ship data on the server: pass [shipsNear] when you must push the ships. */
    val ships: List<Ship> =
        shipsNear ?: level.getShipsIntersecting(AABB(worldCentre, worldCentre).inflate(reach + 1.0)).toList()
    @PublishedApi internal val cursor = BlockPos.MutableBlockPos()
    @PublishedApi internal val shipPoint = Vector3d()

    /** The world middle of the block that [forEachWithin] visits now. */
    val visitedMiddle = Vector3d()

    /**
     * Calls [action] for the world block at this offset from [origin], then for the block of each ship there.
     * The ship is null for the world block, and the pos is in the frame of that ship.
     */
    inline fun forEachAt(dx: Int, dy: Int, dz: Int, action: (BlockPos.MutableBlockPos, Ship?) -> Unit) {
        val x = origin.x + dx
        val y = origin.y + dy
        val z = origin.z + dz
        action(cursor.set(x, y, z), null)
        for (ship in ships) {
            ship.transform.worldToShip.transformPosition(x + 0.5, y + 0.5, z + 0.5, shipPoint)
            action(cursor.set(shipPoint.x, shipPoint.y, shipPoint.z), ship)
        }
    }

    /**
     * Calls [action] for each block whose middle is within [r] of [worldCentre]: the world blocks, then the blocks of each ship.
     * Each frame uses its own grid, so a turned ship has no missed or doubled blocks.
     */
    inline fun forEachWithin(r: Double, world: Boolean = true, action: (BlockPos.MutableBlockPos, Ship?) -> Unit) {
        val ri = Math.ceil(r).toInt()
        val r2 = r * r
        if (world) for (dx in -ri..ri) for (dy in -ri..ri) for (dz in -ri..ri) {
            cursor.set(origin.x + dx, origin.y + dy, origin.z + dz)
            visitedMiddle.set(cursor.x + 0.5, cursor.y + 0.5, cursor.z + 0.5)
            if (visitedMiddle.distanceSquared(worldCentre.x, worldCentre.y, worldCentre.z) <= r2) action(cursor, null)
        }
        for (ship in ships) {
            ship.transform.worldToShip.transformPosition(worldCentre.x, worldCentre.y, worldCentre.z, shipPoint)
            val cx = Math.floor(shipPoint.x).toInt()
            val cy = Math.floor(shipPoint.y).toInt()
            val cz = Math.floor(shipPoint.z).toInt()
            // A cell clearly out of reach in the ship frame skips the transform; the exact world test still decides.
            val scale = ship.transform.shipToWorldScaling
            val uniform = scale.x() == scale.y() && scale.y() == scale.z()
            val scaleSq = scale.x() * scale.x()
            val outSq = r2 + SHIP_FRAME_MARGIN * (1.0 + r2)
            // A scaled ship has 1/scale of its cells in one world block.
            val minScale = minOf(scale.x(), scale.y(), scale.z())
            val si = if (minScale > 0.0) Math.ceil(r / minScale).toInt() else ri
            for (dx in -si..si) for (dy in -si..si) for (dz in -si..si) {
                cursor.set(cx + dx, cy + dy, cz + dz)
                if (uniform && scaleSq * shipPoint.distanceSquared(cursor.x + 0.5, cursor.y + 0.5, cursor.z + 0.5) > outSq) continue
                ship.transform.shipToWorld.transformPosition(cursor.x + 0.5, cursor.y + 0.5, cursor.z + 0.5, visitedMiddle)
                if (visitedMiddle.distanceSquared(worldCentre.x, worldCentre.y, worldCentre.z) <= r2) action(cursor, ship)
            }
        }
    }

    /**
     * Random cells of each ship whose middle is within [r] of [worldCentre], drawn in the ship's own grid. A ship draws
     * [count] samples for each world-block cube of the same size, up to [MAX_SAMPLE_FACTOR] times [count], so its
     * blocks get the same chance as world blocks that get [count] samples in the cube of [r].
     */
    inline fun forEachShipSample(r: Double, count: Int, random: RandomSource, action: (BlockPos.MutableBlockPos, Ship) -> Unit) {
        val ri = Math.ceil(r).toInt()
        val r2 = r * r
        for (ship in ships) {
            ship.transform.worldToShip.transformPosition(worldCentre.x, worldCentre.y, worldCentre.z, shipPoint)
            val cx = Math.floor(shipPoint.x).toInt()
            val cy = Math.floor(shipPoint.y).toInt()
            val cz = Math.floor(shipPoint.z).toInt()
            val scale = ship.transform.shipToWorldScaling
            val minScale = minOf(scale.x(), scale.y(), scale.z())
            val si = if (minScale > 0.0) Math.ceil(r / minScale).toInt() else ri
            val ratio = (2.0 * si + 1.0) / (2.0 * ri + 1.0)
            val samples = (count * ratio * ratio * ratio).toInt().coerceIn(1, count * MAX_SAMPLE_FACTOR)
            repeat(samples) {
                cursor.set(
                    cx + random.nextIntBetweenInclusive(-si, si),
                    cy + random.nextIntBetweenInclusive(-si, si),
                    cz + random.nextIntBetweenInclusive(-si, si),
                )
                ship.transform.shipToWorld.transformPosition(cursor.x + 0.5, cursor.y + 0.5, cursor.z + 0.5, visitedMiddle)
                if (visitedMiddle.distanceSquared(worldCentre.x, worldCentre.y, worldCentre.z) <= r2) action(cursor, ship)
            }
        }
    }

    @PublishedApi internal companion object {
        // Relative slack for float rounding, so the ship-frame skip never drops a cell that the world test keeps.
        const val SHIP_FRAME_MARGIN = 1.0e-6
        // Caps the samples of a much scaled-down ship, so one tick stays cheap.
        const val MAX_SAMPLE_FACTOR = 16
    }
}
