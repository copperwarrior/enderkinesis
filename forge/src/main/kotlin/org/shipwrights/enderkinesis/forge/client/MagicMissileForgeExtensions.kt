package org.shipwrights.enderkinesis.forge.client

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import org.shipwrights.enderkinesis.client.MagicMissileBEWLR

/**
 * Forge-side handle for the Magic Missile's client extensions. Same pattern as
 * [WyllandTomeForgeExtensions] — returns the shared common BEWLR so Forge's
 * `IClientItemExtensions.getCustomRenderer` dispatches into the model-based renderer.
 * Wiring onto the Item lives in `ItemForgeMagicMissileMixin`.
 */
object MagicMissileForgeExtensions : IClientItemExtensions {
    override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer = MagicMissileBEWLR
}
