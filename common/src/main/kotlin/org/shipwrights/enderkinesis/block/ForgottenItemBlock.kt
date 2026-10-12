package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.ForgottenItemBlockEntity

class ForgottenItemBlock(properties: Properties) : Block(properties), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ForgottenItemBlockEntity(pos, state)

    @Deprecated("Deprecated in Java")
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    @Deprecated("Deprecated in Java")
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPE

    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val be = level.getBlockEntity(pos) as? ForgottenItemBlockEntity ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)
        if (player.isCreative && !held.isEmpty) {
            // PASS lets the held block item place an adjacent block.
            if (held.`is`(asItem())) return InteractionResult.PASS
            if (be.item.isEmpty) {
                if (!level.isClientSide) {
                    be.put(held.copy(), player.yRot)
                    level.sendBlockUpdated(pos, state, state, UPDATE_CLIENTS)
                }
                return InteractionResult.sidedSuccess(level.isClientSide)
            }
        }
        if (level.isClientSide) return InteractionResult.SUCCESS
        // Take the stack before removal, or onRemove drops a second copy.
        val stack = be.take()
        if (!stack.isEmpty) player.inventory.placeItemBackInInventory(stack)
        level.removeBlock(pos, false)
        return InteractionResult.CONSUME
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block)) {
            val stack = (level.getBlockEntity(pos) as? ForgottenItemBlockEntity)?.take() ?: ItemStack.EMPTY
            if (!stack.isEmpty) {
                Containers.dropItemStack(level, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), stack)
            }
        }
        super.onRemove(state, level, pos, newState, moved)
    }

    companion object {
        private val SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0)
    }
}
