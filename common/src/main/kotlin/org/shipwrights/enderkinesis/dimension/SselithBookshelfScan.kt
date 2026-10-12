package org.shipwrights.enderkinesis.dimension

import java.util.function.Predicate
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

/** The bookshelf rule of [SselithChatTeleport]. The server and the client glyph effect use the same scan. */
object SselithBookshelfScan {
    const val RADIUS_H = 3
    const val RADIUS_V = 2
    const val MIN_BOOKSHELVES = 4

    private val IS_BOOKSHELF = Predicate<BlockState> { it.`is`(EKBlocks.SSELITH_BOOKSHELF.get()) }

    private class Tally(val limit: Int, val found: MutableList<Vec3>?) {
        var count = 0
        val full get() = count >= limit
    }

    /**
     * Counts the Sselith bookshelves in the 7×5×7 box around the position, in the world and on VS2 ships.
     * The count stops at [limit]. [found] receives the WORLD-frame center of each counted bookshelf.
     */
    fun collect(level: Level, x: Double, y: Double, z: Double, limit: Int, found: MutableList<Vec3>?): Int {
        require(limit > 0) { "limit must be positive" }
        val px = Mth.floor(x)
        val py = Mth.floor(y)
        val pz = Mth.floor(z)
        val tally = Tally(limit, found)
        val worldMin = Vector3d((px - RADIUS_H).toDouble(), (py - RADIUS_V).toDouble(), (pz - RADIUS_H).toDouble())
        val worldMax = Vector3d(px + RADIUS_H + 1.0, py + RADIUS_V + 1.0, pz + RADIUS_H + 1.0)

        scanBox(
            level, null, worldMin, worldMax, tally,
            px - RADIUS_H, py - RADIUS_V, pz - RADIUS_H, px + RADIUS_H, py + RADIUS_V, pz + RADIUS_H,
        )
        if (tally.full) return tally.count

        val corner = Vector3d()
        val shipMin = Vector3d()
        val shipMax = Vector3d()
        for (ship in level.shipObjectWorld.allShips) {
            val aabb = ship.worldAABB
            if (aabb.maxX() < worldMin.x || aabb.minX() > worldMax.x) continue
            if (aabb.maxY() < worldMin.y || aabb.minY() > worldMax.y) continue
            if (aabb.maxZ() < worldMin.z || aabb.minZ() > worldMax.z) continue

            shipMin.set(Double.POSITIVE_INFINITY)
            shipMax.set(Double.NEGATIVE_INFINITY)
            for (i in 0 until 8) {
                corner.set(
                    if (i and 1 == 0) worldMin.x else worldMax.x,
                    if (i and 2 == 0) worldMin.y else worldMax.y,
                    if (i and 4 == 0) worldMin.z else worldMax.z,
                )
                ship.transform.worldToShip.transformPosition(corner)
                shipMin.min(corner)
                shipMax.max(corner)
            }
            scanBox(
                level, ship, worldMin, worldMax, tally,
                Mth.floor(shipMin.x), Mth.floor(shipMin.y), Mth.floor(shipMin.z),
                Mth.floor(shipMax.x), Mth.floor(shipMax.y), Mth.floor(shipMax.z),
            )
            if (tally.full) break
        }
        return tally.count
    }

    /** Scans a block box in the frame of [ship], or in the world frame when [ship] is null. */
    @Suppress("LongParameterList", "NestedBlockDepth", "CyclomaticComplexMethod") // One flat loop nest keeps the scan free of allocation.
    private fun scanBox(
        level: Level, ship: Ship?, worldMin: Vector3d, worldMax: Vector3d, tally: Tally,
        x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int,
    ) {
        val bookshelf = EKBlocks.SSELITH_BOOKSHELF.get()
        val center = Vector3d()
        for (cx in (x0 shr 4)..(x1 shr 4)) {
            for (cz in (z0 shr 4)..(z1 shr 4)) {
                // VS2 claims are whole chunks, thus one lookup for each chunk is sufficient.
                if (level.getLoadedShipManagingPos(cx, cz)?.id != ship?.id) continue
                val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
                for (sy in maxOf(y0 shr 4, chunk.minSection)..minOf(y1 shr 4, chunk.maxSection - 1)) {
                    val section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy))
                    if (section.hasOnlyAir() || !section.maybeHas(IS_BOOKSHELF)) continue
                    for (x in maxOf(x0, cx shl 4)..minOf(x1, (cx shl 4) + 15)) {
                        for (y in maxOf(y0, sy shl 4)..minOf(y1, (sy shl 4) + 15)) {
                            for (z in maxOf(z0, cz shl 4)..minOf(z1, (cz shl 4) + 15)) {
                                if (!section.getBlockState(x and 15, y and 15, z and 15).`is`(bookshelf)) continue
                                center.set(x + 0.5, y + 0.5, z + 0.5)
                                if (ship != null) {
                                    ship.transform.shipToWorld.transformPosition(center)
                                    if (center.x < worldMin.x || center.x > worldMax.x) continue
                                    if (center.y < worldMin.y || center.y > worldMax.y) continue
                                    if (center.z < worldMin.z || center.z > worldMax.z) continue
                                }
                                tally.count++
                                tally.found?.add(Vec3(center.x, center.y, center.z))
                                if (tally.full) return
                            }
                        }
                    }
                }
            }
        }
    }
}
