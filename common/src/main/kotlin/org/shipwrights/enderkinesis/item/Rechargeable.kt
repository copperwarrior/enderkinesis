package org.shipwrights.enderkinesis.item

import net.minecraft.world.item.ItemStack

/** An item the Fountain of Blessing `recharge` outcome can refill. */
interface Rechargeable {
    /** Adds [share] (0..1] of a full charge to [stack]. */
    fun recharge(stack: ItemStack, share: Double)
}
