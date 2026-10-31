package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.entity.MiasmaEntity

/**
 * A [MiasmaEntity] is only ever an old cloud pouring into the gas field on its first tick, so
 * there's nothing of it to draw: its gas is drawn with the rest ([GasFieldClient]).
 */
class MiasmaRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<MiasmaEntity>(ctx) {

    @Suppress("DEPRECATION")
    override fun getTextureLocation(entity: MiasmaEntity): ResourceLocation = TextureAtlas.LOCATION_BLOCKS
}
