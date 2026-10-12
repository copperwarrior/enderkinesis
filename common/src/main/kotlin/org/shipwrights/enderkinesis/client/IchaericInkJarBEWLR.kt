package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.block.IchaericInkJarBlock
import org.shipwrights.enderkinesis.registry.EKBlocks

// An item model has no multipart, so the item draws the block state to show the ink level.
object IchaericInkJarBEWLR : BlockEntityWithoutLevelRenderer(
    Minecraft.getInstance().blockEntityRenderDispatcher,
    Minecraft.getInstance().entityModels,
) {
    override fun renderByItem(
        stack: ItemStack, context: ItemDisplayContext, poseStack: PoseStack,
        buffers: MultiBufferSource, packedLight: Int, packedOverlay: Int,
    ) {
        val state = EKBlocks.ICHAERIC_INK_JAR.get().defaultBlockState()
            .setValue(IchaericInkJarBlock.BOTTLES, IchaericInkJarBlock.bottlesOf(stack))
        // Not `renderSingleBlock`: with Fabulous graphics it selects the item-entity target for
        // a translucent block, and a slot in a GUI then shows nothing. This is the vanilla
        // rule of `ItemRenderer.render` for a block item.
        val mainTarget = context == ItemDisplayContext.GUI || context.firstPerson() ||
            state.block !is net.minecraft.world.level.block.HalfTransparentBlock
        val dispatcher = Minecraft.getInstance().blockRenderer
        dispatcher.modelRenderer.renderModel(
            poseStack.last(),
            buffers.getBuffer(ItemBlockRenderTypes.getRenderType(state, mainTarget)),
            state, dispatcher.getBlockModel(state),
            1f, 1f, 1f, packedLight, packedOverlay,
        )
    }
}
