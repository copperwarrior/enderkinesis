package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.PrismaticGoatModel
import org.shipwrights.enderkinesis.entity.PrismaticGoat

/** Renderer for the [PrismaticGoat]. Plain [MobRenderer] — no armour
 *  layers, no held items, no glint. The model handles its own walk
 *  and idle animations in `setupAnim`. */
class PrismaticGoatRenderer(
    ctx: EntityRendererProvider.Context,
) : MobRenderer<PrismaticGoat, PrismaticGoatModel>(
    ctx,
    PrismaticGoatModel(ctx.bakeLayer(PrismaticGoatModel.LAYER_LOCATION)),
    SHADOW_RADIUS,
) {
    override fun getTextureLocation(entity: PrismaticGoat): ResourceLocation = SKIN

    companion object {
        private const val SHADOW_RADIUS = 0.5f
        private val SKIN: ResourceLocation =
            EnderkinesisMod.id("textures/entity/prismatic_goat.png")
    }
}
