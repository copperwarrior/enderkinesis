package org.shipwrights.enderkinesis.item

import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class CrownUntestedItem(props: Item.Properties) : CrownItem(props) {

    override fun isValidRepairItem(stack: ItemStack, repair: ItemStack): Boolean = repair.`is`(Items.GOLD_INGOT)

    companion object {
        const val MAX_DURABILITY = 77
    }
}
