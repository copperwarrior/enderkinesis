package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/**
 * Deepslate-textured ladder used throughout the Sselith dimension. Climbs identically
 * to the vanilla ladder; the only behavioural difference is that it has no support
 * requirement — the chunk generator's archway shelves and stairwell ladders need to
 * survive in mid-air gaps without a sturdy face behind them (where vanilla would drop
 * the ladder the moment it tried to place).
 */
class SselithLadderBlock(properties: BlockBehaviour.Properties) : LadderBlock(properties) {

    /** Vanilla checks `canAttachTo(facing.opposite)` — i.e. a sturdy face behind the
     *  ladder. Returning true unconditionally lets the ladder exist on any face, with
     *  or without a backer block, which is what the procedural archway / stairwell
     *  ladder placement assumes. */
    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean = true

    /** Bypass vanilla's "if the block behind me disappeared, drop to AIR" branch —
     *  delegate straight to [net.minecraft.world.level.block.Block.updateShape] so the
     *  waterlogged-tick scheduling still fires but the no-support breakage doesn't. */
    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(
                pos,
                net.minecraft.world.level.material.Fluids.WATER,
                net.minecraft.world.level.material.Fluids.WATER.getTickDelay(level),
            )
        }
        return state
    }
}
