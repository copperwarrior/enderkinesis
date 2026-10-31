package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer

/**
 * A copy of what's been drawn to the screen so far, for a shader that shows the world behind it
 * moved (Black Hole Sun's pull on what's behind it). Taken as the shader is set up to draw, so
 * it holds everything drawn before it; a texture of our own, as [RefractionPass] keeps.
 */
object SceneCopy {
    private var texture = -1
    private var width = 0
    private var height = 0

    /** Copies the main screen as it stands into our texture, and returns the texture. */
    fun copy(): Int {
        val target = Minecraft.getInstance().mainRenderTarget
        val w = target.width
        val h = target.height
        if (w <= 0 || h <= 0) return texture
        ensure(w, h)
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId)
        GlStateManager._bindTexture(texture)
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)
        target.bindWrite(false)
        return texture
    }

    private fun ensure(w: Int, h: Int) {
        if (texture != -1 && width == w && height == h) return
        if (texture != -1) GlStateManager._deleteTexture(texture)
        texture = GlStateManager._genTexture()
        GlStateManager._bindTexture(texture)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
        width = w
        height = h
    }
}
