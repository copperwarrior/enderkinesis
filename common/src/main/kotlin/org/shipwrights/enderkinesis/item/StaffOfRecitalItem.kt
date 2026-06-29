package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.SlotAccess
import net.minecraft.world.inventory.ClickAction
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.UseAnim
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level

/**
 * Bundle of tomes — adding a duplicate kind replaces the existing entry so a fresher
 * tome's NBT isn't lost. Item overrides proxy through to the active tome by swapping the
 * player's held stack to the active tome for the duration of the call, then writing the
 * (possibly modified) tome stack back and restoring the staff — so logic that reads
 * "my own stack" inside the proxied call sees the tome, not the staff.
 *
 * Wylland's input is LEFT-click; the Wylland mixins recognise the staff via
 * [RecitalHelper.isHoldingWyllandTome] so the gesture works through it, and the SGA
 * enchant-particle beam render reads [RecitalHelper.isHoldingWyllandViaRecitalStaff]
 * to source from the staff tip rather than the camera centre.
 */
class StaffOfRecitalItem(properties: Properties) : Item(properties) {


    override fun overrideOtherStackedOnMe(
        stack: ItemStack, other: ItemStack, slot: Slot,
        action: ClickAction, player: Player, access: SlotAccess,
    ): Boolean {
        if (action != ClickAction.SECONDARY) return false
        if (other.isEmpty) return false
        if (!RecitalHelper.isEligibleTome(other)) return false
        if (RecitalHelper.addTome(stack, other)) {
            other.shrink(other.count)
            playInsert(player)
            return true
        }
        return false
    }

    override fun overrideStackedOnOther(
        stack: ItemStack, slot: Slot, action: ClickAction, player: Player,
    ): Boolean {
        if (action != ClickAction.SECONDARY) return false
        if (slot.item.isEmpty) {
            val removed = RecitalHelper.removeActiveTome(stack) ?: return false
            slot.safeInsert(removed)
            playRemove(player)
            return true
        }
        return false
    }

    private fun playInsert(player: Player) {
        player.playSound(SoundEvents.BUNDLE_INSERT, 0.8f, 0.8f + player.random.nextFloat() * 0.4f)
    }

    private fun playRemove(player: Player) {
        player.playSound(SoundEvents.BUNDLE_REMOVE_ONE, 0.8f, 0.8f + player.random.nextFloat() * 0.4f)
    }


    override fun appendHoverText(
        stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        val tomes = RecitalHelper.readTomes(stack)
        if (tomes.isEmpty()) {
            tooltip.add(
                Component.translatable("item.enderkinesis.staff_of_recital.tooltip.empty")
                    .withStyle(ChatFormatting.GRAY)
            )
            return
        }
        val activeIdx = RecitalHelper.getActiveIndex(stack)
        for ((i, tome) in tomes.withIndex()) {
            val prefix = if (i == activeIdx) "▶ " else "  "
            val line = Component.literal(prefix).append(tome.hoverName.copy())
            line.withStyle(if (i == activeIdx) ChatFormatting.WHITE else ChatFormatting.GRAY)
            tooltip.add(line)
        }
        tooltip.add(
            Component.translatable("item.enderkinesis.staff_of_recital.tooltip.cycle_hint")
                .withStyle(ChatFormatting.DARK_GRAY)
        )
    }


    /** Run `block` with the player's held stack swapped to the active tome.
     *  After the block runs, any mutation the tome made to its stack (e.g.
     *  damage, NBT writes) is persisted back into the staff. The staff stack
     *  is restored to the hand before returning.
     *
     *  Returns `null` if the staff is empty — callers should fall through to
     *  pass-through behaviour in that case. */
    private inline fun <R> withActiveTome(
        player: Player, hand: InteractionHand, staffStack: ItemStack, block: (ItemStack) -> R,
    ): R? {
        val tomeStack = RecitalHelper.getActiveTome(staffStack) ?: return null
        player.setItemInHand(hand, tomeStack)
        val result: R
        try {
            result = block(tomeStack)
        } finally {
            val updatedTome = player.getItemInHand(hand)
            RecitalHelper.replaceActiveTome(staffStack, updatedTome)
            player.setItemInHand(hand, staffStack)
        }
        return result
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val staffStack = player.getItemInHand(hand)
        val proxied = withActiveTome(player, hand, staffStack) { tome ->
            tome.item.use(level, player, hand)
        } ?: return InteractionResultHolder.pass(staffStack)
        return InteractionResultHolder(proxied.result, staffStack)
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val hand = context.hand
        val staffStack = player.getItemInHand(hand)
        val proxied = withActiveTome(player, hand, staffStack) { tome ->
            tome.item.useOn(context)
        } ?: return InteractionResult.PASS
        return proxied
    }

    override fun releaseUsing(stack: ItemStack, level: Level, entity: LivingEntity, timeLeft: Int) {
        if (entity !is Player) return
        val hand = if (entity.mainHandItem === stack) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
        withActiveTome(entity, hand, stack) { tome ->
            tome.item.releaseUsing(tome, level, entity, timeLeft)
        }
    }

    override fun onUseTick(level: Level, entity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        if (entity !is Player) return
        val hand = if (entity.mainHandItem === stack) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
        withActiveTome(entity, hand, stack) { tome ->
            tome.item.onUseTick(level, entity, tome, remainingUseDuration)
        }
    }

    override fun getUseDuration(stack: ItemStack): Int {
        val tome = RecitalHelper.getActiveTome(stack) ?: return 0
        return tome.item.getUseDuration(tome)
    }

    override fun getUseAnimation(stack: ItemStack): UseAnim {
        val tome = RecitalHelper.getActiveTome(stack) ?: return UseAnim.NONE
        return tome.item.getUseAnimation(tome)
    }

    override fun inventoryTick(
        stack: ItemStack, level: Level, entity: Entity, slotId: Int, isSelected: Boolean,
    ) {
        super.inventoryTick(stack, level, entity, slotId, isSelected)
        if (entity !is Player) return
        val tomes = RecitalHelper.readTomes(stack)
        if (tomes.isEmpty()) return
        val idx = RecitalHelper.getActiveIndex(stack).coerceIn(0, tomes.size - 1)
        val tome = tomes[idx]
        tome.item.inventoryTick(tome, level, entity, slotId, isSelected)
        tomes[idx] = tome
        RecitalHelper.writeTomes(stack, tomes)
    }
}
