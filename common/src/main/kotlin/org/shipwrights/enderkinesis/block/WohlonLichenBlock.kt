package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.GlowLichenBlock
import net.minecraft.world.level.block.MultifaceBlock
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
        if (!updated.`is`(this)) return updated
        val below = if (direction == Direction.DOWN) neighborState else level.getBlockState(pos.below())
        return updated.setValue(TIP, isTip(updated, below))
    }

    fun withTip(state: BlockState, level: BlockGetter, pos: BlockPos): BlockState =
        state.setValue(TIP, isTip(state, level.getBlockState(pos.below())))

    // Floor and ceiling lichen is not a vertical patch, so it keeps the full texture.
    private fun isTip(state: BlockState, below: BlockState): Boolean =
        !below.`is`(this) && Direction.Plane.HORIZONTAL.any { MultifaceBlock.hasFace(state, it) }

    companion object {
        val TIP: BooleanProperty = BooleanProperty.create("tip")
    }
}
