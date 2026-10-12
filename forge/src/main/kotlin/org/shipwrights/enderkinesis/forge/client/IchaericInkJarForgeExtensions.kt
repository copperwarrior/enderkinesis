package org.shipwrights.enderkinesis.forge.client

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import org.shipwrights.enderkinesis.client.IchaericInkJarBEWLR

object IchaericInkJarForgeExtensions : IClientItemExtensions {
    override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer = IchaericInkJarBEWLR
}
