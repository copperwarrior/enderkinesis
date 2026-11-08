package org.shipwrights.enderkinesis.util

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.mod.common.toWorldCoordinates

private const val PATH_STEP = 16.0
private const val MAX_PATH_STEPS = 64

/** Players whose clients hold the chunk of any of [points]. A point on a ship counts at its shipyard chunk and at its
 *  world chunk. */
fun ServerLevel.playersHolding(points: List<Vec3>): Collection<ServerPlayer> {
    val chunks = HashSet<ChunkPos>()
    for (point in points) {
        chunks += ChunkPos(BlockPos.containing(point))
        chunks += ChunkPos(BlockPos.containing(toWorldCoordinates(point)))
    }
    if (chunks.isEmpty()) return emptyList()
    if (chunks.size == 1) return chunkSource.chunkMap.getPlayers(chunks.first(), false)
    val out = LinkedHashSet<ServerPlayer>()
    for (chunk in chunks) out += chunkSource.chunkMap.getPlayers(chunk, false)
    return out
}

/** Adds points each 16 blocks from [start] to [end], both ends included, so every chunk the line crosses is near one. */
fun addPointsAlong(start: Vec3, end: Vec3, out: MutableList<Vec3>) {
    val steps = Math.ceil(start.distanceTo(end) / PATH_STEP).toInt().coerceIn(1, MAX_PATH_STEPS)
    for (i in 0..steps) out += start.lerp(end, i.toDouble() / steps)
}
