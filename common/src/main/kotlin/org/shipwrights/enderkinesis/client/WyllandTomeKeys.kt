package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.InputConstants
import dev.architectury.registry.client.keymappings.KeyMappingRegistry
import net.minecraft.client.KeyMapping

/**
 * Registered client-side key mappings for the Wylland Tome.
 *
 *  - [MOD_KEY] — "Wylland Tome Mod Key". Held while grabbing to enable
 *    rotation, lock the player's view, and swallow mouse-wheel input
 *    so it doesn't cycle the hotbar. Defaults to Left Alt — players
 *    can rebind in the standard controls screen.
 */
object WyllandTomeKeys {

    val MOD_KEY: KeyMapping = KeyMapping(
        "key.enderkinesis.wylland_tome.mod",
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_LALT,
        "key.categories.enderkinesis",
    )

    fun init() {
        KeyMappingRegistry.register(MOD_KEY)
    }

    /** True when the player is currently holding the mod key. Safe to
     *  call from any client-thread context — [KeyMapping.isDown] just
     *  reads the cached down-state set by the keyboard event pump. */
    fun isModKeyDown(): Boolean = MOD_KEY.isDown
}
