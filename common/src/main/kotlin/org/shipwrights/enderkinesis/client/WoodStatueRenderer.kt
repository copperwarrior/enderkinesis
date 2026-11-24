package org.shipwrights.enderkinesis.client

import com.mojang.authlib.minecraft.MinecraftProfileTexture
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import org.shipwrights.enderkinesis.blockentity.WoodStatueBlockEntity

class WoodStatueRenderer(ctx: BlockEntityRendererProvider.Context) : BlockEntityRenderer<WoodStatueBlockEntity> {

    private val wide = PlayerModel<LivingEntity>(ctx.bakeLayer(ModelLayers.PLAYER), false)
    private val slim = PlayerModel<LivingEntity>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true)

    override fun render(
        be: WoodStatueBlockEntity, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource,
        packedLight: Int, packedOverlay: Int,
    ) {
        val skin = be.skin ?: resolveSkin(be) ?: return
        val model = if (be.slimSkin) slim else wide
        model.head.yRot = Math.toRadians(be.headYaw.toDouble()).toFloat()
        model.head.xRot = Math.toRadians(be.headPitch.toDouble()).toFloat()
        model.hat.copyFrom(model.head)

        pose.pushPose()
        // The same transform as LivingEntityRenderer for a player.
        pose.translate(0.5, 0.0, 0.5)
        pose.mulPose(Axis.YP.rotationDegrees(180f - be.bodyYaw))
        pose.scale(-PLAYER_SCALE, -PLAYER_SCALE, PLAYER_SCALE)
        pose.translate(0.0, MODEL_DROP, 0.0)
        model.renderToBuffer(
            pose, buffers.getBuffer(RenderType.entityCutoutNoCull(skin)), packedLight, OverlayTexture.NO_OVERLAY,
            WOOD_RED, WOOD_GREEN, WOOD_BLUE, 1f,
        )
        pose.popPose()
    }

    private fun resolveSkin(be: WoodStatueBlockEntity): ResourceLocation? {
        val profile = be.owner ?: return null
        val skins = Minecraft.getInstance().skinManager
        val texture = skins.getInsecureSkinInformation(profile)[MinecraftProfileTexture.Type.SKIN]
        if (texture != null) {
            be.slimSkin = texture.getMetadata("model") == "slim"
            be.skin = skins.registerTexture(texture, MinecraftProfileTexture.Type.SKIN)
        } else {
            val uuid = UUIDUtil.getOrCreatePlayerUUID(profile)
            be.slimSkin = DefaultPlayerSkin.getSkinModelName(uuid) == "slim"
            be.skin = DefaultPlayerSkin.getDefaultSkin(uuid)
        }
        return be.skin
    }

    private companion object {
        const val PLAYER_SCALE = 0.9375f
        const val MODEL_DROP = -1.501

        // Placeholder wogor wood tint over the skin; no carved-wood art exists.
        const val WOOD_RED = 0.62f
        const val WOOD_GREEN = 0.45f
        const val WOOD_BLUE = 0.30f
    }
}
