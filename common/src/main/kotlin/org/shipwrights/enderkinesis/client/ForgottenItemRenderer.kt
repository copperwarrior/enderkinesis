package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import kotlin.math.abs
import com.mojang.math.Axis
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.util.Mth
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemDisplayContext
import org.joml.Matrix3f
import org.shipwrights.enderkinesis.blockentity.ForgottenItemBlockEntity

class ForgottenItemRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ForgottenItemBlockEntity> {

    private val itemRenderer: ItemRenderer = ctx.itemRenderer
    private val scratch = Matrix3f()

    override fun render(
        be: ForgottenItemBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val stack = be.item
        if (stack.isEmpty) return
        // Not ItemRenderer.getModel: it gives the in-hand 3D model for the spyglass and trident,
        // but render() draws their flat model in FIXED and GROUND.
        val base = itemRenderer.itemModelShaper.getItemModel(stack)
        val model = base.overrides.resolve(base, stack, be.level as? ClientLevel, null, be.blockPos.asLong().toInt())
            ?: base
        ps.pushPose()
        ps.translate(0.5, FLOOR_GAP, 0.5)
        // MC yaw is clockwise; JOML rotation about +Y is counter-clockwise.
        ps.mulPose(Axis.YP.rotationDegrees(-be.yaw))
        val context = if (model.isGui3d && stack.item is BlockItem) {
            val ground = model.transforms.ground
            val modelBottom = ground.translation.y() - HALF_MODEL * ground.scale.y()
            ps.translate(0f, -modelBottom * GROUND_SCALE, 0f)
            ps.scale(GROUND_SCALE, GROUND_SCALE, GROUND_SCALE)
            ItemDisplayContext.GROUND
        } else {
            ps.translate(0.0, if (model.isGui3d) fixedModelDepth(model) * SCALE else FLAT_MODEL_LIFT, 0.0)
            ps.mulPose(Axis.XP.rotationDegrees(90f))
            ps.scale(SCALE, SCALE, SCALE)
            ItemDisplayContext.FIXED
        }
        itemRenderer.render(stack, context, false, ps, bufferSource, packedLight, OverlayTexture.NO_OVERLAY, model)
        ps.popPose()
    }

    /** Distance from the model centre to its lowest point: the unit model cube under the FIXED transform. */
    private fun fixedModelDepth(model: BakedModel): Double {
        val fixed = model.transforms.fixed
        val rot = fixed.rotation
        scratch.rotationXYZ(rot.x() * Mth.DEG_TO_RAD, rot.y() * Mth.DEG_TO_RAD, rot.z() * Mth.DEG_TO_RAD)
        val scale = fixed.scale
        // Local +Z points into the floor after the 90 degree turn. JOML mCR: column C, row R.
        val extent = abs(scratch.m02 * scale.x()) + abs(scratch.m12 * scale.y()) + abs(scratch.m22 * scale.z())
        return (fixed.translation.z() + HALF_MODEL * extent).toDouble()
    }

    companion object {
        // Glint uses an EQUAL depth test with no cull. A model face coplanar with the floor
        // would let the glint draw on the floor block.
        private const val FLOOR_GAP = 0.005
        private const val SCALE = 1f
        // Half of the flat model thickness at SCALE, so the model rests on the floor.
        private const val FLAT_MODEL_LIFT = 1.0 / 32.0
        private const val HALF_MODEL = 0.5f
        // Vanilla GROUND shows a block at 1/4 size. This factor keeps it at 1/2 size.
        private const val GROUND_SCALE = 2f
    }
}
