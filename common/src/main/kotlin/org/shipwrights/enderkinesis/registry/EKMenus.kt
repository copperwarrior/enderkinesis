package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.MenuType
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.SatchelOfShelvingMenu

object EKMenus {
    val MENUS: DeferredRegister<MenuType<*>> = DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.MENU)

    val SATCHEL_OF_SHELVING: RegistrySupplier<MenuType<SatchelOfShelvingMenu>> =
        MENUS.register("satchel_of_shelving") {
            MenuType({ containerId, inventory -> SatchelOfShelvingMenu(containerId, inventory) }, FeatureFlags.VANILLA_SET)
        }

    fun register() = MENUS.register()
}
