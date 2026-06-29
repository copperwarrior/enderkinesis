package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.AbstractCandleBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/** Single-only candle. Bypasses [net.minecraft.world.level.block.CandleBlock] (which
 *  declares the `candles` 1..4 property) and extends [AbstractCandleBlock] directly,
 *  so the state machine is just `lit × waterlogged` — no missing-model warnings for
 *  candle counts that never exist. */
class HeartCandleBlock(properties: Properties) : AbstractCandleBlock(properties), SimpleWaterloggedBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(LIT, false)
                .setValue(WATERLOGGED, false)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(LIT, WATERLOGGED)
    }

    /** Stops vanilla from stacking a second candle into this block;
     *  the candle item falls through to normal adjacent-face placement instead. */
    override fun canBeReplaced(state: BlockState, context: BlockPlaceContext): Boolean = false

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val waterlogged = context.level.getFluidState(context.clickedPos).type === Fluids.WATER
        return defaultBlockState().setValue(WATERLOGGED, waterlogged)
    }

    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos)
    }

    override fun getFluidState(state: BlockState): FluidState =
        if (state.getValue(WATERLOGGED)) Fluids.WATER.getSource(false) else super.getFluidState(state)

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean =
        Block.canSupportCenter(level, pos.below(), Direction.UP)

    override fun canBeLit(state: BlockState): Boolean =
        !state.getValue(WATERLOGGED) && super.canBeLit(state)

    /** Y 14/16 lines up with the flame quad in the model. Vanilla's Y 0.5 default would
     *  float the flame mid-wax for the taller heart-candle silhouette. */
    override fun getParticleOffsets(state: BlockState): Iterable<Vec3> = FLAME_OFFSETS

    /** Custom shape unions wax base + wick pillar — vanilla's `ONE_AABB` only covers
     *  the wick, leaving the visible 6×6 base unhittable. */
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext,
    ): VoxelShape = SHAPE

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)
        if (level.isClientSide) return
        if (!oldState.`is`(this)) return
        val wasLit = oldState.getValue(LIT)
        val isLit = state.getValue(LIT)
        if (wasLit || !isLit) return
        val serverLevel = level as? ServerLevel ?: return
        if (!WohlonnogondoniaPortalRitual.checkPattern(serverLevel, pos)) return
        if (!WohlonnogondoniaPortalRitual.isBiomeValid(serverLevel, pos)) return
        val portalPos = pos.above(2)
        if (!WohlonnogondoniaPortalManager.addPortal(serverLevel, portalPos)) return
        WohlonnogondoniaSpreader.startPortalSeed(serverLevel, portalPos)
        WohlonnogondoniaTreeGrower.startTree(serverLevel, portalPos)
    }

    companion object {
        val LIT = BlockStateProperties.LIT
        val WATERLOGGED = BlockStateProperties.WATERLOGGED

        private val FLAME_OFFSETS: List<Vec3> = listOf(Vec3(0.5, 14.0 / 16.0, 0.5))

        private val SHAPE: VoxelShape = Shapes.or(
            Block.box(5.0, 0.0, 5.0, 11.0, 6.0, 11.0),
            Block.box(7.0, 6.0, 7.0, 9.0, 12.0, 9.0),
        )
    }
}
