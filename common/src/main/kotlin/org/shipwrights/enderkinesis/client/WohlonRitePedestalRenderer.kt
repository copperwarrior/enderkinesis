package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ItemDisplayContext
import org.shipwrights.enderkinesis.entity.WohlonRitePedestal

class WohlonRitePedestalRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<WohlonRitePedestal>(ctx) {

    private val itemRenderer: ItemRenderer = ctx.itemRenderer

    override fun render(
        entity: WohlonRitePedestal,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
    ) {
        WohlonRitePedestalVisuals.queue(entity, partialTick)
        WohlonStaffGhost.queue(entity, partialTick)
        val stack = entity.sacrifice
        if (stack.isEmpty || entity.staffSlots > 0) return
        val t = entity.tickCount + partialTick
        poseStack.pushPose()
        poseStack.translate(0.0, WohlonRitePedestal.CRADLE_HEIGHT + 0.15 + Math.sin(t * 0.1) * 0.05, 0.0)
        poseStack.mulPose(Axis.YP.rotationDegrees(t * 4f))
        poseStack.scale(0.6f, 0.6f, 0.6f)
        itemRenderer.renderStatic(
            stack, ItemDisplayContext.GROUND, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
            poseStack, buffer, entity.level(), entity.id,
        )
        poseStack.popPose()
    }

    override fun getTextureLocation(entity: WohlonRitePedestal): ResourceLocation = InventoryMenu.BLOCK_ATLAS
}
