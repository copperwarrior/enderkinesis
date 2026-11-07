package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUtils
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds
import dev.architectury.registry.registries.RegistrySupplier

class IchaericInkJarBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(BOTTLES, 0).setValue(OPEN, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BOTTLES, OPEN)
    }

    @Deprecated("Deprecated in Java")
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPE

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(OPEN, isUnderRetort(context.level, context.clickedPos))

    @Deprecated("Deprecated in Java")
    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState = if (direction == Direction.UP) state.setValue(OPEN, isUnderRetort(level, pos)) else state

    // Catches a column change that neither the jar nor the retort touches directly.
    @Deprecated("Deprecated in Java")
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        refreshOpen(level, pos, state)
    }

    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val held = player.getItemInHand(hand)
        val bottles = state.getValue(BOTTLES)
        return when {
            held.`is`(EKItems.ICHAERIC_INK_BOTTLE.get()) && bottles < MAX_BOTTLES -> transfer(
                state, level, pos, player, hand, bottles + 1, Items.GLASS_BOTTLE, EKSounds.ICHAERIC_INK_JAR_POUR,
            )
            held.`is`(Items.GLASS_BOTTLE) && bottles > 0 -> transfer(
                state, level, pos, player, hand, bottles - 1, EKItems.ICHAERIC_INK_BOTTLE.get(),
                EKSounds.ICHAERIC_INK_BOTTLE_FILL,
            )
            else -> InteractionResult.PASS
        }
    }

    @Suppress("LongParameterList") // A parameter object would only move the list.
    private fun transfer(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand,
        bottles: Int, returned: Item, sound: RegistrySupplier<SoundEvent>,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        level.setBlock(pos, state.setValue(BOTTLES, bottles), UPDATE_ALL)
        player.setItemInHand(hand, ItemUtils.createFilledResult(player.getItemInHand(hand), player, ItemStack(returned)))
        level.playSound(null, pos, sound.get(), SoundSource.BLOCKS, 1f, 0.9f + 0.04f * bottles)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        player.displayClientMessage(Component.translatable(CONTENTS_KEY, bottles, MAX_BOTTLES), true)
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(
        stack: ItemStack, level: BlockGetter?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        tooltip.add(Component.translatable(CONTENTS_KEY, bottlesOf(stack), MAX_BOTTLES))
    }

    override fun getCloneItemStack(level: BlockGetter, pos: BlockPos, state: BlockState): ItemStack =
        withBottles(super.getCloneItemStack(level, pos, state), state.getValue(BOTTLES))

    companion object {
        const val MAX_BOTTLES = 6
        const val CONTENTS_KEY = "message.enderkinesis.ichaeric_ink_jar.contents"
        val BOTTLES: IntegerProperty = IntegerProperty.create("bottles", 0, MAX_BOTTLES)
        val OPEN: BooleanProperty = BooleanProperty.create("open")

        fun refreshOpen(level: Level, pos: BlockPos, state: BlockState) {
            val open = isUnderRetort(level, pos)
            if (state.getValue(OPEN) != open) level.setBlock(pos, state.setValue(OPEN, open), Block.UPDATE_ALL)
        }

        // The same column rule as the retort drip, so the jar opens only where drips land.
        private fun isUnderRetort(level: BlockGetter, pos: BlockPos): Boolean {
            val cursor = pos.mutable()
            repeat(InegricRetortBlock.MAX_DRIP_DISTANCE) {
                val above = level.getBlockState(cursor.move(Direction.UP))
                if (!above.isAir) return above.`is`(EKBlocks.INEGRIC_RETORT.get())
            }
            return false
        }

        /** Marks a jar [stack] as holding [bottles], as the placed block will. */
        fun withBottles(stack: ItemStack, bottles: Int): ItemStack {
            if (bottles > 0) stack.getOrCreateTagElement(BlockItem.BLOCK_STATE_TAG).putString(BOTTLES.name, bottles.toString())
            return stack
        }

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
