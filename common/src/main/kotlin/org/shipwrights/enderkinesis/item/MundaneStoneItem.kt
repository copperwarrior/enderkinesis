package org.shipwrights.enderkinesis.item

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

class MundaneStoneItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        if (MundaneIncubation.incubating(player)) return InteractionResultHolder.fail(player.getItemInHand(hand))
        return super.use(level, player, hand)
    }

    override fun finishUsingItem(stack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        if (entity is ServerPlayer && !MundaneIncubation.start(entity)) {
            entity.displayClientMessage(Component.translatable("item.enderkinesis.mundane_stone.incubating"), true)
            // The client already took the stone off its stack.
            entity.inventoryMenu.sendAllDataToRemote()
            return stack
        }
        return super.finishUsingItem(stack, level, entity)
    }
}
