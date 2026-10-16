package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ServerShip

object ConstriliarCargo {

    private const val DROP_SCAN_BUDGET = 100_000

    internal inline fun forEachShipBlock(
        level: ServerLevel,
        ship: ServerShip,
        cellBudget: Int,
        action: (BlockPos, BlockState) -> Unit,
    ) {
        val aabb = ship.shipAABB ?: return
        var budget = cellBudget
        val cursor = BlockPos.MutableBlockPos()
        for (y in aabb.minY()..aabb.maxY()) {
            for (x in aabb.minX()..aabb.maxX()) {
                for (z in aabb.minZ()..aabb.maxZ()) {
                    if (budget-- <= 0) return
                    cursor.set(x, y, z)
                    val state = level.getBlockState(cursor)
                    if (!state.isAir) action(cursor.immutable(), state)
                }
            }
        }
    }

    /** Drops each block of [ship] into the world, but not the plate at [platformPos]. */
    fun dropShipCargo(level: ServerLevel, ship: ServerShip, platformPos: BlockPos) {
        forEachShipBlock(level, ship, DROP_SCAN_BUDGET) { pos, state ->
            if (pos != platformPos) popBlockOff(level, ship, pos, state)
        }
    }

    private fun popBlockOff(level: ServerLevel, ship: ServerShip, shipyardPos: BlockPos, state: BlockState) {
        level.setBlock(shipyardPos, Blocks.AIR.defaultBlockState(), 3)
        val world = ship.shipToWorld.transformPosition(
            Vector3d(shipyardPos.x + 0.5, shipyardPos.y + 0.5, shipyardPos.z + 0.5),
        )
        val worldPos = BlockPos.containing(world.x, world.y, world.z)
        if (level.getBlockState(worldPos).isAir) {
            FallingBlockEntity.fall(level, worldPos, state)
        } else {
            Block.popResource(level, worldPos, ItemStack(state.block))
        }
    }
}
