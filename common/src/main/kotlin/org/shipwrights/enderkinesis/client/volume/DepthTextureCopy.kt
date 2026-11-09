package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import java.nio.ByteBuffer
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30

/**
 * A depth texture that holds a copy of main depth from one point in the frame. A [colourFormat] above 0 also copies main
 * colour into a texture of that format. Render thread only.
 */
class DepthTextureCopy(private val colourFormat: Int = 0) {
    var fbo = -1
        private set
    var textureId = -1
        private set
    var colourTextureId = -1
        private set
    private var width = 0
    private var height = 0
    private var internalFormat = 0
    private var mainTexture = -1

    /** Copies main depth, and colour when [colourFormat] is above 0, now. The framebuffer that was bound stays bound. */
    fun copyFrom(main: RenderTarget) {
        // Fabulous binds its own targets around some passes, so restore what was bound, not main.
        val prevFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        ensure(main)
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo)
        val bits = if (colourFormat > 0) GL11.GL_DEPTH_BUFFER_BIT or GL11.GL_COLOR_BUFFER_BIT else GL11.GL_DEPTH_BUFFER_BIT
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, bits, GL11.GL_NEAREST)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo)
    }

    private fun ensure(main: RenderTarget) {
        // A GL query each frame stalls on the GPU; main depth changes only with a new texture or a resize.
        if (fbo != -1 && main.depthTextureId == mainTexture && main.width == width && main.height == height) return
        // A depth blit needs equal formats, and a mod can give main a depth-stencil buffer.
        GlStateManager._bindTexture(main.depthTextureId)
        val mainFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
        mainTexture = main.depthTextureId
        if (fbo != -1 && main.width == width && main.height == height && mainFormat == internalFormat) return
        if (textureId == -1) textureId = GlStateManager._genTexture()
        if (fbo == -1) fbo = GlStateManager.glGenFramebuffers()
        val stencil = mainFormat == GL30.GL_DEPTH24_STENCIL8 || mainFormat == GL30.GL_DEPTH32F_STENCIL8
        val format = if (stencil) GL30.GL_DEPTH_STENCIL else GL11.GL_DEPTH_COMPONENT
        val type = when (mainFormat) {
            GL30.GL_DEPTH24_STENCIL8 -> GL30.GL_UNSIGNED_INT_24_8
            GL30.GL_DEPTH32F_STENCIL8 -> GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV
            else -> GL11.GL_FLOAT
        }
        GlStateManager._bindTexture(textureId)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, mainFormat, main.width, main.height, 0, format, type, null as ByteBuffer?)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        val attachment = if (stencil) GL30.GL_DEPTH_STENCIL_ATTACHMENT else GL30.GL_DEPTH_ATTACHMENT
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, textureId, 0)
        if (colourFormat > 0) {
            if (colourTextureId == -1) colourTextureId = GlStateManager._genTexture()
            VolumeTextures.allocate(colourTextureId, colourFormat, main.width, main.height, GL11.GL_RGBA, GL11.GL_FLOAT, GL11.GL_NEAREST, clampToEdge = true)
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colourTextureId, 0)
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0)
        } else {
            // Depth only: a colour pass into this framebuffer must write nothing.
            GL11.glDrawBuffer(GL11.GL_NONE)
        }
        GL11.glReadBuffer(GL11.GL_NONE)
        width = main.width
        height = main.height
        internalFormat = mainFormat
    }
}
