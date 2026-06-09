package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

class HeartCandleBlock(properties: Properties) : CandleBlock(properties) {

    /** Returning false stops vanilla from stacking a second candle into this block;
     *  the candle item falls through to normal adjacent-face placement instead. */
    override fun canBeReplaced(state: BlockState, context: BlockPlaceContext): Boolean = false

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

    /** Y 14/16 lines up with the flame quad in the model. Vanilla's Y 0.5 default would
     *  float the flame mid-wax for the taller heart-candle silhouette. */
    override fun getParticleOffsets(state: BlockState): Iterable<Vec3> = FLAME_OFFSETS

    /** Custom shape unions wax base + wick pillar — vanilla's `ONE_AABB` only covers
     *  the wick, leaving the visible 6×6 base unhittable. */
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext,
    ): VoxelShape = SHAPE

    companion object {
        private val FLAME_OFFSETS: List<Vec3> = listOf(Vec3(0.5, 14.0 / 16.0, 0.5))

        private val SHAPE: VoxelShape = Shapes.or(
            Block.box(5.0, 0.0, 5.0, 11.0, 6.0, 11.0),
            Block.box(7.0, 6.0, 7.0, 9.0, 12.0, 9.0),
        )
    }
}
