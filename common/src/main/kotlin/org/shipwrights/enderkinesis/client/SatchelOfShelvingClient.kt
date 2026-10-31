package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.InputConstants
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.registry.client.keymappings.KeyMappingRegistry
import dev.architectury.registry.item.ItemPropertiesRegistry
import dev.architectury.registry.menu.MenuRegistry
import net.minecraft.client.KeyMapping
import org.shipwrights.enderkinesis.item.SatchelOfShelvingNetwork
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKMenus

object SatchelOfShelvingClient {

    /** Unbound by default. */
    val OPEN_KEY: KeyMapping = KeyMapping(
        "key.enderkinesis.satchel_of_shelving.open",
        InputConstants.UNKNOWN.value,
        "key.categories.enderkinesis",
    )

    fun init() {
        MenuRegistry.registerScreenFactory(EKMenus.SATCHEL_OF_SHELVING.get(), ::SatchelOfShelvingScreen)
        ItemPropertiesRegistry.register(EKItems.SATCHEL_OF_SHELVING.get(), SatchelOpenProperty.ID) { stack, _, _, _ ->
            SatchelOpenProperty.value(stack)
        }
        KeyMappingRegistry.register(OPEN_KEY)
        // The server picks which satchel to open; the client may not see accessory slots.
        ClientTickEvent.CLIENT_POST.register { _ ->
            while (OPEN_KEY.consumeClick()) SatchelOfShelvingNetwork.sendOpen()
        }
    }
}
