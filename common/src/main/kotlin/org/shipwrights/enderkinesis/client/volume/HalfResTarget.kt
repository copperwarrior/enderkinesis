package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.EffectInstance
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.shipwrights.enderkinesis.client.RefractionPass

/**
 * Half-size target for a volume march. The default RG16F holds R = alpha, G = linear depth of the scene
 * sample the march used. A depth-aware upsample through [VolumeComposite] puts the result on main.
 */
class HalfResTarget(
    private val internalFormat: Int = GL30.GL_RG16F,
    private val format: Int = GL30.GL_RG,
) {
    var textureId = -1
        private set
    var width = 0
        private set
    var height = 0
        private set
    private var fbo = -1
    private val clearValue = FloatArray(4)

    fun draw(main: RenderTarget, effect: EffectInstance, pushUniforms: (EffectInstance) -> Unit) {
        bind(main)
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()
        RenderSystem.depthMask(false)
        RenderSystem.disableBlend()
        RefractionPass.drawQuad(effect, width, height, pushUniforms)
    }

    /** Binds this target and its viewport, at the size for [main]. */
    fun bind(main: RenderTarget) {
        ensure(main.width, main.height)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GlStateManager._viewport(0, 0, width, height)
    }

    /** Clears the bound target to 0 inside the scissor box. Leaves the vanilla clear colour as it is. */
    fun clear() {
        // glClearBuffer obeys the colour mask; set it through the GlStateManager cache.
        RenderSystem.colorMask(true, true, true, true)
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, clearValue)
    }

    fun pushHalfSize(effect: EffectInstance) = effect.safeGetUniform("HalfSize").set(width.toFloat(), height.toFloat())

    private fun ensure(mainWidth: Int, mainHeight: Int) {
        val w = (mainWidth + 1) / 2
        val h = (mainHeight + 1) / 2
        if (fbo != -1 && w == width && h == height) return
        if (textureId == -1) textureId = GlStateManager._genTexture()
        if (fbo == -1) fbo = GlStateManager.glGenFramebuffers()
        VolumeTextures.allocate(textureId, internalFormat, w, h, format, GL11.GL_FLOAT, GL11.GL_NEAREST)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textureId, 0)
        width = w
        height = h
    }
}
