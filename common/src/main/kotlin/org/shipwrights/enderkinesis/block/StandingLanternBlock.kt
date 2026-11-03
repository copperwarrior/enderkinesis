package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/** A lantern with no hanging form: it needs support from below and breaks without it. */
// Mojang marks these Block overrides deprecated to tell callers to use BlockState; overriding them is the intended use.
@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
open class StandingLanternBlock(properties: Properties, private val shape: VoxelShape) : Block(properties) {

    // canSupportCenter, as the vanilla lantern does, so walls, fences and chains hold it.
    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean =
        Block.canSupportCenter(level, pos.below(), Direction.UP)

    override fun updateShape(
        state: BlockState,
        direction: Direction,
        neighborState: BlockState,
        level: LevelAccessor,
        pos: BlockPos,
        neighborPos: BlockPos,
    ): BlockState =
        if (direction == Direction.DOWN && !canSurvive(state, level, pos)) Blocks.AIR.defaultBlockState()
        else super.updateShape(state, direction, neighborState, level, pos, neighborPos)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape = shape
}
