package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.ServerShip

/** What the walker carries, measured from its plate in the ship's own grid: how far it
 *  reaches and how high, for the planner. Contact comes from VS2 (ConstriliarContacts). */
internal class ConstriliarCargoShape {

    /** Furthest cell the load fills from the plate's column across the plate (blocks):
     *  the planner works on whole cells, so the load is sized exactly, never rounded up. */
    var radius = 0
        private set

    /** Layers the body and its load fill above the surface; a bare body keeps one layer
     *  of headroom, as it always had. */
    var height = MIN_HEIGHT
        private set

    /** A load block fills the cell straight above the plate: the avoidance rays scan it. */
    var blockAbove = false
        private set

    private var key = Long.MIN_VALUE

    /** Rescans when the ship's blocks change (its box or mass). */
    fun update(level: ServerLevel, ship: ServerShip, platform: BlockPos) {
        val box = ship.shipAABB ?: return
        val newKey = (box.minX() * 31L + box.maxX()) * 31L + box.minY() + box.maxY() * 961L +
            (box.minZ() * 29791L + box.maxZ()) + java.lang.Double.doubleToLongBits(ship.inertiaData.mass)
        if (newKey == key) return
        key = newKey
        var reach = 0
        var up = 0
        var above = false
        ConstriliarCargo.forEachShipBlock(level, ship, SCAN_BUDGET) { pos, _ ->
            if (pos == platform) return@forEachShipBlock
            val dx = pos.x - platform.x
            val dy = pos.y - platform.y
            val dz = pos.z - platform.z
            reach = Math.max(reach, Math.max(Math.abs(dx), Math.abs(dz)))
            up = Math.max(up, dy)
            if (dx == 0 && dy == 1 && dz == 0) above = true
        }
        blockAbove = above
        radius = reach
        height = Math.max(MIN_HEIGHT, up + 1)
    }

    private companion object {
        const val SCAN_BUDGET = 4096
        const val MIN_HEIGHT = 2
    }
}
