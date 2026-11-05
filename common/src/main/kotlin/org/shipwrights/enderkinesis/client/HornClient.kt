package org.shipwrights.enderkinesis.client

import dev.architectury.registry.item.ItemPropertiesRegistry
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.registry.EKItems

object HornClient {

    // Vanilla registers "tooting" only for Items.GOAT_HORN.
    private val TOOTING = ResourceLocation("tooting")

    fun init() {
        for (horn in listOf(EKItems.PRISMATIC_GOAT_HORN, EKItems.SHELL_OF_TIDES)) {
            ItemPropertiesRegistry.register(horn.get(), TOOTING) { stack, _, entity, _ ->
                if (entity != null && entity.isUsingItem && entity.useItem === stack) 1f else 0f
            }
        }
    }
}
