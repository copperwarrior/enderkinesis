package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * Scene depth for the volume passes: main depth from just before particles, plus clouds. Particles write
 * depth for each solid sprite texel, so a march stopped at main depth cut a hole behind each particle.
 * Render thread only.
 */
object VolumeDepth {
    private val copy = DepthTextureCopy()

    // A volume sampled depth since the last capture point. The copy starts one frame after a volume appears.
    private var wanted = false
    private var captured = false
    private var prevFbo = 0

    /** Call at renderLevel HEAD, so a frame that skips the capture point never reads a stale copy. */
    fun beginFrame() {
        captured = false
    }

    /** Call just before the particle pass. */
    fun capture(main: RenderTarget) {
        if (!wanted) return
        wanted = false
        copy.copyFrom(main)
        captured = true
    }

    /** True when the caller must draw the clouds again, into the copy, and then call [endCloudReplay]. */
    fun beginCloudReplay(): Boolean {
        if (!captured) return false
        prevFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, copy.fbo)
        return true
    }

    fun endCloudReplay() {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo)
    }

    /** The depth texture a volume pass samples; main depth until a copy exists this frame. */
    fun textureId(main: RenderTarget): Int {
        wanted = true
        return if (captured) copy.textureId else main.depthTextureId
    }
}
