package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.registry.EKBlocks

// A builtin/entity item model draws nothing static, so this draws the pedestal too.
object QuasarOfPurityBEWLR : BlockEntityWithoutLevelRenderer(
    Minecraft.getInstance().blockEntityRenderDispatcher,
    Minecraft.getInstance().entityModels,
) {
    private val quasar = QuasarOfPurityRenderer()

    override fun renderByItem(
        stack: ItemStack, context: ItemDisplayContext, poseStack: PoseStack,
        buffers: MultiBufferSource, packedLight: Int, packedOverlay: Int,
    ) {
        val state = EKBlocks.QUASAR_OF_PURITY.get().defaultBlockState()
        Minecraft.getInstance().blockRenderer.renderSingleBlock(state, poseStack, buffers, packedLight, packedOverlay)
        // The pedestal is in a fixed buffer that draws at batch end, after the glow, which writes no depth.
        // Draw it now so the glow depth-tests against it, as the chunk mesh does in the world.
        (buffers as? MultiBufferSource.BufferSource)?.endBatch(ItemBlockRenderTypes.getRenderType(state, false))
        // Menus have no level, so the item spins on wall-clock time.
        val t = (Util.getMillis() % (QuasarOfPurityRenderer.SPIN_PERIOD_TICKS * MS_PER_TICK)) / MS_PER_TICK.toFloat()
        quasar.drawQuasar(poseStack, buffers, Direction.UP, t)
    }

    private const val MS_PER_TICK = 50L
}
