package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.ShulkonautModel
import org.shipwrights.enderkinesis.entity.ShulkonautEntity

class ShulkonautRenderer(ctx: EntityRendererProvider.Context) :
    MobRenderer<ShulkonautEntity, ShulkonautModel>(
        ctx,
        ShulkonautModel(ctx.bakeLayer(ShulkonautModel.LAYER_LOCATION)),
        0.5f,
    ) {

    override fun getTextureLocation(entity: ShulkonautEntity): ResourceLocation = TEXTURE

    companion object {
        private val TEXTURE = ResourceLocation(
            EnderkinesisMod.MOD_ID, "textures/entity/shulkonaut.png",
        )
    }
}
