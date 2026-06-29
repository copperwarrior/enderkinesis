package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.model.ShulkerBulletModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import org.shipwrights.enderkinesis.entity.MagicMissileEntity

/**
 * In-flight renderer for the magic missile — **exact** pose chain from vanilla
 * `ShulkerBulletRenderer`: translate up 0.15, wobble in three axes off `tickCount`, scale
 * `(-0.5, -0.5, 0.5)`. No velocity-direction orientation (vanilla doesn't do that either —
 * the wobble *is* the silhouette read).
 *
 * The pulse-beam trail is drawn separately in [MagicMissileTrailRenderer] from the
 * level-renderer's `AFTER_TRANSLUCENT` hook so it composites correctly over translucent
 * world geometry.
 */
class MagicMissileRenderer(ctx: EntityRendererProvider.Context) :
    EntityRenderer<MagicMissileEntity>(ctx) {

    private val model: ShulkerBulletModel<MagicMissileEntity> =
        ShulkerBulletModel(ctx.bakeLayer(ModelLayers.SHULKER_BULLET))

    override fun render(
        entity: MagicMissileEntity,
        yaw: Float,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        light: Int,
    ) {
        pose.pushPose()
        val f2 = entity.tickCount.toFloat() + partialTick
        pose.translate(0f, 0.15f, 0f)
        pose.mulPose(Axis.YP.rotationDegrees(Mth.sin(f2 * 0.1f) * 180.0f))
        pose.mulPose(Axis.XP.rotationDegrees(Mth.cos(f2 * 0.1f) * 180.0f))
        pose.mulPose(Axis.ZP.rotationDegrees(Mth.sin(f2 * 0.15f) * 360.0f))
        pose.scale(-0.5f, -0.5f, 0.5f)
        val vc = buffers.getBuffer(model.renderType(getTextureLocation(entity)))
        model.renderToBuffer(pose, vc, light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f)
        pose.popPose()
        super.render(entity, yaw, partialTick, pose, buffers, light)
    }

    override fun getTextureLocation(entity: MagicMissileEntity): ResourceLocation = SPARK_TEX

    private companion object {
        private val SPARK_TEX = ResourceLocation("textures/entity/shulker/spark.png")
    }
}
