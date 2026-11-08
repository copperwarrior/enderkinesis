package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.Sheets
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.block.OarscribeBlock
import org.shipwrights.enderkinesis.block.OarscribeFrame
import org.shipwrights.enderkinesis.blockentity.OarscribeBlockEntity
import org.shipwrights.enderkinesis.registry.EKItems

/** Draws the hand and the held penfeather, which move while the Oarscribe writes. */
class OarscribeRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<OarscribeBlockEntity> {

    private val itemRenderer = context.itemRenderer
    private val penfeather by lazy { ItemStack(EKItems.PENFEATHER_OF_THE_ACCURSED_OWL.get()) }

    // Render thread only.
    private val frame = Quaternionf()
    private val sway = Quaternionf()
    private val stroke = Quaternionf()

    override fun render(
        oarscribe: OarscribeBlockEntity, partialTick: Float, poseStack: PoseStack,
        buffers: MultiBufferSource, packedLight: Int, packedOverlay: Int,
    ) {
        val hand = handModel ?: return
        val state = oarscribe.blockState
        val writing = state.getValue(OarscribeBlock.WRITING)
        val time = ((oarscribe.level?.gameTime ?: 0L) % TIME_WRAP_TICKS) + partialTick
        poseStack.pushPose()
        poseStack.translate(HALF_BLOCK, HALF_BLOCK, HALF_BLOCK)
        poseStack.mulPose(frame.set(OarscribeFrame.mount(state)).mul(OarscribeFrame.handTurn(state)))
        poseStack.translate(-HALF_BLOCK, -HALF_BLOCK, -HALF_BLOCK)
        if (writing) swayHand(poseStack, time)
        // The hand texture has part-transparent pixels, which the cutout block layer draws opaque.
        Minecraft.getInstance().blockRenderer.modelRenderer.renderModel(
            poseStack.last(), buffers.getBuffer(Sheets.translucentCullBlockSheet()), state, hand,
            1f, 1f, 1f, packedLight, packedOverlay,
        )
        if (state.getValue(OarscribeBlock.PENFEATHER)) {
            poseStack.scale(PIXEL, PIXEL, PIXEL)
            val mirrored = state.getValue(OarscribeBlock.MIRRORED)
            OarscribeModel.applyPen(poseStack, mirrored, if (writing) strokeAt(time) else null)
            itemRenderer.renderStatic(
                penfeather, ItemDisplayContext.NONE, packedLight, packedOverlay, poseStack, buffers, oarscribe.level, 0,
            )
        }
        poseStack.popPose()
    }

    /** The arm rocks about its base along the line it writes, with a smaller nod at twice the rate. */
    private fun swayHand(poseStack: PoseStack, time: Float) {
        val phase = time * SWAY_RAD_PER_TICK
        sway.identity()
            .rotateZ(SWAY_RAD * Mth.sin(phase))
            .rotateX(NOD_RAD * Mth.sin(2f * phase))
        poseStack.translate(HAND_PIVOT_X * PIXEL, HAND_PIVOT_Y * PIXEL, HAND_PIVOT_Z * PIXEL)
        poseStack.mulPose(sway)
        poseStack.translate(-HAND_PIVOT_X * PIXEL, -HAND_PIVOT_Y * PIXEL, -HAND_PIVOT_Z * PIXEL)
    }

    /** Quick pen strokes about the grip. */
    private fun strokeAt(time: Float): Quaternionf =
        stroke.identity().rotateX(STROKE_RAD * Mth.sin(time * STROKE_RAD_PER_TICK))

    companion object {
        private const val HALF_BLOCK = 0.5f
        private const val PIXEL = 1f / 16f

        // The base of the hand, in model pixels.
        private const val HAND_PIVOT_X = 8f
        private const val HAND_PIVOT_Y = 3.25f
        private const val HAND_PIVOT_Z = 8f

        private val SWAY_RAD = Math.toRadians(6.0).toFloat()
        private val NOD_RAD = Math.toRadians(3.0).toFloat()
        private val STROKE_RAD = Math.toRadians(10.0).toFloat()

        /** One sway each 1.5 s. */
        private const val SWAY_RAD_PER_TICK = Mth.TWO_PI / 30f

        /** Five strokes each second. */
        private const val STROKE_RAD_PER_TICK = Mth.TWO_PI / 4f

        /** Keeps the float time small. Both periods divide it, so the wrap does not jump. */
        private const val TIME_WRAP_TICKS = 24_000L

        val HAND_MODEL_LOCATION: ResourceLocation = ResourceLocation("enderkinesis", "block/oarscribe_hand")

        /** Set by the loader bake hook. */
        @Volatile
        var handModel: BakedModel? = null
    }
}
