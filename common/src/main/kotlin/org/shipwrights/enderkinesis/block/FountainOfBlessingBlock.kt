package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.FountainOfBlessingBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/** The spring under the Mother Heart, at the pool surface. The block model is empty; the BER draws the fountain. */
class FountainOfBlessingBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock, SimpleWaterloggedBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(WATERLOGGED, true))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(WATERLOGGED)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = FountainOfBlessingBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (type != EKBlockEntities.FOUNTAIN_OF_BLESSING.get()) return null
        return if (level.isClientSide) {
            BlockEntityTicker { lvl, _, _, be -> (be as? FountainOfBlessingBlockEntity)?.clientTick(lvl) }
        } else {
            BlockEntityTicker { lvl, _, _, be -> (be as? FountainOfBlessingBlockEntity)?.serverTick(lvl as ServerLevel) }
        }
    }

    // Block, unlike BaseEntityBlock, does not pass block events to the block entity.
    override fun triggerEvent(state: BlockState, level: Level, pos: BlockPos, id: Int, param: Int): Boolean {
        super.triggerEvent(state, level, pos, id, param)
        return level.getBlockEntity(pos)?.triggerEvent(id, param) ?: false
    }

    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val be = level.getBlockEntity(pos) as? FountainOfBlessingBlockEntity ?: return InteractionResult.PASS
        if (level.isClientSide) return if (be.canInteract(player.getItemInHand(hand))) InteractionResult.SUCCESS else InteractionResult.PASS
        return be.interact(level as ServerLevel, player, hand)
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? FountainOfBlessingBlockEntity)?.let {
                Containers.dropItemStack(level, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, it.takeStored())
            }
        }
        @Suppress("DEPRECATION")
        super.onRemove(state, level, pos, newState, isMoving)
    }

    @Deprecated("Deprecated in Java")
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    @Deprecated("Deprecated in Java")
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        Shapes.empty()

    @Deprecated("Deprecated in Java")
    override fun getFluidState(state: BlockState): FluidState =
        if (state.getValue(WATERLOGGED)) Fluids.WATER.getSource(false) else super.getFluidState(state)

    @Deprecated("Deprecated in Java")
    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos)
    }

    companion object {
        val WATERLOGGED = BlockStateProperties.WATERLOGGED
        /** Around the floating item at the water surface, so the player can target it. */
        private val SHAPE: VoxelShape = box(4.0, 10.0, 4.0, 12.0, 16.0, 12.0)
    }
}
