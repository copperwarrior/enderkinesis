package org.shipwrights.enderkinesis.util

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import org.valkyrienskies.core.api.ships.Ship

/** Shared ship-aiming helpers (Shulkonaut @v targeting, missile ship-retargeting). */
object ShipTargeting {

    /** A solid block of the target vessel, in SHIPYARD coords: walk the 3×3 centre
     *  column of its ship AABB from the vertical MIDPOINT outward (down first, then
     *  up) — lands mid-hull instead of crowning the mast, which a top-down scan hit
     *  nearly every time. Point-target missiles validate against this real block, the
     *  same way the staff's gaze stamps its hit blockPos. Null when the centre
     *  columns are hollow or the read budget runs out. */
    fun solidShipAnchor(level: Level, ship: Ship): BlockPos? {
        val aabb = ship.shipAABB ?: return null
        val cx = (aabb.minX() + aabb.maxX()) / 2
        val cy = (aabb.minY() + aabb.maxY()) / 2
        val cz = (aabb.minZ() + aabb.maxZ()) / 2
        val cursor = BlockPos.MutableBlockPos()
        var budget = SCAN_BUDGET
        val span = aabb.maxY() - aabb.minY() + 1
        for (step in 0 until span) {
            // 0, -1, +1, -2, +2, … — hull body first, extremities last.
            val y = cy + (if (step % 2 == 1) -(step + 1) / 2 else step / 2)
            if (y < aabb.minY() || y > aabb.maxY()) continue
            for (dx in -1..1) {
                for (dz in -1..1) {
                    if (budget-- <= 0) return null
                    cursor.set(cx + dx, y, cz + dz)
                    if (!level.getBlockState(cursor).isAir) return cursor.immutable()
                }
            }
        }
        return null
    }

    private const val SCAN_BUDGET = 256
}
