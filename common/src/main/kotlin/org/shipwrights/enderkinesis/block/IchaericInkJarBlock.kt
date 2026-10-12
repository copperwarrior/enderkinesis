package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUtils
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.registry.EKItems

class IchaericInkJarBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(BOTTLES, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BOTTLES)
    }

    @Deprecated("Deprecated in Java")
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPE

    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val held = player.getItemInHand(hand)
        val bottles = state.getValue(BOTTLES)
        return when {
            held.`is`(EKItems.ICHAERIC_INK_BOTTLE.get()) && bottles < MAX_BOTTLES ->
                transfer(state, level, pos, player, hand, bottles + 1, Items.GLASS_BOTTLE, SoundEvents.BOTTLE_EMPTY)
            held.`is`(Items.GLASS_BOTTLE) && bottles > 0 -> transfer(
                state, level, pos, player, hand, bottles - 1, EKItems.ICHAERIC_INK_BOTTLE.get(), SoundEvents.BOTTLE_FILL,
            )
            else -> InteractionResult.PASS
        }
    }

    @Suppress("LongParameterList") // A parameter object would only move the list.
    private fun transfer(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand,
        bottles: Int, returned: Item, sound: SoundEvent,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        level.setBlock(pos, state.setValue(BOTTLES, bottles), UPDATE_ALL)
        player.setItemInHand(hand, ItemUtils.createFilledResult(player.getItemInHand(hand), player, ItemStack(returned)))
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, 1f)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        player.displayClientMessage(Component.translatable(CONTENTS_KEY, bottles, MAX_BOTTLES), true)
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(
        stack: ItemStack, level: BlockGetter?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        tooltip.add(Component.translatable(CONTENTS_KEY, bottlesOf(stack), MAX_BOTTLES))
    }

    override fun getCloneItemStack(level: BlockGetter, pos: BlockPos, state: BlockState): ItemStack {
        val stack = super.getCloneItemStack(level, pos, state)
        val bottles = state.getValue(BOTTLES)
        if (bottles > 0) stack.getOrCreateTagElement(BlockItem.BLOCK_STATE_TAG).putString(BOTTLES.name, bottles.toString())
        return stack
    }

    companion object {
        const val MAX_BOTTLES = 6
        const val CONTENTS_KEY = "message.enderkinesis.ichaeric_ink_jar.contents"
        val BOTTLES: IntegerProperty = IntegerProperty.create("bottles", 0, MAX_BOTTLES)

        @JvmStatic
        fun bottlesOf(stack: ItemStack): Int =
            stack.tag?.getCompound(BlockItem.BLOCK_STATE_TAG)?.getString(BOTTLES.name)?.toIntOrNull()
                ?.coerceIn(0, MAX_BOTTLES) ?: 0

        private val SHAPE: VoxelShape = Shapes.or(
            box(3.0, 0.0, 3.0, 13.0, 11.0, 13.0),
            box(3.5, 11.0, 3.5, 12.5, 15.0, 12.5),
        )
    }
}
