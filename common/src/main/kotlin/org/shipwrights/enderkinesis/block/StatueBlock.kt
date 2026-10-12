package org.shipwrights.enderkinesis.block

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.StatueBlockEntity

/**
 * One instance per [StatueKind], two blocks tall. The lower half holds the block entity that
 * draws the figure. A ray tests only the block in each cell, so the upper cell needs its own block.
 */
class StatueBlock(val kind: StatueKind, properties: BlockBehaviour.Properties) :
    HorizontalDirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, HALF)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val pos = context.clickedPos
        val level = context.level
        val hasRoom = pos.y < level.maxBuildHeight - 1 && level.getBlockState(pos.above()).canBeReplaced(context)
        return if (hasRoom) defaultBlockState().setValue(FACING, context.horizontalDirection.opposite) else null
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), UPDATE_ALL)
    }

    @Deprecated("Deprecated in Java")
    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        val half = state.getValue(HALF)
        val towardOther = if (half == DoubleBlockHalf.LOWER) Direction.UP else Direction.DOWN
        val otherIsGone = direction == towardOther &&
            !(neighborState.`is`(this) && neighborState.getValue(HALF) != half)
        return if (otherIsGone) {
            Blocks.AIR.defaultBlockState()
        } else {
            @Suppress("DEPRECATION")
            super.updateShape(state, direction, neighborState, level, pos, neighborPos)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    @Deprecated("Deprecated in Java")
    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) StatueBlockEntity(pos, state) else null

    @Deprecated("Deprecated in Java")
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = StatueShapes.of(kind, state.getValue(FACING), state.getValue(HALF))

    @Deprecated("Deprecated in Java")
    override fun getCollisionShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = StatueShapes.of(kind, state.getValue(FACING), state.getValue(HALF))

    @Deprecated("Deprecated in Java")
    override fun getOcclusionShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape =
        Shapes.empty()

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player) {
        if (level is ServerLevel) {
            val isUpper = state.getValue(HALF) == DoubleBlockHalf.UPPER
            val lowerPos = if (isUpper) pos.below() else pos
            if (kind == StatueKind.OWL && OwlStatueEscape.escapes(player)) {
                OwlStatueEscape.escape(level, lowerPos, state.setValue(HALF, DoubleBlockHalf.LOWER), player as? ServerPlayer)
            }
            if (isUpper) breakLowerHalf(level, lowerPos, player)
        }
        super.playerWillDestroy(level, pos, state, player)
    }

    // Only the lower half has loot. Without this, updateShape drops it with no tool:
    // that ignores the correct-tool rule and the Silk Touch test of the owl.
    private fun breakLowerHalf(level: ServerLevel, lowerPos: BlockPos, player: Player) {
        val lower = level.getBlockState(lowerPos)
        if (!lower.`is`(this) || lower.getValue(HALF) != DoubleBlockHalf.LOWER) return
        if (!player.isCreative && player.hasCorrectToolForDrops(lower)) {
            dropResources(lower, level, lowerPos, level.getBlockEntity(lowerPos), player, player.mainHandItem)
        }
        level.setBlock(lowerPos, Blocks.AIR.defaultBlockState(), UPDATE_ALL or UPDATE_SUPPRESS_DROPS)
        level.levelEvent(player, LevelEvent.PARTICLES_DESTROY_BLOCK, lowerPos, getId(lower))
    }

    // The block is air at this point, so the facing is lost.
    override fun wasExploded(level: Level, pos: BlockPos, explosion: Explosion) {
        if (kind == StatueKind.OWL && level is ServerLevel) {
            OwlStatueEscape.escape(level, pos, defaultBlockState(), null)
        }
        super.wasExploded(level, pos, explosion)
    }

    override fun appendHoverText(
        stack: ItemStack, level: BlockGetter?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        tooltip.add(Component.translatable(kind.tooltipKey).withStyle(ChatFormatting.GRAY))
    }

    companion object {
        val HALF: EnumProperty<DoubleBlockHalf> = BlockStateProperties.DOUBLE_BLOCK_HALF

        /** Places the two halves. [lower] gives the facing. */
        fun place(level: Level, pos: BlockPos, lower: BlockState) {
            level.setBlock(pos, lower.setValue(HALF, DoubleBlockHalf.LOWER), UPDATE_ALL)
            level.setBlock(pos.above(), lower.setValue(HALF, DoubleBlockHalf.UPPER), UPDATE_ALL)
        }
    }
}
