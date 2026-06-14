package org.shipwrights.enderkinesis.item

import net.minecraft.world.item.Item

/**
 * Crepusculite Charm — the levitation item. Plain [Item] except for a non-zero enchantment
 * value: that, plus the `durability(...)` on its [Properties] at registration, is enough to
 * make `Item.isEnchantable` return true (vanilla checks `maxStackSize == 1 && canBeDepleted()`),
 * which in turn lets the enchanting table offer enchantments and lets Unbreaking / Mending
 * apply through the standard [EnchantmentCategory.BREAKABLE] gate that both vanilla
 * enchantments declare. No need to override [canApplyAtEnchantingTable] (that's a Forge
 * concept; the category-based vanilla path already covers our case on both loaders).
 *
 * Durability drain happens in [CrepusculiteCharmManager], not here — `Item.inventoryTick`
 * has no "is being used" signal, so the manager's airborne / in-envelope check is the
 * single source of truth for both the anti-cheat exemption and the wear.
 */
class CrepusculiteCharmItem(properties: Properties) : Item(properties) {
    override fun getEnchantmentValue(): Int = ENCHANTMENT_VALUE

    companion object {
        /** Total uses before the charm breaks. Combined with [CrepusculiteCharmManager]'s
         *  per-`DRAIN_INTERVAL_TICKS` drain (`1 dmg / 0.5 s` at default settings), that's
         *  ~4 minutes of continuous flight per charge before Unbreaking is factored in. */
        const val MAX_DURABILITY: Int = 500

        /** Mirrors the leather-armor / iron-tool tier. Higher values just make the
         *  enchanting table offer richer rolls; the actual enchantments accepted are
         *  decided by [EnchantmentCategory.BREAKABLE] (Unbreaking + Mending). */
        const val ENCHANTMENT_VALUE: Int = 1
    }
}
