package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.client.model.HeartOfTheWildModel

// The block has no static model; the BER draws the heart, so the item must draw it too.
object HeartOfTheWildBEWLR : BlockEntityWithoutLevelRenderer(
    Minecraft.getInstance().blockEntityRenderDispatcher,
    Minecraft.getInstance().entityModels,
) {
    private val model by lazy {
        HeartOfTheWildModel(Minecraft.getInstance().entityModels.bakeLayer(HeartOfTheWildModel.LAYER_LOCATION))
    }

    override fun renderByItem(
        stack: ItemStack, context: ItemDisplayContext, poseStack: PoseStack,
        buffers: MultiBufferSource, packedLight: Int, packedOverlay: Int,
    ) {
        model.animateBeatMillis(Util.getMillis(), 1.0f)
        poseStack.pushPose()
        HeartOfTheWildRenderer.poseHeart(poseStack, false, 1f, 0f)
        val consumer = buffers.getBuffer(RenderType.entityTranslucent(HeartOfTheWildRenderer.TEXTURE))
        model.renderToBuffer(poseStack, consumer, packedLight, packedOverlay, 1f, 1f, 1f, 1f)
        poseStack.popPose()
    }
}
