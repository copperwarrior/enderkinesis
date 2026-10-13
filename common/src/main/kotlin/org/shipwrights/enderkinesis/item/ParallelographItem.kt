package org.shipwrights.enderkinesis.item

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.dimension.SselithParallel
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKSounds

/** Reads the holder's Sselith parallel onto the action bar; the client wiggles the arm. */
class ParallelographItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        // Called on both sides: the client plays it on the wiggle tick, and the server sends it to all others.
        level.playSound(
            player, player.x, player.y, player.z,
            EKSounds.PARALLELOGRAPH_ACTIVATE.get(), SoundSource.PLAYERS, 1f, 1f,
        )
        if (level.isClientSide) {
            lastUseMillis = net.minecraft.Util.getMillis()
            lastUseHand = hand
        } else if (player is ServerPlayer) {
            val message = if (level.dimension() == SselithRepertory.LEVEL_KEY) {
                Component.translatable("item.enderkinesis.parallelograph.reading", SselithParallel.get(player).toString())
            } else {
                Component.translatable("item.enderkinesis.parallelograph.none")
            }
            player.displayClientMessage(message, true)
        }
        // CONSUME, not SUCCESS: the use counts but the hand does not swing.
        return InteractionResultHolder.consume(stack)
    }

    companion object {
        private const val WIGGLE_MILLIS = 900L
        private const val WIGGLE_DEGREES = 18f
        private const val WIGGLE_SWINGS = 3f

        /** Client-thread only: the local player's last use. */
        @Volatile
        private var lastUseMillis: Long = Long.MIN_VALUE / 2

        @Volatile
        private var lastUseHand: InteractionHand = InteractionHand.MAIN_HAND

        /** Damped yaw swing of the arm, in degrees, at [nowMillis]. Zero unless [stack] is the one [localPlayer] used. */
        @JvmStatic
        fun armYawDegrees(stack: ItemStack, localPlayer: Player?, nowMillis: Long): Float {
            // Identity, not equality: equal stacks in other slots and other hands must stay still.
            if (localPlayer == null || localPlayer.getItemInHand(lastUseHand) !== stack) return 0f
            val elapsed = nowMillis - lastUseMillis
            if (elapsed !in 0 until WIGGLE_MILLIS) return 0f
            val t = elapsed.toFloat() / WIGGLE_MILLIS
            return WIGGLE_DEGREES * (1f - t) * Mth.sin(t * WIGGLE_SWINGS * Mth.TWO_PI)
        }
    }
}
