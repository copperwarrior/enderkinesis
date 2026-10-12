package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.item.ParallelographItem

/** Draws the frame, then the `main_arm` group turned about the hinge frame element. */
object ParallelographBEWLR : BlockEntityWithoutLevelRenderer(
    Minecraft.getInstance().blockEntityRenderDispatcher,
    Minecraft.getInstance().entityModels,
) {
    @JvmField val BASE_MODEL_LOC = ResourceLocation("enderkinesis", "item/parallelograph_base")
    @JvmField val ARM_MODEL_LOC = ResourceLocation("enderkinesis", "item/parallelograph_arm")

    @Volatile @JvmField var baseModel: BakedModel? = null
    @Volatile @JvmField var armModel: BakedModel? = null

    // Centre of the hinge "frame" element (from 6,0,13 to 9,3,16, turned 45° on Y about 8,1,14),
    // in block units: its unrotated centre (7.5, 14.5) lands at (8, 14 + √2 / 2).
    private const val PIVOT_X = 8f / 16f
    private const val PIVOT_Z = (14f + 0.70710677f) / 16f

    override fun renderByItem(
        stack: ItemStack, context: ItemDisplayContext, poseStack: PoseStack,
        buffers: MultiBufferSource, packedLight: Int, packedOverlay: Int,
    ) {
        baseModel?.let { BakedItemModels.emit(it, poseStack, buffers, packedLight, packedOverlay) }
        val arm = armModel ?: return
        poseStack.pushPose()
        poseStack.translate(PIVOT_X, 0f, PIVOT_Z)
        val yaw = ParallelographItem.armYawDegrees(stack, Minecraft.getInstance().player, Util.getMillis())
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw))
        poseStack.translate(-PIVOT_X, 0f, -PIVOT_Z)
        BakedItemModels.emit(arm, poseStack, buffers, packedLight, packedOverlay)
        poseStack.popPose()
    }
}
