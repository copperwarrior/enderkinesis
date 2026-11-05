package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.MoskuoModel
import org.shipwrights.enderkinesis.entity.MoskuoEntity

class MoskuoRenderer(ctx: EntityRendererProvider.Context) : MobRenderer<MoskuoEntity, MoskuoModel>(
    ctx,
    MoskuoModel(ctx.bakeLayer(MoskuoModel.LAYER_LOCATION)),
    SHADOW_RADIUS,
) {
    override fun getTextureLocation(entity: MoskuoEntity): ResourceLocation = SKIN

    private companion object {
        const val SHADOW_RADIUS = 0.15f
        private val SKIN: ResourceLocation = EnderkinesisMod.id("textures/entity/${MoskuoEntity.ID_PATH}.png")
    }
}
