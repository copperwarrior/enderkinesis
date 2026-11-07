package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3fc
import org.shipwrights.enderkinesis.block.OarscribePenPose
import kotlin.math.atan2
import kotlin.math.hypot

/** The held pen in model pixels, before the hand turn and the blockstate mount. */
object OarscribeModel {

    // In the item texture the point is the centre of pixel (3, 14) and the feather top is pixel (12, 2).
    private const val ITEM_PIXELS = 16f
    private const val POINT_X = 3.5f / ITEM_PIXELS
    private const val POINT_Y = 1.5f / ITEM_PIXELS
    private const val TOP_X = 12.5f / ITEM_PIXELS
    private const val TOP_Y = 13.5f / ITEM_PIXELS
    private const val HALF = 0.5f
    private const val MODEL_PIXELS = 16f

    /** Turns the diagonal pen texture so the point is at item -Y. */
    private val POINT_DOWN: Quaternionf = Quaternionf().rotateZ(atan2(TOP_X - POINT_X, TOP_Y - POINT_Y))

    /** One pose of the pen, plain or mirrored across model X. */
    private class Rig {
        val centre = Vector3f()
        val tilt = Quaternionf()

        /** Item -Y (the point) to model +Y, out of the hand, and the item face to model west. */
        val pointOut: Quaternionf = Quaternionf().rotateX(radians(180f)).rotateY(radians(-90f))
        var scale = 1f
        val point = Vector3f()

        // A true mirror turns the item faces inside out and the texture back to front. Mirroring only the centre
        // and the tilt gives the same outline with the texture the right way round.
        fun set(pose: OarscribePenPose, mirrored: Boolean) {
            val sign = if (mirrored) -1f else 1f
            centre.set(if (mirrored) MODEL_PIXELS - pose.centreX else pose.centreX, pose.centreY, pose.centreZ)
            // Blockbench applies X first, then Y, then Z. pointOut puts the pen along model Y, so the spin goes last.
            tilt.identity()
                .rotateZ(sign * radians(pose.rotZ))
                .rotateY(sign * radians(pose.rotY))
                .rotateX(radians(pose.rotX))
                .rotateY(sign * radians(pose.spin))
            scale = pose.length / hypot(TOP_X - POINT_X, TOP_Y - POINT_Y)
            val stack = PoseStack()
            apply(stack, null)
            stack.last().pose().transformPosition(point.set(POINT_X - HALF, POINT_Y - HALF, 0f))
        }

        fun apply(poseStack: PoseStack, stroke: Quaternionf?) {
            poseStack.translate(centre.x, centre.y, centre.z)
            stroke?.let(poseStack::mulPose)
            poseStack.mulPose(tilt)
            poseStack.mulPose(pointOut)
            poseStack.scale(scale, scale, scale)
            poseStack.mulPose(POINT_DOWN)
            // ItemRenderer centres the item model on the origin before it draws.
            poseStack.translate(HALF - (POINT_X + TOP_X) / 2f, HALF - (POINT_Y + TOP_Y) / 2f, 0f)
        }
    }

    // Render thread only.
    private val plain = Rig().apply { set(OarscribePenPose.DEFAULT, mirrored = false) }
    private val mirror = Rig().apply { set(OarscribePenPose.DEFAULT, mirrored = true) }

    /** The pen point, in model pixels. */
    fun penPoint(mirrored: Boolean): Vector3fc = rig(mirrored).point

    /**
     * Call with the pose stack in model pixels; then draw the item with ItemDisplayContext.NONE.
     * [stroke] turns the pen about its grip, on top of the rest pose.
     */
    fun applyPen(poseStack: PoseStack, mirrored: Boolean, stroke: Quaternionf? = null) =
        rig(mirrored).apply(poseStack, stroke)

    private fun rig(mirrored: Boolean): Rig = if (mirrored) mirror else plain

    private fun radians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()
}
