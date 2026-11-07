package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.model.WeheloStoneCreatureModel
import org.shipwrights.enderkinesis.entity.WeheloStoneCreature

class WeheloStoneCreatureRenderer(ctx: EntityRendererProvider.Context) :
    MobRenderer<WeheloStoneCreature, WeheloStoneCreatureModel>(
        ctx, WeheloStoneCreatureModel(ctx.bakeLayer(WeheloStoneCreatureModel.LAYER_LOCATION)), SHADOW_RADIUS,
    ) {

    init {
        addLayer(FadingItemLayer(this, ctx))
        addLayer(FogEyesLayer(this))
    }

    override fun getTextureLocation(entity: WeheloStoneCreature): ResourceLocation = TEXTURE

    override fun getRenderType(entity: WeheloStoneCreature, bodyVisible: Boolean, translucent: Boolean, glowing: Boolean): RenderType? {
        val base = super.getRenderType(entity, bodyVisible, translucent, glowing) ?: return null
        return if (model.alpha < 1f) RenderType.entityTranslucent(TEXTURE) else base
    }

    /** Item models take no alpha, so the blade goes when the body is half gone. */
    private class FadingItemLayer(
        parent: RenderLayerParent<WeheloStoneCreature, WeheloStoneCreatureModel>,
        ctx: EntityRendererProvider.Context,
    ) : ItemInHandLayer<WeheloStoneCreature, WeheloStoneCreatureModel>(parent, ctx.itemInHandRenderer) {

        override fun render(
            poseStack: PoseStack, buffer: MultiBufferSource, packedLight: Int, entity: WeheloStoneCreature,
            limbSwing: Float, limbSwingAmount: Float, partialTick: Float, ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
        ) {
            if (parentModel.alpha < HALF) return
            super.render(poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch)
        }
    }

    /** The pocket fog eyes, fixed to the face. */
    private class FogEyesLayer(
        parent: RenderLayerParent<WeheloStoneCreature, WeheloStoneCreatureModel>,
    ) : RenderLayer<WeheloStoneCreature, WeheloStoneCreatureModel>(parent) {

        override fun render(
            poseStack: PoseStack,
            buffer: MultiBufferSource,
            packedLight: Int,
            entity: WeheloStoneCreature,
            limbSwing: Float,
            limbSwingAmount: Float,
            partialTick: Float,
            ageInTicks: Float,
            netHeadYaw: Float,
            headPitch: Float,
        ) {
            if (entity.isInvisible) return
            val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
            val rgb = WeheloPocketClient.eyeRgb(camera.distanceTo(entity.getEyePosition(partialTick)))
            // FACE_GLOW is additive, so a fade must scale the colour, not only the alpha.
            val fade = parentModel.alpha
            val glow = buffer.getBuffer(AegisRenderType.FACE_GLOW)
            poseStack.pushPose()
            parentModel.translateToHead(poseStack)
            val matrix = poseStack.last().pose()
            val half = (WeheloPocketClient.EYE_GAP * 0.5).toFloat()
            square(glow, matrix, -half, rgb, fade)
            square(glow, matrix, half, rgb, fade)
            poseStack.popPose()
        }

        private fun square(c: VertexConsumer, m: Matrix4f, x: Float, rgb: Int, fade: Float) {
            val r = (rgb shr 16 and 0xFF) / 255f * fade
            val g = (rgb shr 8 and 0xFF) / 255f * fade
            val b = (rgb and 0xFF) / 255f * fade
            val h = WeheloPocketClient.EYE_HALF.toFloat()
            c.vertex(m, x - h, EYE_Y - h, FACE_Z).color(r, g, b, fade).endVertex()
            c.vertex(m, x + h, EYE_Y - h, FACE_Z).color(r, g, b, fade).endVertex()
            c.vertex(m, x + h, EYE_Y + h, FACE_Z).color(r, g, b, fade).endVertex()
            c.vertex(m, x - h, EYE_Y + h, FACE_Z).color(r, g, b, fade).endVertex()
        }
    }

    companion object {
        private const val SHADOW_RADIUS = 0.5f
        private const val HALF = 0.5f
        /** Head space, in blocks: the model is upside down here, so up is -Y, and the face is at -Z. */
        private const val EYE_Y = -0.28f
        private const val FACE_Z = -0.252f
        private val TEXTURE = ResourceLocation("textures/block/stone.png")
    }
}
