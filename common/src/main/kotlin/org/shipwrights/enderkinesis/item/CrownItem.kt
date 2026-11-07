package org.shipwrights.enderkinesis.item

import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Equipable
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.level.Level

// Not an ArmorItem, so it gives no armor points. The ARMOR and ARMOR_HEAD categories accept only ArmorItem.
open class CrownItem(props: Properties) : Item(props), Equipable {

    override fun getEquipmentSlot(): EquipmentSlot = EquipmentSlot.HEAD

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> =
        swapWithEquipmentSlot(this, level, player, hand)

    override fun getEnchantmentValue(): Int = ENCHANTMENT_VALUE

    /** Forge IForgeItem hook; common compiles against vanilla, so this can't say `override`. */
    @Suppress("unused") // Called by Forge through JVM dispatch.
    fun canApplyAtEnchantingTable(stack: ItemStack, enchantment: Enchantment): Boolean =
        acceptsAtTable(enchantment)

    companion object {
        private const val ENCHANTMENT_VALUE = 25

        /** Anvil and `/enchant`: what a helmet takes. */
        @JvmStatic
        fun acceptsHelmetEnchantment(enchantment: Enchantment): Boolean =
            enchantment.canEnchant(ItemStack(Items.GOLDEN_HELMET))

        // The table asks only the category, so a helmet gets Thorns from an anvil but not from the table.
        @JvmStatic
        fun acceptsAtTable(enchantment: Enchantment): Boolean =
            enchantment.category.canEnchant(Items.GOLDEN_HELMET)
    }
}
