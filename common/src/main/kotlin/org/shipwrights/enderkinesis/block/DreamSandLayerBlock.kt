package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

// Vanilla `SnowLayerBlock` is not waterloggable, melts in light, and has a collision one layer below its top.
class DreamSandLayerBlock(properties: BlockBehaviour.Properties) : Block(properties), SimpleWaterloggedBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(LAYERS, 1).setValue(WATERLOGGED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(LAYERS, WATERLOGGED)
    }

    @Deprecated("Deprecated in Java")
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext,
    ): VoxelShape = SHAPES[state.getValue(LAYERS)]

    @Deprecated("Deprecated in Java")
    override fun useShapeForLightOcclusion(state: BlockState): Boolean = true

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
        const val MAX_LAYERS: Int = 8
        val LAYERS: IntegerProperty = BlockStateProperties.LAYERS
        val WATERLOGGED: BooleanProperty = BlockStateProperties.WATERLOGGED

        private val SHAPES: Array<VoxelShape> = Array(MAX_LAYERS + 1) { layers ->
            box(0.0, 0.0, 0.0, 16.0, layers * 2.0, 16.0)
        }
    }
}
