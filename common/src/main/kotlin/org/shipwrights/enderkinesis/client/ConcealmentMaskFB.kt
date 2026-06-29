package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.logging.LogUtils
import java.nio.ByteBuffer
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30

/** Off-screen two-channel (RG8) mask framebuffer used as the Staff of Concealment
 *  refraction stencil. Each cloaking ship's bubble ellipsoid is rendered with
 *  GL_MAX blending so overlapping bubbles take the dominant cloak's progress.
 *
 *  Channel encoding (written by [ConcealmentBubbleRenderer]):
 *  - R = cloak progress at this pixel (0..1). Overlapping ships → max wins.
 *  - G = silhouette indicator (1.0 inside any bubble, blurred at the edge).
 *
 *  Per-pixel progress lets every ship's bubble composite at its own progress
 *  in the shader, instead of every bubble tracking the dominant cloak via a
 *  global uniform.
 *
 *  Depth is **shared with the main framebuffer** so the bubble auto-occludes against
 *  world geometry. Anything in front of the bubble (e.g. a wall between the camera and
 *  the cloaked ship) blocks the bubble write at that pixel, which keeps the mask honest:
 *  the refraction only applies where the ship would actually be visible.
 *
 *  Lifecycle:
 *  - Lazily allocated by [bindForWrite] on first use.
 *  - Resized to the main render target's dimensions on every bind (so window resize is a
 *    no-op except for one bind cycle where we recreate the color attachment).
 *  - Destroyed by [destroy] on client teardown / world-leave. */
object ConcealmentMaskFB {

    private val LOG = LogUtils.getLogger()

    private var fbId: Int = -1
    private var colorId: Int = -1
    private var depthId: Int = -1
    private var width: Int = 0
    private var height: Int = 0

    /** OpenGL texture id of the mask's red-channel color attachment. Pushed to the
     *  post-shader as the `MaskSampler` uniform. */
    val colorTextureId: Int
        get() = colorId

    /** Bind the mask FB for writing and clear color + own depth. Resizes to match
     *  the main FB if the window dimensions have changed. Caller is responsible for
     *  rebinding the main FB when done.
     *
     *  After bind, caller MUST call [copyMainDepth] to seed the own-depth
     *  attachment with current main FB depth — so bubbles still occlude
     *  against world geometry. Bubbles then write their own depth as they
     *  draw, so multiple cloak bubbles depth-occlude each other in screen
     *  space (the closest bubble at each pixel wins). */
    fun bindForWrite() {
        val mc = Minecraft.getInstance()
        val mainW = mc.mainRenderTarget.width
        val mainH = mc.mainRenderTarget.height
        if (fbId == -1 || width != mainW || height != mainH) {
            allocate(mainW, mainH)
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbId)
        // Own depth attachment is set at allocate time; no re-attach needed.
        // Clear color (to transparent) — the bubble pass writes mask data on
        // top. Depth is seeded later by [copyMainDepth]; don't pre-clear it
        // here.
        RenderSystem.colorMask(true, true, true, true)
        RenderSystem.clearColor(0f, 0f, 0f, 0f)
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, false)
        // Depth test + WRITES — bubbles need to occlude each other (front
        // bubble wins) so per-pixel progress carries the closest cloak's
        // value, not max across overlapping bubbles.
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(GL11.GL_LEQUAL)
        RenderSystem.depthMask(true)
        GlStateManager._viewport(0, 0, mainW, mainH)
    }

    /** Blit the main FB's depth into the mask FB's OWN depth attachment.
     *  Call once per bubble pass, AFTER [bindForWrite], so bubbles depth-test
     *  against current world geometry (terrain occludes bubbles) while their
     *  own depth writes occlude each other within the mask FB.
     *
     *  Restores the mask FB as the bound DRAW framebuffer before returning. */
    fun copyMainDepth() {
        val mc = Minecraft.getInstance()
        val mainW = mc.mainRenderTarget.width
        val mainH = mc.mainRenderTarget.height
        if (fbId == -1 || width != mainW || height != mainH) {
            allocate(mainW, mainH)
        }
        val mainFb = mc.mainRenderTarget.frameBufferId
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainFb)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbId)
        GL30.glBlitFramebuffer(
            0, 0, mainW, mainH,
            0, 0, mainW, mainH,
            GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST,
        )
        // Rebind mask FB as the active DRAW target for the bubble pass.
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbId)
    }

    /** Bind the main FB back. Called after the bubble pass. */
    fun unbind() {
        Minecraft.getInstance().mainRenderTarget.bindWrite(true)
    }

    fun destroy() {
        if (colorId != -1) {
            GlStateManager._deleteTexture(colorId)
            colorId = -1
        }
        if (depthId != -1) {
            GlStateManager._deleteTexture(depthId)
            depthId = -1
        }
        if (fbId != -1) {
            GlStateManager._glDeleteFramebuffers(fbId)
            fbId = -1
        }
        width = 0
        height = 0
    }

    private fun allocate(w: Int, h: Int) {
        destroy()
        width = w
        height = h
        fbId = GlStateManager.glGenFramebuffers()
        colorId = GlStateManager._genTexture()
        depthId = GlStateManager._genTexture()

        // Color attachment (RG8, sampled by the post-shader for per-pixel
        // progress + silhouette).
        GlStateManager._bindTexture(colorId)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL30.GL_RG8,
            w, h, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?,
        )

        // OWN depth attachment — separate from main FB so bubble depth writes
        // (used to make front bubbles occlude back bubbles) don't pollute main
        // depth. Seeded with main depth each bubble pass via [copyMainDepth].
        GlStateManager._bindTexture(depthId)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT,
            w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null as ByteBuffer?,
        )

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbId)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, colorId, 0,
        )
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, depthId, 0,
        )

        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            LOG.warn("Concealment mask FB incomplete: 0x{}", Integer.toHexString(status))
        }
        // Restore main FB so caller sees a clean state.
        Minecraft.getInstance().mainRenderTarget.bindWrite(true)
    }
}
