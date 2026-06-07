package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.IronBarsBlock
import net.minecraft.world.level.block.PipeBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.Fluids
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * [IronBarsBlock] with one extra connection rule on top of vanilla's: an axis-aligned
 * [AncriteChainBlock] endcap counts as a sturdy face. Vanilla bars only attach to faces flagged
 * as `isFaceSturdy` (full-cube collision support on that face); chains never satisfy that, so
 * without this override the bars sit beside the chain with a visible gap. With it, ancrite bars
 * lock onto an ancrite chain only when the chain's axis points *at* the bars — touching the
 * chain's side at 90° still leaves the bars unattached.
 */
class AncriteBarsBlock(properties: BlockBehaviour.Properties) : IronBarsBlock(properties) {

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val level = context.level
        val pos = context.clickedPos
        val base = super.getStateForPlacement(context) ?: return null
        return base
            .setValue(PipeBlock.NORTH, shouldConnect(level, pos, Direction.NORTH))
            .setValue(PipeBlock.SOUTH, shouldConnect(level, pos, Direction.SOUTH))
            .setValue(PipeBlock.EAST,  shouldConnect(level, pos, Direction.EAST))
            .setValue(PipeBlock.WEST,  shouldConnect(level, pos, Direction.WEST))
    }

    override fun updateShape(
        state: BlockState, dir: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        // Mirror vanilla's water-tick scheduling (super does this in the non-horizontal branch).
        if (state.getValue(BlockStateProperties.WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
        }
        if (!dir.axis.isHorizontal) {
            return super.updateShape(state, dir, neighborState, level, pos, neighborPos)
        }
        val prop = PipeBlock.PROPERTY_BY_DIRECTION[dir] ?: return state
        return state.setValue(prop, shouldConnect(level, pos, dir))
    }

    private fun shouldConnect(level: BlockGetter, pos: BlockPos, dir: Direction): Boolean {
        val neighborPos = pos.relative(dir)
        val neighbor = level.getBlockState(neighborPos)
        // Custom rule: ancrite chains are "sturdy" along their axis. Connecting only when the
        // chain's axis matches the connection direction keeps bars from grabbing onto the side
        // of a chain that's just brushing past them.
        if (neighbor.`is`(EKBlocks.ANCRITE_CHAIN.get())
            && neighbor.getValue(BlockStateProperties.AXIS) == dir.axis
        ) {
            return true
        }
        // Otherwise, vanilla logic: attach if the neighbor's face toward us is sturdy, OR it's
        // another iron-bars-like block, OR it's in the walls tag.
        val sturdy = neighbor.isFaceSturdy(level, neighborPos, dir.opposite)
        return attachsTo(neighbor, sturdy)
    }
}
