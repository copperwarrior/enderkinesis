package org.shipwrights.enderkinesis.item

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.PotionItem
import net.minecraft.world.item.UseAnim
import net.minecraft.world.level.Level

class GrandStaffOfTheDeadItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (hand == InteractionHand.MAIN_HAND && player.offhandItem.item is PotionItem) {
            if (level is ServerLevel) GrandStaffRaising.sharePotion(level, player)
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
        }
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(stack)
    }

    override fun getUseDuration(stack: ItemStack): Int = Int.MAX_VALUE
    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.BOW

    override fun onUseTick(level: Level, entity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        if (level is ServerLevel && entity is Player) GrandStaffRaising.tickGather(level, entity)
    }
}
