package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.HornedFrogModel
import org.shipwrights.enderkinesis.entity.HornedFrogEntity

class HornedFrogRenderer(ctx: EntityRendererProvider.Context) : MobRenderer<HornedFrogEntity, HornedFrogModel>(
    ctx,
    HornedFrogModel(ctx.bakeLayer(HornedFrogModel.LAYER_LOCATION)),
    SHADOW_RADIUS,
) {
    override fun getTextureLocation(entity: HornedFrogEntity): ResourceLocation = SKIN

    override fun render(
        entity: HornedFrogEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack,
        buffer: MultiBufferSource, packedLight: Int,
    ) {
        shadowRadius = SHADOW_RADIUS * entity.scale
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
    }

    // A HierarchicalModel has no young-model scaling, so babies shrink here, matching their half-size hitbox.
    override fun scale(entity: HornedFrogEntity, poseStack: PoseStack, partialTick: Float) {
        val scale = entity.scale
        poseStack.scale(scale, scale, scale)
    }

    private companion object {
        const val SHADOW_RADIUS = 0.3f
        private val SKIN: ResourceLocation = EnderkinesisMod.id("textures/entity/${HornedFrogEntity.ID_PATH}.png")
    }
}
