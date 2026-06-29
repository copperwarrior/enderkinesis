package org.shipwrights.enderkinesis.forge.compat.curios

import dev.architectury.platform.Platform
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.item.AccessoryGatherer
import top.theillusivec4.curios.api.CuriosApi

/**
 * Forge-side bridge between Curios and `RobeArmor`'s combined-score calculator. At init we
 * check whether Curios is actually loaded; if so we register a gatherer with
 * [AccessoryGatherer] that pulls every equipped curio item-stack from the wearer's
 * inventory.
 *
 * Mirrors `CreateCompat.init()` — `Platform.isModLoaded` gate, direct API import, runtime
 * dependency optional. The `modCompileOnly` declaration in `forge/build.gradle` brings the
 * Curios API on the classpath for compilation only; the actual mod jar is not bundled.
 */
object CuriosCompat {

    fun init() {
        if (!Platform.isModLoaded("curios")) return
        AccessoryGatherer.register(::gatherEquippedCurios)
    }

    private fun gatherEquippedCurios(wearer: LivingEntity): List<ItemStack> {
        val handler = CuriosApi.getCuriosInventory(wearer).orElse(null) ?: return emptyList()
        val items = handler.equippedCurios ?: return emptyList()
        val slots = items.slots
        if (slots == 0) return emptyList()
        val out = ArrayList<ItemStack>(slots)
        for (i in 0 until slots) {
            val stack = items.getStackInSlot(i)
            if (!stack.isEmpty) out.add(stack)
        }
        return out
    }
}
