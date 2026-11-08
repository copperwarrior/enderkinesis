package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.UlderBeamAnchorBlockEntity
import org.shipwrights.enderkinesis.item.UlderBeamAnchorItem
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.util.ShipMoves

class UlderBeamAnchorBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    /** FACING points toward the support block, as on [OrbOfLinkingBlock]. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(FACING, context.clickedFace.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        UlderBeamAnchorBlockEntity(pos, state)

    // ShipMoves finds its listeners through the ticker list, so the anchor needs a ticker.
    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? =
        if (level.isClientSide || type != EKBlockEntities.ULDER_BEAM_ANCHOR.get()) null
        else BlockEntityTicker { _, _, _, _ -> }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = when (state.getValue(FACING)) {
        Direction.DOWN -> SHAPE_DOWN
        Direction.UP -> SHAPE_UP
        Direction.NORTH -> SHAPE_NORTH
        Direction.SOUTH -> SHAPE_SOUTH
        Direction.EAST -> SHAPE_EAST
        Direction.WEST -> SHAPE_WEST
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level !is ServerLevel) return
        val item = stack.item as? UlderBeamAnchorItem ?: return
        item.bind(level, pos, stack, placer as? Player)
    }

    /** A selected Ulder Beam Anchor item claims this anchor for its orb. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val stack = player.getItemInHand(hand)
        val item = stack.item as? UlderBeamAnchorItem ?: return InteractionResult.PASS
        if (!item.hasSelection(stack)) return InteractionResult.PASS
        if (level !is ServerLevel) return InteractionResult.SUCCESS
        item.bind(level, pos, stack, player)
        return InteractionResult.CONSUME
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        if (!state.`is`(newState.block) && level is ServerLevel && !ShipMoves.isMovingBlocks(level)) {
            (level.getBlockEntity(pos) as? UlderBeamAnchorBlockEntity)?.release(level)
        }
        super.onRemove(state, level, pos, newState, isMoving)
    }

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.FACING

        // The model's plate and anchor cube, turned onto each face.
        private val SHAPE_DOWN: VoxelShape = box(6.0, 0.0, 6.0, 10.0, 2.0, 10.0)
        private val SHAPE_UP: VoxelShape = box(6.0, 14.0, 6.0, 10.0, 16.0, 10.0)
        private val SHAPE_NORTH: VoxelShape = box(6.0, 6.0, 0.0, 10.0, 10.0, 2.0)
        private val SHAPE_SOUTH: VoxelShape = box(6.0, 6.0, 14.0, 10.0, 10.0, 16.0)
        private val SHAPE_EAST: VoxelShape = box(14.0, 6.0, 6.0, 16.0, 10.0, 10.0)
        private val SHAPE_WEST: VoxelShape = box(0.0, 6.0, 6.0, 2.0, 10.0, 10.0)
    }
}
