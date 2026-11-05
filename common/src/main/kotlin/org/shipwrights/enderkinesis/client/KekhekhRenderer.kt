package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.KekhekhModel
import org.shipwrights.enderkinesis.entity.KekhekhEntity

class KekhekhRenderer(ctx: EntityRendererProvider.Context) : MobRenderer<KekhekhEntity, KekhekhModel>(
    ctx,
    KekhekhModel(ctx.bakeLayer(KekhekhModel.LAYER_LOCATION)),
    SHADOW_RADIUS,
) {
    override fun getTextureLocation(entity: KekhekhEntity): ResourceLocation = SKIN

    override fun render(
        entity: KekhekhEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack,
        buffer: MultiBufferSource, packedLight: Int,
    ) {
        shadowRadius = SHADOW_RADIUS * entity.size
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
    }

    override fun scale(entity: KekhekhEntity, poseStack: PoseStack, partialTick: Float) {
        poseStack.scale(entity.size, entity.size, entity.size)
    }

    companion object {
        private const val SHADOW_RADIUS = 0.3f
        private val SKIN: ResourceLocation = EnderkinesisMod.id("textures/entity/${KekhekhEntity.ID_PATH}.png")
    }
}
