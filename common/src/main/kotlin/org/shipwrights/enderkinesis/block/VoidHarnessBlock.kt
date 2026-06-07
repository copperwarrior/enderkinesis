package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.shipwrights.enderkinesis.blockentity.VoidHarnessBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Void Harness — a magenta-stained-glass block with an end crystal inside that, when redstone-
 * powered, applies an XZ-plane "magnet pulling its own car" force pulling whatever ship body it
 * affects toward the harness's world position.
 *
 *  - **On a ship**: pulls that ship toward the harness; the force vector in body-frame stays
 *    fixed (from COM → harness), so the ship gets a constant body-relative thrust.
 *  - **In the world**: pulls every ship within `PULL_RADIUS` blocks toward the harness's world
 *    position.
 *
 * Force application lives in [VoidHarnessBlockEntity.physTick]; this class is just the block +
 * `POWERED` blockstate plumbing.
 */
class VoidHarnessBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(POWER, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(POWER)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val base = super.getStateForPlacement(context) ?: defaultBlockState()
        return base.setValue(POWER, context.level.getBestNeighborSignal(context.clickedPos))
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        VoidHarnessBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.VOID_HARNESS.get()) return null
        return BlockEntityTicker { lvl, p, st, be ->
            (be as? VoidHarnessBlockEntity)?.serverTick(lvl as ServerLevel, p, st)
        }
    }

    /** Mirror the strongest neighbouring redstone signal into the [POWER] blockstate the instant
     *  it changes — the BER reads POWER for animation rate / brightness, the BE re-caches it
     *  for force scaling on the next server tick. */
    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        neighborBlock: Block, neighborPos: BlockPos, isMoving: Boolean,
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, isMoving)
        if (level.isClientSide) return
        val signal = level.getBestNeighborSignal(pos)
        if (state.getValue(POWER) != signal) {
            level.setBlock(pos, state.setValue(POWER, signal), Block.UPDATE_CLIENTS)
        }
    }

    companion object {
        /** Redstone power level (0–15) — the strongest neighbouring signal. Drives the force
         *  magnitude (BE) and the crystal's animation rate / brightness (BER). */
        val POWER = BlockStateProperties.POWER
    }
}
