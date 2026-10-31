package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.model.BoatModel
import net.minecraft.client.model.ChestBoatModel
import net.minecraft.client.model.ListModel
import net.minecraft.client.model.WaterPatchModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.vehicle.Boat
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Port of vanilla BoatRenderer.render: vanilla picks model and texture from the closed Boat.Type enum.
 *  The OAK model layer has the same geometry as every non-raft boat. */
class WogorBoatRenderer(context: EntityRendererProvider.Context, chest: Boolean) : EntityRenderer<Boat>(context) {
    private val model: ListModel<Boat> =
        if (chest) ChestBoatModel(context.bakeLayer(ModelLayers.createChestBoatModelName(Boat.Type.OAK)))
        else BoatModel(context.bakeLayer(ModelLayers.createBoatModelName(Boat.Type.OAK)))
    private val texture = ResourceLocation(
        EnderkinesisMod.MOD_ID,
        if (chest) "textures/entity/chest_boat/wogor.png" else "textures/entity/boat/wogor.png",
    )
    private val bubbleAxis = Quaternionf()

    init {
        shadowRadius = SHADOW_RADIUS
    }

    override fun render(
        boat: Boat, entityYaw: Float, partialTick: Float, poseStack: PoseStack, buffers: MultiBufferSource, light: Int,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.0f, MODEL_Y_OFFSET, 0.0f)
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - entityYaw))
        val hurt = boat.hurtTime - partialTick
        val damage = (boat.damage - partialTick).coerceAtLeast(0.0f)
        if (hurt > 0.0f) {
            poseStack.mulPose(Axis.XP.rotationDegrees(Mth.sin(hurt) * hurt * damage / 10.0f * boat.hurtDir))
        }
        val bubbleAngle = boat.getBubbleAngle(partialTick)
        if (!Mth.equal(bubbleAngle, 0.0f)) {
            poseStack.mulPose(bubbleAxis.setAngleAxis(bubbleAngle * Mth.DEG_TO_RAD, 1.0f, 0.0f, 1.0f))
        }
        poseStack.scale(-1.0f, -1.0f, 1.0f)
        poseStack.mulPose(Axis.YP.rotationDegrees(90.0f))
        model.setupAnim(boat, partialTick, 0.0f, -0.1f, 0.0f, 0.0f)
        val consumer = buffers.getBuffer(model.renderType(texture))
        model.renderToBuffer(poseStack, consumer, light, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f)
        if (!boat.isUnderWater && model is WaterPatchModel) {
            model.waterPatch().render(poseStack, buffers.getBuffer(RenderType.waterMask()), light, OverlayTexture.NO_OVERLAY)
        }
        poseStack.popPose()
        super.render(boat, entityYaw, partialTick, poseStack, buffers, light)
    }

    override fun getTextureLocation(boat: Boat): ResourceLocation = texture

    private companion object {
        const val SHADOW_RADIUS = 0.8f
        const val MODEL_Y_OFFSET = 0.375f
    }
}
