package org.shipwrights.enderkinesis.fabric.compat.trinkets

import dev.architectury.platform.Platform
import dev.emi.trinkets.api.SlotReference
import dev.emi.trinkets.api.Trinket
import dev.emi.trinkets.api.TrinketsApi
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.item.AccessoryGatherer
import org.shipwrights.enderkinesis.item.SatchelOfShelvingItem
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Fabric-side bridge between Trinkets and `RobeArmor`'s combined-score calculator. At init
 * we check whether Trinkets is actually loaded; if so we register a gatherer with
 * [AccessoryGatherer] that pulls every equipped trinket item-stack from the wearer's
 * component.
 *
 * Mirrors `CuriosCompat` on the Forge side. The `modCompileOnly` declaration in
 * `fabric/build.gradle` brings the Trinkets API on the classpath for compilation only;
 * the actual mod jar is not bundled.
 */
object TrinketsCompat {

    fun init() {
        if (!Platform.isModLoaded("trinkets")) return
        AccessoryGatherer.register(::gatherEquippedTrinkets)
        EKItems.SATCHEL_OF_SHELVING.listen { TrinketsApi.registerTrinket(it, SatchelTrinket) }
    }

    // Trinkets calls onEquip only when the item changes, so there is no previous satchel to skip.
    private object SatchelTrinket : Trinket {
        override fun onEquip(stack: ItemStack, slot: SlotReference, entity: LivingEntity) {
            SatchelOfShelvingItem.playAccessoryEquip(entity, ItemStack.EMPTY)
        }
    }

    private fun gatherEquippedTrinkets(wearer: LivingEntity): List<ItemStack> {
        val component = TrinketsApi.getTrinketComponent(wearer).orElse(null) ?: return emptyList()
        val equipped = component.allEquipped
        if (equipped.isEmpty()) return emptyList()
        val out = ArrayList<ItemStack>(equipped.size)
        for (pair in equipped) {
            // Trinkets returns com.mojang.datafixers.util.Pair<SlotReference, ItemStack>;
            // the getter is exposed as a method, not as a Kotlin property.
            val stack: ItemStack = pair.b
            if (!stack.isEmpty) out.add(stack)
        }
        return out
    }
}
