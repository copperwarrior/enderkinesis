package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.BlessedGoatModel
import org.shipwrights.enderkinesis.entity.BlessedGoatEntity

class BlessedGoatRenderer(
    ctx: EntityRendererProvider.Context,
) : MobRenderer<BlessedGoatEntity, BlessedGoatModel>(
    ctx,
    BlessedGoatModel(ctx.bakeLayer(BlessedGoatModel.LAYER_LOCATION)),
    SHADOW_RADIUS,
) {
    override fun getTextureLocation(entity: BlessedGoatEntity): ResourceLocation = SKIN

    // HierarchicalModel ignores `young`, so the kid is drawn at its half-size hitbox scale here.
    override fun scale(entity: BlessedGoatEntity, poseStack: PoseStack, partialTick: Float) {
        if (entity.isBaby) poseStack.scale(BABY_SCALE, BABY_SCALE, BABY_SCALE)
    }

    companion object {
        private const val SHADOW_RADIUS = 0.5f
        private const val BABY_SCALE = 0.5f
        private val SKIN: ResourceLocation =
            EnderkinesisMod.id("textures/entity/${BlessedGoatEntity.ID_PATH}.png")
    }
}
