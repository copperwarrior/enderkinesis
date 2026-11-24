package org.shipwrights.enderkinesis.construction

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.ClawhodBlockEntity
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Runs [action] for each loaded Clawhod whose world position lies within [radius] of [centre] (world coordinates), in
 * the world and on ships. A ship's blocks sit in its shipyard, so each ship that reaches the sphere is searched by
 * its own chunks and the Clawhod cell is carried to the world by the ship's transform. Loaded chunks only; the chunk
 * count is capped, so the work is bounded.
 */
internal fun ServerLevel.forEachClawhodNear(centre: Vec3, radius: Double, action: (ClawhodBlockEntity) -> Unit) {
    val radiusSq = radius * radius
    val minCx = Math.floorDiv(Math.floor(centre.x - radius).toInt(), CHUNK)
    val maxCx = Math.floorDiv(Math.floor(centre.x + radius).toInt(), CHUNK)
    val minCz = Math.floorDiv(Math.floor(centre.z - radius).toInt(), CHUNK)
    val maxCz = Math.floorDiv(Math.floor(centre.z + radius).toInt(), CHUNK)
    for (cx in minCx..minOf(maxCx, minCx + MAX_CHUNK_SPAN)) {
        for (cz in minCz..minOf(maxCz, minCz + MAX_CHUNK_SPAN)) {
            val chunk = chunkSource.getChunkNow(cx, cz) ?: continue
            for (be in chunk.blockEntities.values) {
                if (be !is ClawhodBlockEntity || be.isRemoved) continue
                val p = be.blockPos
                val dx = p.x + HALF - centre.x
                val dy = p.y + HALF - centre.y
                val dz = p.z + HALF - centre.z
                if (dx * dx + dy * dy + dz * dz <= radiusSq) action(be)
            }
        }
    }
    val dimension = dimensionId
    val world = Vector3d()
    for (ship in shipObjectWorld.loadedShips) {
        if (ship.chunkClaimDimension != dimension) continue
        val reach = ship.worldAABB
        if (reach.minX() > centre.x + radius || reach.maxX() < centre.x - radius ||
            reach.minY() > centre.y + radius || reach.maxY() < centre.y - radius ||
            reach.minZ() > centre.z + radius || reach.maxZ() < centre.z - radius
        ) {
            continue
        }
        val box = ship.shipAABB ?: continue
        val toWorld = ship.shipToWorld
        for (cx in (box.minX() shr 4)..minOf((box.maxX() - 1) shr 4, (box.minX() shr 4) + MAX_CHUNK_SPAN)) {
            for (cz in (box.minZ() shr 4)..minOf((box.maxZ() - 1) shr 4, (box.minZ() shr 4) + MAX_CHUNK_SPAN)) {
                val chunk = chunkSource.getChunkNow(cx, cz) ?: continue
                for (be in chunk.blockEntities.values) {
                    if (be !is ClawhodBlockEntity || be.isRemoved) continue
                    val p = be.blockPos
                    toWorld.transformPosition(world.set(p.x + HALF, p.y + HALF, p.z + HALF))
                    if (world.distanceSquared(centre.x, centre.y, centre.z) <= radiusSq) action(be)
                }
            }
        }
    }
}

private const val CHUNK = 16
private const val HALF = 0.5
private const val MAX_CHUNK_SPAN = 32
