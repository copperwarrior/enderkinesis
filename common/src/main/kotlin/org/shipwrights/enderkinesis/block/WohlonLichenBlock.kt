package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.GlowLichenBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty

/** Wall lichen. [TIP] marks the lowest block of a vertical patch, which renders a ragged bottom fringe. */
class WohlonLichenBlock(properties: Properties) : GlowLichenBlock(properties) {
    init {
        registerDefaultState(defaultBlockState().setValue(TIP, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(TIP)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        super.getStateForPlacement(context)?.let { withTip(it, context.level, context.clickedPos) }

    // The bonemeal spreader places through this overload.
    override fun getStateForPlacement(
        current: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction,
    ): BlockState? = super.getStateForPlacement(current, level, pos, direction)?.let { withTip(it, level, pos) }

    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        val updated = super.updateShape(state, direction, neighborState, level, pos, neighborPos)
        if (direction != Direction.DOWN || !updated.`is`(this)) return updated
        return updated.setValue(TIP, !neighborState.`is`(this))
    }

    fun withTip(state: BlockState, level: BlockGetter, pos: BlockPos): BlockState =
        state.setValue(TIP, !level.getBlockState(pos.below()).`is`(this))

    companion object {
        val TIP: BooleanProperty = BooleanProperty.create("tip")
    }
}
