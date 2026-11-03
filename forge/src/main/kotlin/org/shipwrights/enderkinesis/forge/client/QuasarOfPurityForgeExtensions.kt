package org.shipwrights.enderkinesis.forge.client

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import org.shipwrights.enderkinesis.client.QuasarOfPurityBEWLR

object QuasarOfPurityForgeExtensions : IClientItemExtensions {
    override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer = QuasarOfPurityBEWLR
}
