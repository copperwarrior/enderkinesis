package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.entity.MiasmaEntity

// A MiasmaEntity pours into the gas field on its first tick, so [GasFieldClient] draws its gas.
class MiasmaRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<MiasmaEntity>(ctx) {

    @Suppress("DEPRECATION") // LOCATION_BLOCKS is deprecated, but this renderer draws nothing.
    override fun getTextureLocation(entity: MiasmaEntity): ResourceLocation = TextureAtlas.LOCATION_BLOCKS
}
