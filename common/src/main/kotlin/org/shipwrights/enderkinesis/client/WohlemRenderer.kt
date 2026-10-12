package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.WohlemModel
import org.shipwrights.enderkinesis.entity.WohlemEntity

/** Renderer for the [WohlemEntity] — plain [MobRenderer] over the keyframe-
 *  animated [WohlemModel]; no layers (never armoured, never holds items). */
class WohlemRenderer(
    ctx: EntityRendererProvider.Context,
) : MobRenderer<WohlemEntity, WohlemModel>(
    ctx,
    WohlemModel(ctx.bakeLayer(WohlemModel.LAYER_LOCATION)),
    SHADOW_RADIUS,
) {
    override fun getTextureLocation(entity: WohlemEntity): ResourceLocation = SKIN

    companion object {
        private const val SHADOW_RADIUS = 1.1f
        private val SKIN: ResourceLocation = EnderkinesisMod.id("textures/entity/wohlem.png")
    }
}
