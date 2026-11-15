package org.shipwrights.enderkinesis.entity

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ServerShip

object ConstriliarCargo {

    private const val DROP_SCAN_BUDGET = 100_000
    private const val SPAWNS_PER_TICK = 256
    private const val TICKS_PER_SECOND = 20.0

    /** [action] gets a reused cursor: call `immutable()` on any position it keeps. */
    internal inline fun forEachShipBlock(
        level: ServerLevel,
        ship: ServerShip,
        cellBudget: Int,
        action: (BlockPos, BlockState) -> Unit,
    ) {
        val aabb = ship.shipAABB ?: return
        var budget = cellBudget
        val cursor = BlockPos.MutableBlockPos()
        // vs-core ShipData sets the voxel box max one past the last block.
        for (y in aabb.minY() until aabb.maxY()) {
            for (x in aabb.minX() until aabb.maxX()) {
                for (z in aabb.minZ() until aabb.maxZ()) {
                    if (budget-- <= 0) return
                    cursor.set(x, y, z)
                    val state = level.getBlockState(cursor)
                    if (!state.isAir) action(cursor, state)
                }
            }
        }
    }

    private class Piece(val worldPos: Long, val state: BlockState, val vx: Double, val vy: Double, val vz: Double)

    // Game thread only.
    private val pending = HashMap<ResourceKey<Level>, ArrayDeque<Piece>>()
    private val spawnCursor = BlockPos.MutableBlockPos()

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(TickEvent.ServerLevelTick { level -> drain(level, SPAWNS_PER_TICK) })
        // SERVER_STOPPING runs before the final save, so the spawned blocks are saved as entities.
        LifecycleEvent.SERVER_STOPPING.register { server -> server.allLevels.forEach { drain(it, Int.MAX_VALUE) } }
        LifecycleEvent.SERVER_STOPPED.register { pending.clear() }
    }

    /**
     * Clears each block of [ship], but not the plate at [platformPos], and queues it to fall. Each piece keeps the
     * world position and body velocity of this tick, so the queue does not need the ship after this call.
     */
    fun dropShipCargo(level: ServerLevel, ship: ServerShip, platformPos: BlockPos) {
        check(level.server.isSameThread) { "dropShipCargo must run on the server thread" }
        val queue = pending.getOrPut(level.dimension()) { ArrayDeque() }
        val shipToWorld = ship.shipToWorld
        val origin = ship.transform.positionInWorld
        val v = ship.velocity
        val w = ship.angularVelocity
        val world = Vector3d()
        forEachShipBlock(level, ship, DROP_SCAN_BUDGET) { pos, state ->
            if (pos == platformPos) return@forEachShipBlock
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
            shipToWorld.transformPosition(world.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))
            val rx = world.x - origin.x()
            val ry = world.y - origin.y()
            val rz = world.z - origin.z()
            queue.addLast(
                Piece(
                    BlockPos.asLong(Mth.floor(world.x), Mth.floor(world.y), Mth.floor(world.z)),
                    state,
                    (v.x() + w.y() * rz - w.z() * ry) / TICKS_PER_SECOND,
                    (v.y() + w.z() * rx - w.x() * rz) / TICKS_PER_SECOND,
                    (v.z() + w.x() * ry - w.y() * rx) / TICKS_PER_SECOND,
                ),
            )
        }
    }

    private fun drain(level: ServerLevel, budget: Int) {
        val queue = pending[level.dimension()] ?: return
        var left = budget
        while (left-- > 0) {
            val piece = queue.removeFirstOrNull() ?: break
            spawnCursor.set(piece.worldPos)
            if (level.getBlockState(spawnCursor).isAir) {
                // push, not setDeltaMovement: push marks hasImpulse, so the client gets the velocity next tick.
                FallingBlockEntity.fall(level, spawnCursor, piece.state).push(piece.vx, piece.vy, piece.vz)
            } else {
                Block.popResource(level, spawnCursor, ItemStack(piece.state.block))
            }
        }
        if (queue.isEmpty()) pending.remove(level.dimension())
    }
}
