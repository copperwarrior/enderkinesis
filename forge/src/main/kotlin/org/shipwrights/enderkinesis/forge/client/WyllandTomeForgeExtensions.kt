package org.shipwrights.enderkinesis.forge.client

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import org.shipwrights.enderkinesis.client.WyllandTomeBEWLR

/**
 * Forge-side handle for the Wylland Tome's client extensions. Forge
 * dispatches custom item renderers through
 * [IClientItemExtensions.getCustomRenderer], which is wired onto the
 * [org.shipwrights.enderkinesis.item.WyllandTomeItem] via the
 * `ItemForgeWyllandTomeMixin` injection on
 * [net.minecraft.world.item.Item.initializeClient]. Returning
 * [WyllandTomeBEWLR] from here is what makes vanilla
 * `ItemRenderer.render` dispatch through to our common BEWLR.
 */
object WyllandTomeForgeExtensions : IClientItemExtensions {
    override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer = WyllandTomeBEWLR
}
