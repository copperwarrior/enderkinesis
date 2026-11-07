package org.shipwrights.enderkinesis.item

import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Equipable
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

class CrownUntestedItem(props: Properties) : Item(props), Equipable {

    override fun getEquipmentSlot(): EquipmentSlot = EquipmentSlot.HEAD

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> =
        swapWithEquipmentSlot(this, level, player, hand)
}
