package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.player.PlayerRenderer
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import org.joml.Quaternionf
import org.joml.Vector3f
import org.shipwrights.enderkinesis.item.SilverpointItem
import kotlin.math.tan

/** The artist's frame: while the viewfinder is held, both bare first-person arms raise to opposite corners of the frame. */
object SilverpointHands {
    // Vanilla draws first-person hands with a fixed 70° field of view, whatever the player's setting.
    private const val HAND_FOV_DEGREES = 70f
    private const val DEPTH = 0.8f
    private const val RAISE_TICKS = 4f
    private const val LOWERED = 0.6f

    // The arm model's hand end in its own space (pixels / 16): Y runs from the shoulder to the hand.
    private const val ARM_END_Y = 12f / 16f
    private const val RIGHT_ARM_X = -6f / 16f
    private const val LEFT_ARM_X = 6f / 16f

    // Half the arm's width, so the arm lies outside the frame with its inner edge on the corner.
    private const val HALF_ARM = 2f / 16f

    // Arm directions in view space, shoulder to hand. The left comes up from below the screen. The right comes in from the
    // side: from below, its hand and forearm lie over the frame's top-right corner.
    private val LEFT_REACH = Vector3f(0.3f, 0.8f, -0.5f).normalize()
    private val RIGHT_REACH = Vector3f(-0.8f, 0.45f, -0.45f).normalize()

    // Render thread only.
    private val target = Vector3f()
    private val rotation = Quaternionf()

    fun framing(player: AbstractClientPlayer): Boolean =
        player.isLocalPlayer && player.isUsingItem && player.useItem.item is SilverpointItem

    /** Draws [arm] with its hand at its frame corner: the left at the bottom left, the right at the top right. */
    @JvmStatic
    fun render(player: AbstractClientPlayer, arm: HumanoidArm, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val window = Minecraft.getInstance().window
        val width = window.width.toFloat()
        val height = window.height.toFloat()
        if (width <= 0f || height <= 0f) return
        // The frame is square, so its half side at the hands' depth is the same across and up.
        val halfSide = minOf(width, height) * SilverpointView.frameZoom() / height *
            DEPTH * tan(Math.toRadians(HAND_FOV_DEGREES / 2.0)).toFloat()
        val sign = if (arm == HumanoidArm.RIGHT) 1f else -1f
        val raise = Mth.clamp((player.ticksUsingItem + partialTick) / RAISE_TICKS, 0f, 1f)
        val eased = raise * raise * (3f - 2f * raise)
        target.set(sign * (halfSide + HALF_ARM), sign * halfSide - (1f - eased) * LOWERED, -DEPTH)
        val reach = if (arm == HumanoidArm.RIGHT) RIGHT_REACH else LEFT_REACH
        rotation.rotationTo(0f, 1f, 0f, reach.x, reach.y, reach.z)
        val renderer = Minecraft.getInstance().entityRenderDispatcher.getRenderer(player) as? PlayerRenderer ?: return
        pose.pushPose()
        pose.translate(target.x, target.y, target.z)
        pose.mulPose(rotation)
        pose.translate(-(if (arm == HumanoidArm.RIGHT) RIGHT_ARM_X else LEFT_ARM_X), -ARM_END_Y, 0f)
        if (arm == HumanoidArm.RIGHT) {
            renderer.renderRightHand(pose, buffers, light, player)
        } else {
            renderer.renderLeftHand(pose, buffers, light, player)
        }
        pose.popPose()
    }
}
