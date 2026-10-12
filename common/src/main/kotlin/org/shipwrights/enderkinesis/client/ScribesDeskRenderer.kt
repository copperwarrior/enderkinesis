package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.block.ScribesDeskBlock
import org.shipwrights.enderkinesis.blockentity.ScribesDeskBlockEntity
import org.shipwrights.enderkinesis.registry.EKItems

/** Draws the penfeather. Its poses are not on one axis, which a block model element cannot do. */
class ScribesDeskRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<ScribesDeskBlockEntity> {

    private val itemRenderer = context.itemRenderer
    private val penfeather by lazy { ItemStack(EKItems.PENFEATHER_OF_THE_ACCURSED_OWL.get()) }

    override fun render(
        desk: ScribesDeskBlockEntity, partialTick: Float, poseStack: PoseStack,
        buffers: MultiBufferSource, packedLight: Int, packedOverlay: Int,
    ) {
        val state = desk.blockState
        if (!state.getValue(ScribesDeskBlock.PENFEATHER)) return
        val pose = if (state.getValue(ScribesDeskBlock.INK)) IN_INKWELL else ON_DESK
        poseStack.pushPose()
        poseStack.translate(0.5f, 0f, 0.5f)
        poseStack.mulPose(FACING_ROTATIONS[state.getValue(ScribesDeskBlock.FACING).get2DDataValue()])
        poseStack.translate(-0.5f, 0f, -0.5f)
        poseStack.scale(PIXEL, PIXEL, PIXEL)
        poseStack.translate(pose.pivotX, pose.pivotY, pose.pivotZ)
        poseStack.mulPose(pose.rotation)
        poseStack.translate(pose.centreX - pose.pivotX, pose.centreY - pose.pivotY, pose.centreZ - pose.pivotZ)
        poseStack.mulPose(LIE_FLAT)
        // A flat item model is 1 block wide and 1/16 block thick. A scale of 0 breaks the normals.
        poseStack.scale(SIZE_X, SIZE_Z, THICKNESS * 16f)
        itemRenderer.renderStatic(
            penfeather, ItemDisplayContext.NONE, packedLight, packedOverlay, poseStack, buffers, desk.level, 0,
        )
        poseStack.popPose()
    }

    /** A Blockbench cube pose in model pixels: corner position, pivot, and XYZ rotation in degrees. */
    @Suppress("LongParameterList") // The nine values are one Blockbench transform.
    private class Pose(
        x: Float, y: Float, z: Float,
        val pivotX: Float, val pivotY: Float, val pivotZ: Float,
        rotX: Float, rotY: Float, rotZ: Float,
    ) {
        val centreX = x + SIZE_X / 2f
        val centreY = y + LIFT
        val centreZ = z + SIZE_Z / 2f

        // Blockbench applies X first, then Y, then Z.
        val rotation: Quaternionf = Quaternionf()
            .rotateZ(Math.toRadians(rotZ.toDouble()).toFloat())
            .rotateY(Math.toRadians(rotY.toDouble()).toFloat())
            .rotateX(Math.toRadians(rotX.toDouble()).toFloat())
    }

    companion object {
        private const val PIXEL = 1f / 16f
        private const val SIZE_X = 7f
        private const val THICKNESS = 0.02f
        // Keeps the flat texture off the desk top, which is at the bottom of the Blockbench cube.
        private const val LIFT = 0.05f
        private const val SIZE_Z = 7f

        private val ON_DESK = Pose(0f, 14f, 1f, 4f, 14f, 5f, 0f, 77.5f, 0f)
        private val IN_INKWELL = Pose(-2.75f, 20.25f, 7.25f, 1.25f, 20.25f, 11.25f, -101.6797f, 49.9418f, 173.8322f)

        // The texture faces up, with its top to model north.
        private val LIE_FLAT: Quaternionf = Quaternionf().rotateX(Math.toRadians(-90.0).toFloat())

        // The model front is north. A blockstate "y" turn is clockwise from above, and toYRot has south at 0.
        private val FACING_ROTATIONS: Array<Quaternionf> = Array(4) { index ->
            Quaternionf().rotateY(Math.toRadians(180.0 - Direction.from2DDataValue(index).toYRot()).toFloat())
        }
    }
}
