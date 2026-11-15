package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.spell.SpellNetwork

private val CREEP_DIRECTIONS: Array<Direction> = Direction.values()
// Server thread only.
private val creepNeighbour = BlockPos.MutableBlockPos()

/** Starts the client mud creep where [old] at [pos] just became Wohlon mud. Call after the setBlock. */
fun ServerLevel.queueMudCreep(pos: BlockPos, old: BlockState) {
    if (mudCreepSeen(pos)) SpellNetwork.queueMudCreep(this, pos, old)
}

/** Most conversions are buried stone and dirt; nobody sees their creep, so it is not sent. Never loads a chunk. */
private fun ServerLevel.mudCreepSeen(pos: BlockPos): Boolean {
    for (dir in CREEP_DIRECTIONS) {
        creepNeighbour.setWithOffset(pos, dir)
        val chunk = chunkSource.getChunkNow(creepNeighbour.x shr 4, creepNeighbour.z shr 4) ?: continue
        if (!chunk.getBlockState(creepNeighbour).isSolidRender(this, creepNeighbour)) return true
    }
    return false
}
