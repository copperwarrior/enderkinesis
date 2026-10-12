package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.core.BlockPos
import org.lwjgl.opengl.GL11
import org.shipwrights.enderkinesis.registry.EKFluids

/** The inky purple-black (#0a000a) fog and screen tint seen with the camera in Ichaeric Ink. */
object IchaericInkVisuals {

    const val RED = 0x0A / 255f
    const val GREEN = 0x00 / 255f
    const val BLUE = 0x0A / 255f

    // Dense: the ink should swallow sight within a few blocks.
    const val FOG_START = 0f
    const val FOG_END = 6f

    private const val OVERLAY_ALPHA = 0.6f

    @JvmStatic
    fun cameraInInk(): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        val eye = Minecraft.getInstance().gameRenderer.mainCamera.position
        val pos = BlockPos.containing(eye)
        val fluid = level.getFluidState(pos)
        return fluid.type.isSame(EKFluids.ICHAERIC_INK.get()) && eye.y < pos.y + fluid.getHeight(level, pos)
    }

    /** A full-screen tint in clip space, drawn the way vanilla draws its underwater overlay. */
    @JvmStatic
    fun renderOverlay(poseStack: PoseStack) {
        if (!cameraInInk() || Minecraft.getInstance().player?.isSpectator == true) return
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.depthFunc(GL11.GL_ALWAYS)
        RenderSystem.depthMask(false)
        val buffer = Tesselator.getInstance().builder
        val pose = poseStack.last().pose()
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        buffer.vertex(pose, -1f, -1f, -0.5f).color(RED, GREEN, BLUE, OVERLAY_ALPHA).endVertex()
        buffer.vertex(pose, 1f, -1f, -0.5f).color(RED, GREEN, BLUE, OVERLAY_ALPHA).endVertex()
        buffer.vertex(pose, 1f, 1f, -0.5f).color(RED, GREEN, BLUE, OVERLAY_ALPHA).endVertex()
        buffer.vertex(pose, -1f, 1f, -0.5f).color(RED, GREEN, BLUE, OVERLAY_ALPHA).endVertex()
        BufferUploader.drawWithShader(buffer.end())
        RenderSystem.depthMask(true)
        RenderSystem.depthFunc(GL11.GL_LEQUAL)
        RenderSystem.disableBlend()
    }
}
