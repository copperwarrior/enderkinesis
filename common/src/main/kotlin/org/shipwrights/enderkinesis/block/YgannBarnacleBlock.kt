package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity

/**
 * Ygann Barnacle — a small pip that latches onto hulls travelling the Ygann Abyss
 * ([org.shipwrights.enderkinesis.dimension.YgannBarnacleSpreader]) and grows creeping
 * tentacles over the surface. Only this block is real/breakable; the tentacles are
 * render-only projections ([org.shipwrights.enderkinesis.client.YgannBarnacleRenderer]).
 * FACING points AWAY from the supporting face, button-style.
 */
class YgannBarnacleBlock(props: Properties) : Block(props), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext,
    ): VoxelShape {
        // Outline/selection shape scales with the centre node's current size.
        val be = level.getBlockEntity(pos) as? YgannBarnacleBlockEntity ?: return SHAPES.getValue(state.getValue(FACING))
        val now = be.level?.gameTime ?: 0L
        val hw = be.nodeHalfWidthPx(now, 0f)
        val h = be.nodeHeightPx(now, 0f)
        return nodeShape(state.getValue(FACING), hw, h)
    }

    /** Cube of half-width [hwPx] and height [hPx] (px), proud of the [facing] face. */
    private fun nodeShape(facing: Direction, hwPx: Double, hPx: Double): VoxelShape {
        val lo = 8.0 - hwPx
        val hi = 8.0 + hwPx
        return when (facing) {
            Direction.UP -> box(lo, 0.0, lo, hi, hPx, hi)
            Direction.DOWN -> box(lo, 16.0 - hPx, lo, hi, 16.0, hi)
            Direction.NORTH -> box(lo, lo, 16.0 - hPx, hi, hi, 16.0)
            Direction.SOUTH -> box(lo, lo, 0.0, hi, hi, hPx)
            Direction.WEST -> box(16.0 - hPx, lo, lo, 16.0, hi, hi)
            Direction.EAST -> box(0.0, lo, lo, hPx, hi, hi)
        }
    }

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean {
        val facing = state.getValue(FACING)
        val support = pos.relative(facing.opposite)
        return level.getBlockState(support).isFaceSturdy(level, support, facing)
    }

    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        if (direction == state.getValue(FACING).opposite && !state.canSurvive(level, pos)) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos)
    }

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, old: BlockState, moved: Boolean) {
        super.onPlace(state, level, pos, old, moved)
        (level.getBlockEntity(pos) as? YgannBarnacleBlockEntity)?.initBorn(level)
    }

    // Each removal starts the shrivel effect: a player break, lost support, an explosion or a
    // command. The block entity still exists here; `super.onRemove` removes it.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (level is net.minecraft.server.level.ServerLevel && !moved && !state.`is`(newState.block)) {
            val be = level.getBlockEntity(pos) as? YgannBarnacleBlockEntity
            if (be != null) {
                org.shipwrights.enderkinesis.barnacle.YgannBarnacleDeathNetwork.broadcastDeath(
                    level, pos, be.seed, state.getValue(FACING), be.growthPx(level.gameTime, 0f),
                )
            }
        }
        @Suppress("DEPRECATION")
        super.onRemove(state, level, pos, newState, moved)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        YgannBarnacleBlockEntity(pos, state)

    @Suppress("UNCHECKED_CAST")
    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
    ): net.minecraft.world.level.block.entity.BlockEntityTicker<T>? {
        if (type != org.shipwrights.enderkinesis.registry.EKBlockEntities.YGANN_BARNACLE.get()) return null
        val ticker = net.minecraft.world.level.block.entity.BlockEntityTicker<YgannBarnacleBlockEntity> {
            lvl, pos, _, be ->
            be.tick(lvl, pos)
        }
        return ticker as net.minecraft.world.level.block.entity.BlockEntityTicker<T>
    }

    override fun isPathfindable(
        state: BlockState, level: BlockGetter, pos: BlockPos, type: PathComputationType,
    ): Boolean = false

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.FACING

        /** 4×4 px pip, 2.5 px proud of the supporting face. */
        private val SHAPES = mapOf(
            Direction.UP to box(6.0, 0.0, 6.0, 10.0, 2.5, 10.0),
            Direction.DOWN to box(6.0, 13.5, 6.0, 10.0, 16.0, 10.0),
            Direction.NORTH to box(6.0, 6.0, 13.5, 10.0, 10.0, 16.0),
            Direction.SOUTH to box(6.0, 6.0, 0.0, 10.0, 10.0, 2.5),
            Direction.WEST to box(13.5, 6.0, 6.0, 16.0, 10.0, 10.0),
            Direction.EAST to box(0.0, 6.0, 6.0, 2.5, 10.0, 10.0),
        )

        private fun <K, V> Map<K, V>.getValue(k: K): V = this[k] ?: error("no shape for $k")
    }
}
