package org.shipwrights.enderkinesis.util

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.joml.Matrix4d
import org.joml.Vector3d
import org.joml.Vector3dc

/** Blocks that VS2 moved from one ship to another in a split, fracture or weld. */
class ShipBlockMove internal constructor(
    val fromShipId: Long,
    val toShipId: Long,
    /** True when the source ship stopped existing (weld). */
    val fromShipDeleted: Boolean,
    private val transform: Matrix4d,
    private val moved: LongOpenHashSet,
) {
    fun contains(pos: BlockPos): Boolean = moved.contains(pos.asLong())

    fun mapBlock(pos: BlockPos): BlockPos {
        val c = transform.transformPosition(Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))
        return BlockPos.containing(c.x, c.y, c.z)
    }

    /** Maps [point] from the old shipyard frame to the new one, in place. */
    fun mapPoint(point: Vector3d): Vector3d = transform.transformPosition(point)

    /** True if a moved cell touches [point]. A raycast point sits on a cell boundary, so each
     *  cell within [EDGE] of it counts. */
    fun touches(point: Vector3dc): Boolean {
        for (dx in 0..1) for (dy in 0..1) for (dz in 0..1) {
            val cell = BlockPos.asLong(
                Math.floor(point.x() + if (dx == 0) -EDGE else EDGE).toInt(),
                Math.floor(point.y() + if (dy == 0) -EDGE else EDGE).toInt(),
                Math.floor(point.z() + if (dz == 0) -EDGE else EDGE).toInt(),
            )
            if (moved.contains(cell)) return true
        }
        return false
    }

    /** Maps [point] on ship [shipId] in place if it moved. A weld moves the whole source ship. */
    fun followPoint(shipId: Long, point: Vector3d): Boolean {
        if (shipId != fromShipId || !(fromShipDeleted || touches(point))) return false
        mapPoint(point)
        return true
    }

    private companion object {
        const val EDGE = 1e-3
    }
}

/** A block entity, entity or manager that keeps ship IDs or shipyard positions. [ShipMoves]
 *  calls it at the end of a tick in which VS2 moved blocks between ships. */
interface ShipMoveListener {
    fun onShipBlocksMoved(level: ServerLevel, move: ShipBlockMove)
}
