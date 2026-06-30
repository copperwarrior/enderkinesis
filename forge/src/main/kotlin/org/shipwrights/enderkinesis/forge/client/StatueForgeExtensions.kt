package org.shipwrights.enderkinesis.forge.client

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import org.shipwrights.enderkinesis.client.StatueBEWLR

/**
 * Forge-side handle that hands every Ulder Statue item the shared common
 * [StatueBEWLR]. Wired onto every `BlockItem` whose block is a `StatueBlock`
 * by [org.shipwrights.enderkinesis.forge.mixin.ItemForgeStatueMixin].
 */
object StatueForgeExtensions : IClientItemExtensions {
    override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer = StatueBEWLR
}
