package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.DraftingTableBlockEntity
import org.shipwrights.enderkinesis.construction.BuildJobs
import org.shipwrights.enderkinesis.construction.useDraftingTable
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.util.ShipMoves

class DraftingTableBlock(properties: Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    // The user-approved placeholder is the Scribe's Desk model, so the desk's bare shape matches it.
    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        DESK_SHAPES[state.getValue(FACING).get2DDataValue()]

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun isPathfindable(state: BlockState, level: BlockGetter, pos: BlockPos, type: PathComputationType): Boolean =
        false

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = DraftingTableBlockEntity(pos, state)

    // Block.use runs before Item.useOn, so a staff click with Clawhods selected reaches the table here.
    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        if (level is ServerLevel && player is ServerPlayer) level.useDraftingTable(pos, player, hand)
        return InteractionResult.CONSUME
    }

    // Only a player placement calls this, after the item's block entity data loads: a copy gets its own id.
    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (!level.isClientSide) (level.getBlockEntity(pos) as? DraftingTableBlockEntity)?.renewId()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block) && level is ServerLevel) {
            // VS2 removes each old block of an assembly or weld; that is a move, not a break.
            val moving = ShipMoves.isMovingBlocks(level)
            (level.getBlockEntity(pos) as? DraftingTableBlockEntity)?.let { BuildJobs.tableRemoved(level, pos, it.tableId, moving) }
        }
        super.onRemove(state, level, pos, newState, moved)
    }

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING

        // Lazy: the desk block is not registered when this class loads.
        private val DESK_SHAPES: Array<VoxelShape> by lazy {
            Array(4) { i ->
                EKBlocks.SCRIBES_DESK.get().defaultBlockState()
                    .setValue(ScribesDeskBlock.FACING, Direction.from2DDataValue(i))
                    .getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
            }
        }
    }
}
