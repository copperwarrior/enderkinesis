package org.shipwrights.enderkinesis.enchantment

import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentCategory
import org.shipwrights.enderkinesis.registry.EKItems

/** Only the Fountain of Blessing gives this, so tables, loot, and trades must never offer it. */
class LoveOfTheMotherEnchantment :
    Enchantment(Rarity.VERY_RARE, EnchantmentCategory.BREAKABLE, arrayOf(EquipmentSlot.MAINHAND)) {

    override fun canEnchant(stack: ItemStack): Boolean = stack.`is`(EKItems.BLESSED_RAZOR.get())

    override fun isTreasureOnly(): Boolean = true

    override fun isDiscoverable(): Boolean = false

    override fun isTradeable(): Boolean = false
}
