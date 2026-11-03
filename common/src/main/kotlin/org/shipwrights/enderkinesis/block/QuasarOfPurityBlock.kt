package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.QuasarOfPurityBlockEntity

/** A ship that holds one of these gets no new Ygann barnacles. FACING points away from the support, end-rod style. */
class QuasarOfPurityBlock(props: Properties) : Block(props), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.clickedFace)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext,
    ): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        QuasarOfPurityBlockEntity(pos, state)

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.FACING

        private val SHAPES: Map<Direction, VoxelShape> = mapOf(
            Direction.UP to box(5.0, 0.0, 5.0, 11.0, 11.0, 11.0),
            Direction.DOWN to box(5.0, 5.0, 5.0, 11.0, 16.0, 11.0),
            Direction.NORTH to box(5.0, 5.0, 5.0, 11.0, 11.0, 16.0),
            Direction.SOUTH to box(5.0, 5.0, 0.0, 11.0, 11.0, 11.0),
            Direction.EAST to box(0.0, 5.0, 5.0, 11.0, 11.0, 11.0),
            Direction.WEST to box(5.0, 5.0, 5.0, 16.0, 11.0, 11.0),
        )
    }
}
