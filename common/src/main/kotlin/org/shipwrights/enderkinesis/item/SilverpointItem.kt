package org.shipwrights.enderkinesis.item

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.DyeItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.client.SilverpointView
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds

class SilverpointItem(properties: Properties) : Item(properties) {

    /**
     * Used past reach, or on a block past [STRIKE_REACH]: a viewfinder, held as long as the player likes. On release
     * the client sketches the framed view and sends it; the paper comes from [SilverpointNetwork].
     */
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        if (level.isClientSide) org.shipwrights.enderkinesis.client.SilverpointDebug.log("use: viewfinder start")
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(player.getItemInHand(hand))
    }

    override fun getUseDuration(stack: ItemStack): Int = VIEWFINDER_TICKS

    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.NONE

    override fun releaseUsing(stack: ItemStack, level: Level, entity: LivingEntity, timeLeft: Int) {
        if (entity !is Player) return
        if (level.isClientSide) org.shipwrights.enderkinesis.client.SilverpointDebug.log("releaseUsing: held {} ticks", getUseDuration(stack) - timeLeft)
        if (level.isClientSide && entity.isLocalPlayer) SilverpointView.capture()
        if (entity is ServerPlayer) SilverpointNetwork.allow(entity)
        entity.cooldowns.addCooldown(this, VIEW_COOLDOWN)
    }

    /** Past [STRIKE_REACH], a block passes to [use], so things a few blocks away are sketched whole. */
    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val player = context.player ?: return InteractionResult.PASS
        if (player.eyePosition.distanceToSqr(context.clickLocation) > STRIKE_REACH * STRIKE_REACH) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        val pos = context.clickedPos
        val state = level.getBlockState(pos)
        val sound = state.soundType
        level.playSound(null, pos, sound.hitSound, SoundSource.BLOCKS, sound.volume, sound.pitch)
        level.playSound(null, pos, EKSounds.SILVERPOINT_NOTE.get(), SoundSource.PLAYERS, 1f, 1f)
        val readout = BlockPhysicsReadout.of(
            level, pos, state, player.gameProfile.name, level.random.nextInt(), dyeInOtherHand(player, context.hand),
        )
        record(player, readout)
        return InteractionResult.CONSUME
    }

    companion object {
        /** Before the entity's own interaction, so a villager or a horse is read and not traded with or ridden. */
        fun initEvents() {
            InteractionEvent.INTERACT_ENTITY.register { player, entity, hand ->
                if (player.getItemInHand(hand).item !is SilverpointItem) return@register EventResult.pass()
                val level = player.level()
                if (level.isClientSide) return@register EventResult.interruptTrue()
                level.playSound(null, entity.x, entity.y, entity.z, EKSounds.SILVERPOINT_NOTE.get(), SoundSource.PLAYERS, 1f, 1f)
                record(player, EntityReadout.of(entity, player.gameProfile.name, level.random.nextInt(), dyeInOtherHand(player, hand)))
                EventResult.interruptTrue()
            }
        }

        private const val VIEW_COOLDOWN = 20
        private const val STRIKE_REACH = 3.0
        // As a spyglass: the hold has no practical end.
        private const val VIEWFINDER_TICKS = 72_000

        fun dyeInOtherHand(player: Player, silverpointHand: InteractionHand): Boolean {
            val other = if (silverpointHand == InteractionHand.MAIN_HAND) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
            return player.getItemInHand(other).item is DyeItem
        }

        private fun record(player: Player, readout: StrikeReadout) {
            player.displayClientMessage(readout.rounded(), true)
            give(player, readout)
        }

        fun give(player: Player, readout: StrikeReadout) {
            val record = ItemStack(EKItems.STRIKE_READOUT.get())
            record.addTagElement(StrikeReadoutItem.TAG, readout.save())
            if (!player.inventory.add(record)) player.drop(record, false)
        }
    }
}
