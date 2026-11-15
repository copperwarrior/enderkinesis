package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import org.shipwrights.enderkinesis.registry.EKBlocks

/** Moss drapes over each side face of the support block that stays visible. */
class WohlonMossCarpetBlock(properties: BlockBehaviour.Properties) : CarpetBlock(properties) {

    init {
        registerDefaultState(
            stateDefinition.any().setValue(NORTH, false).setValue(EAST, false)
                .setValue(SOUTH, false).setValue(WEST, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(NORTH, EAST, SOUTH, WEST)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        withDrapes(defaultBlockState(), context.level, context.clickedPos)

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        val updated = super.updateShape(state, direction, neighborState, level, pos, neighborPos)
        return if (updated.`is`(this)) withDrapes(updated, level, pos) else updated
    }

    companion object {
        val NORTH: BooleanProperty = BlockStateProperties.NORTH
        val EAST: BooleanProperty = BlockStateProperties.EAST
        val SOUTH: BooleanProperty = BlockStateProperties.SOUTH
        val WEST: BooleanProperty = BlockStateProperties.WEST

        private val HORIZONTAL = arrayOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

        fun drapeProperty(dir: Direction): BooleanProperty = when (dir) {
            Direction.NORTH -> NORTH
            Direction.EAST -> EAST
            Direction.SOUTH -> SOUTH
            Direction.WEST -> WEST
            else -> throw IllegalArgumentException("No drape for $dir")
        }

        fun withDrapes(state: BlockState, level: BlockGetter, pos: BlockPos): BlockState {
            val supportPos = pos.below()
            val support = level.getBlockState(supportPos)
            val beside = BlockPos.MutableBlockPos()
            var result = state
            for (dir in HORIZONTAL) {
                beside.setWithOffset(supportPos, dir)
                val neighbour = level.getBlockState(beside)
                val hidden = neighbour.canOcclude() &&
                    Block.isFaceFull(neighbour.getOcclusionShape(level, beside), dir.opposite)
                result = result.setValue(drapeProperty(dir), !hidden && support.isFaceSturdy(level, supportPos, dir))
            }
            return result
        }

        /**
         * The block beside the support is diagonal to the carpet, so vanilla sends the carpet no update.
         * Called from the indirect-neighbour hook for each block change at [changed].
         */
        fun refreshAround(level: LevelAccessor, changed: BlockPos) {
            val carpet = EKBlocks.WOHLON_MOSS_CARPET.get()
            val at = BlockPos.MutableBlockPos()
            for (dir in HORIZONTAL) {
                at.setWithOffset(changed, dir.stepX, 1, dir.stepZ)
                val state = level.getBlockState(at)
                if (!state.`is`(carpet)) continue
                val updated = withDrapes(state, level, at)
                if (updated != state) level.setBlock(at, updated, Block.UPDATE_CLIENTS)
            }
        }
    }
}

/**
 * Worldgen setBlock runs no shape updates, so this sets the drapes itself.
 * The post-process mark refreshes them when the chunk becomes full, after later features changed the ground.
 */
fun LevelAccessor.placeWohlonMossCarpet(pos: BlockPos, flags: Int): Boolean {
    val carpet = EKBlocks.WOHLON_MOSS_CARPET.get().defaultBlockState()
    if (!carpet.canSurvive(this, pos)) return false
    if (!setBlock(pos, WohlonMossCarpetBlock.withDrapes(carpet, this, pos), flags)) return false
    getChunk(pos).markPosForPostprocessing(pos)
    return true
}
