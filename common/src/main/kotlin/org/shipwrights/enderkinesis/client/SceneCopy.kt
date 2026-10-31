package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer

/** Render thread only. A pass samples this copy because it cannot read main while it draws into main. */
object SceneCopy {
    var textureId = -1
        private set
    private var width = 0
    private var height = 0

    /** Returns false when main has no size. Then [textureId] holds no scene. */
    fun copy(): Boolean {
        val target = Minecraft.getInstance().mainRenderTarget
        val w = target.width
        val h = target.height
        if (w <= 0 || h <= 0) return false
        ensure(w, h)
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId)
        GlStateManager._bindTexture(textureId)
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)
        target.bindWrite(false)
        return true
    }

    private fun ensure(w: Int, h: Int) {
        if (textureId != -1 && width == w && height == h) return
        if (textureId != -1) GlStateManager._deleteTexture(textureId)
        textureId = GlStateManager._genTexture()
        GlStateManager._bindTexture(textureId)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
        width = w
        height = h
    }
}
