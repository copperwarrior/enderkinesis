package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.WoodStatueBlockEntity

/** Two blocks tall like [StatueBlock]; the lower half holds the inventory and draws the figure. No item places it. */
class WoodStatueBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HALF)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) WoodStatueBlockEntity(pos, state) else null

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape = SHAPE

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun use(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val statue = level.getBlockEntity(lowerPos(state, pos)) as? WoodStatueBlockEntity ?: return InteractionResult.PASS
        player.openMenu(statue)
        return InteractionResult.CONSUME
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        val half = state.getValue(HALF)
        val towardOther = if (half == DoubleBlockHalf.LOWER) Direction.UP else Direction.DOWN
        val otherIsGone = direction == towardOther && !(neighborState.`is`(this) && neighborState.getValue(HALF) != half)
        return if (otherIsGone) {
            Blocks.AIR.defaultBlockState()
        } else {
            @Suppress("DEPRECATION")
            super.updateShape(state, direction, neighborState, level, pos, neighborPos)
        }
    }

    // Every way the statue goes (break, explosion, the other half going) spills what it holds.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? WoodStatueBlockEntity)?.let { Containers.dropContents(level, pos, it) }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player) {
        if (level is ServerLevel && state.getValue(HALF) == DoubleBlockHalf.UPPER) breakLowerHalf(level, pos.below(), player)
        super.playerWillDestroy(level, pos, state, player)
    }

    // Same as StatueBlock: only the lower half has loot, so updateShape must not drop it without the player's tool.
    private fun breakLowerHalf(level: ServerLevel, lowerPos: BlockPos, player: Player) {
        val lower = level.getBlockState(lowerPos)
        if (!lower.`is`(this) || lower.getValue(HALF) != DoubleBlockHalf.LOWER) return
        if (!player.isCreative && player.hasCorrectToolForDrops(lower)) {
            dropResources(lower, level, lowerPos, level.getBlockEntity(lowerPos), player, player.mainHandItem)
        }
        level.setBlock(lowerPos, Blocks.AIR.defaultBlockState(), UPDATE_ALL or UPDATE_SUPPRESS_DROPS)
        level.levelEvent(player, LevelEvent.PARTICLES_DESTROY_BLOCK, lowerPos, getId(lower))
    }

    override fun getCloneItemStack(level: BlockGetter, pos: BlockPos, state: BlockState): ItemStack = ItemStack.EMPTY

    companion object {
        val HALF: EnumProperty<DoubleBlockHalf> = BlockStateProperties.DOUBLE_BLOCK_HALF

        private val SHAPE: VoxelShape = box(4.0, 0.0, 4.0, 12.0, 16.0, 12.0)

        fun lowerPos(state: BlockState, pos: BlockPos): BlockPos =
            if (state.getValue(HALF) == DoubleBlockHalf.UPPER) pos.below() else pos
    }
}
