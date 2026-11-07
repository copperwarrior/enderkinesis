package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/** A target's colour without its depth, so a shader can sample that depth without a GL feedback loop. */
object MainColorOnlyTarget {
    private var fbo = -1
    private var target: RenderTarget? = null
    private var colorTex = -1
    private var width = 0
    private var height = 0

    fun bind(main: RenderTarget) {
        if (fbo == -1) fbo = GlStateManager.glGenFramebuffers()
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        // A new texture can get a deleted one's id, so a new target or size counts as a change too.
        if (target !== main || colorTex != main.colorTextureId || width != main.width || height != main.height) {
            GlStateManager._glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, main.colorTextureId, 0,
            )
            target = main
            colorTex = main.colorTextureId
            width = main.width
            height = main.height
        }
    }
}
