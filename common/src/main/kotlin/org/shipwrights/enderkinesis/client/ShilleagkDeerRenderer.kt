package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.layers.EyesLayer
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.ShilleagkDeerModel
import org.shipwrights.enderkinesis.entity.ShilleagkDeerEntity

class ShilleagkDeerRenderer(
    ctx: EntityRendererProvider.Context,
) : MobRenderer<ShilleagkDeerEntity, ShilleagkDeerModel>(
    ctx,
    ShilleagkDeerModel(ctx.bakeLayer(ShilleagkDeerModel.LAYER_LOCATION)),
    SHADOW_RADIUS,
) {
    init {
        // Return value ignored: addLayer returns a boolean that is always true.
        addLayer(EyeGlowLayer(this))
    }

    override fun getTextureLocation(entity: ShilleagkDeerEntity): ResourceLocation = SKIN

    private class EyeGlowLayer(renderer: ShilleagkDeerRenderer) :
        EyesLayer<ShilleagkDeerEntity, ShilleagkDeerModel>(renderer) {
        override fun renderType(): RenderType = EYES
    }

    companion object {
        private const val SHADOW_RADIUS = 0.6f
        private val SKIN: ResourceLocation =
            EnderkinesisMod.id("textures/entity/${ShilleagkDeerEntity.ID_PATH}.png")
        private val EYES: RenderType =
            RenderType.eyes(EnderkinesisMod.id("textures/entity/${ShilleagkDeerEntity.ID_PATH}_eyes.png"))
    }
}
