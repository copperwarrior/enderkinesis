package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids

/** Two-tall emergent reed; the lower half can stand in shallow water. */
class WohlonReedBlock(properties: Properties) : DoublePlantBlock(properties), SimpleWaterloggedBlock {
    init {
        registerDefaultState(defaultBlockState().setValue(WATERLOGGED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(WATERLOGGED)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        super.getStateForPlacement(context)?.setValue(
            WATERLOGGED, context.level.getFluidState(context.clickedPos).type == Fluids.WATER,
        )

    @Deprecated("Deprecated in Java")
    override fun getFluidState(state: BlockState): FluidState =
        if (state.getValue(WATERLOGGED)) Fluids.WATER.getSource(false) else super.getFluidState(state)

    @Deprecated("Deprecated in Java")
    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos)
    }

    companion object {
        val WATERLOGGED = BlockStateProperties.WATERLOGGED
    }
}
