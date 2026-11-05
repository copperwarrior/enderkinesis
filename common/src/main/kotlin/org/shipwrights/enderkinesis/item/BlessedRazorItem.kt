package org.shipwrights.enderkinesis.item

import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Vanishable
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentCategory

class BlessedRazorItem(properties: Properties) : Item(properties), Vanishable {

    // Vanilla needs canBeDepleted() for this; the razor has no durability.
    override fun isEnchantable(stack: ItemStack): Boolean = true

    override fun getEnchantmentValue(): Int = ENCHANTMENT_VALUE

    /** Forge IForgeItem hook; common compiles against vanilla, so this can't say `override`. */
    @Suppress("unused") // Called by Forge through JVM dispatch.
    fun canApplyAtEnchantingTable(stack: ItemStack, enchantment: Enchantment): Boolean =
        acceptsSwordEnchantment(enchantment) || enchantment.category.canEnchant(stack.item)

    companion object {
        private const val ENCHANTMENT_VALUE = 15

        @JvmStatic
        fun acceptsSwordEnchantment(enchantment: Enchantment): Boolean =
            enchantment.category == EnchantmentCategory.WEAPON
    }
}
