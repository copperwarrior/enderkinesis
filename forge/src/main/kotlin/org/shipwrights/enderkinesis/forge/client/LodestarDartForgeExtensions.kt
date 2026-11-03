package org.shipwrights.enderkinesis.forge.client

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import org.shipwrights.enderkinesis.client.LodestarDartBEWLR

/**
 * Forge-side handle for the Lodestar Dart's client extensions. Same pattern as
 * [WyllandTomeForgeExtensions] — returns the shared common BEWLR so Forge's
 * `IClientItemExtensions.getCustomRenderer` dispatches into the model-based renderer.
 * Wiring onto the Item lives in `ItemForgeLodestarDartMixin`.
 */
object LodestarDartForgeExtensions : IClientItemExtensions {
    override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer = LodestarDartBEWLR
}
