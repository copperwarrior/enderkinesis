package org.shipwrights.enderkinesis.forge.client

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import org.shipwrights.enderkinesis.client.ParallelographBEWLR

object ParallelographForgeExtensions : IClientItemExtensions {
    override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer = ParallelographBEWLR
}
