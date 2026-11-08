package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUtils
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult
import org.shipwrights.enderkinesis.blockentity.OarscribeBlockEntity
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds

/** Holds one Ichaeric Ink jar and pours it into Oarscribes on its five closed sides. The open side faces [FACING]. */
class JarReceptacleBlock(properties: Properties) : HorizontalDirectionalBlock(properties) {

    init {
        registerDefaultState(
            stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(JAR, false).setValue(BOTTLES, 0),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, JAR, BOTTLES)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val held = player.getItemInHand(hand)
        val hasJar = state.getValue(JAR)
        val bottles = state.getValue(BOTTLES)
        val action = when {
            held.`is`(EKItems.ICHAERIC_INK_JAR_ITEM.get()) -> ::putJar
            hasJar && held.isEmpty -> ::takeJar
            hasJar && held.`is`(EKItems.ICHAERIC_INK_BOTTLE.get()) && bottles < IchaericInkJarBlock.MAX_BOTTLES -> ::pourBottle
            else -> return InteractionResult.PASS
        }
        if (level.isClientSide) return InteractionResult.SUCCESS
        action(state, level, pos, player, hand)
        return InteractionResult.CONSUME
    }

    /** Puts the held jar in, and gives back the jar that was there, if any. */
    private fun putJar(state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand) {
        val held = player.getItemInHand(hand)
        val placed = state.setValue(JAR, true).setValue(BOTTLES, IchaericInkJarBlock.bottlesOf(held))
        if (state.getValue(JAR)) {
            player.setItemInHand(hand, ItemUtils.createFilledResult(held, player, jarItem(state)))
        } else if (!player.abilities.instabuild) {
            held.shrink(1)
        }
        level.setBlock(pos, placed, UPDATE_ALL)
        level.playSound(null, pos, EKSounds.JAR_RECEPTACLE_JAR_IN.get(), SoundSource.BLOCKS, 1f, 1f)
    }

    private fun takeJar(state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand) {
        player.setItemInHand(hand, jarItem(state))
        level.setBlock(pos, state.setValue(JAR, false).setValue(BOTTLES, 0), UPDATE_ALL)
        level.playSound(null, pos, EKSounds.JAR_RECEPTACLE_JAR_OUT.get(), SoundSource.BLOCKS, 1f, 1f)
    }

    private fun pourBottle(state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand) {
        val bottles = state.getValue(BOTTLES) + 1
        player.setItemInHand(
            hand, ItemUtils.createFilledResult(player.getItemInHand(hand), player, ItemStack(Items.GLASS_BOTTLE)),
        )
        level.setBlock(pos, state.setValue(BOTTLES, bottles), UPDATE_ALL)
        level.playSound(null, pos, EKSounds.ICHAERIC_INK_JAR_POUR.get(), SoundSource.BLOCKS, 1f, 0.9f + 0.04f * bottles)
        player.displayClientMessage(
            Component.translatable(IchaericInkJarBlock.CONTENTS_KEY, bottles, IchaericInkJarBlock.MAX_BOTTLES), true,
        )
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, moved: Boolean) {
        scheduleFeed(state, level, pos)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos, block: Block, fromPos: BlockPos, moved: Boolean,
    ) {
        scheduleFeed(state, level, pos)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        var bottles = state.getValue(BOTTLES)
        if (!state.getValue(JAR) || bottles == 0) return
        val open = state.getValue(FACING)
        for (side in Direction.values()) {
            if (side == open) continue
            val oarscribe = level.getBlockEntity(pos.relative(side)) as? OarscribeBlockEntity ?: continue
            while (bottles > 0 && oarscribe.inkSpace >= OarscribeBlockEntity.INK_PER_BOTTLE) {
                oarscribe.fillInk(OarscribeBlockEntity.INK_PER_BOTTLE)
                bottles--
            }
        }
        if (bottles != state.getValue(BOTTLES)) {
            level.setBlock(pos, state.setValue(BOTTLES, bottles), UPDATE_ALL)
            level.playSound(null, pos, EKSounds.JAR_RECEPTACLE_FEED.get(), SoundSource.BLOCKS, 1f, 1f)
        }
        if (bottles > 0) level.scheduleTick(pos, this, FEED_INTERVAL_TICKS)
    }

    private fun scheduleFeed(state: BlockState, level: Level, pos: BlockPos) {
        if (level.isClientSide || !state.getValue(JAR) || state.getValue(BOTTLES) == 0) return
        if (!level.blockTicks.hasScheduledTick(pos, this)) level.scheduleTick(pos, this, FEED_INTERVAL_TICKS)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block) && state.getValue(JAR)) {
            Containers.dropItemStack(level, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), jarItem(state))
        }
        super.onRemove(state, level, pos, newState, moved)
    }

    private fun jarItem(state: BlockState): ItemStack =
        IchaericInkJarBlock.withBottles(ItemStack(EKItems.ICHAERIC_INK_JAR_ITEM.get()), state.getValue(BOTTLES))

    companion object {
        val JAR: BooleanProperty = BooleanProperty.create("jar")
        val BOTTLES = IchaericInkJarBlock.BOTTLES

        private const val FEED_INTERVAL_TICKS = 20
    }
}
