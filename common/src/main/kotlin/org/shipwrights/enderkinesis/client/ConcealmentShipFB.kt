package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.logging.LogUtils
import java.nio.ByteBuffer
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30

/** RGBA off-screen framebuffer used as the ship-only render target for the smooth-fade
 *  Staff of Concealment cloak. Each cloaking ship's solid blocks are rendered to this FB
 *  every frame (or from a cached VertexBuffer) while the main FB has the ship concealed
 *  via the BlockRenderDispatcher cancellation. The post-shader then samples this FB at a
 *  displacement-offset UV to get "refracted ship pixels", and blends those over the main
 *  FB by `alpha = 1 - CloakProgress` for a perfectly smooth fade — the actual ship sample
 *  is real, the background sample (main FB) is real, no approximation.
 *
 *  **OWN depth attachment** — NOT shared with main. The cloaked ship's depth writes must
 *  not pollute the main FB's depth buffer (would occlude every subsequent main-FB pass —
 *  cutout terrain, other ships' batched draws, translucents — at any pixel where the
 *  cloaked ship is). Self-occlusion still works (ship writes & tests against side
 *  depth). Terrain occlusion is restored by [copyMainDepth], called at the start of
 *  each VS2 `drawLayer` to blit main FB depth → side FB depth — ship's depth test then
 *  reads the current terrain depth, dropping fragments behind terrain. */
object ConcealmentShipFB {

    private val LOG = LogUtils.getLogger()

    private var fbId: Int = -1
    private var colorId: Int = -1
    private var depthId: Int = -1
    private var width: Int = 0
    private var height: Int = 0

    /** True once we've blitted main FB depth → side FB depth this frame.
     *  Subsequent [copyMainDepth] calls in the same frame are no-ops so the
     *  ship's own depth writes ACCUMULATE across layers (SOLID → CUTOUT →
     *  TRANSLUCENT). Without this, each layer's depth blit would wipe the
     *  previous layer's ship-depth contributions and translucent ship blocks
     *  would draw over opaque ship blocks (because translucent's depth test
     *  saw only terrain, not the ship's own solid). Reset to false at frame
     *  start via [beginFrame]. */
    private var depthCopiedThisFrame: Boolean = false

    /** True once color attachment has been cleared this frame. VS2's Sodium
     *  `shipsStartRenderingSodium` event fires PER PASS (SOLID / CUTOUT /
     *  TRANSLUCENT) rather than once per frame the way the vanilla event does
     *  (see SodiumCompat.vsRenderLayer vs MixinLevelRendererVanilla's
     *  `vs$emittedShipsStartRenderingThisFrame` gate). Without this flag, the
     *  CUTOUT and TRANSLUCENT clears wipe the SOLID pass's accumulated ship
     *  pixels — and since most ship blocks are solid, the side FB ends up
     *  effectively empty. Reset to false at frame start via [beginFrame]. */
    private var colorClearedThisFrame: Boolean = false

    /** OpenGL texture id of the ship-only RGBA attachment. Pushed to the post-shader
     *  as the `ShipSampler` uniform. */
    val colorTextureId: Int
        get() = colorId

    /** Bind for writing. Clears to transparent. Resizes to match main FB if needed. */
    fun bindForWrite() {
        val mc = Minecraft.getInstance()
        val mainW = mc.mainRenderTarget.width
        val mainH = mc.mainRenderTarget.height
        if (fbId == -1 || width != mainW || height != mainH) {
            allocate(mainW, mainH)
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbId)
        // Own depth attachment is set at allocate time; no re-attach needed.
        RenderSystem.colorMask(true, true, true, true)
        RenderSystem.clearColor(0f, 0f, 0f, 0f)
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, false)
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(GL11.GL_LEQUAL)
        // Ship-only blocks self-occlude — depth writes go to our OWN depth
        // attachment, which we keep populated with current terrain depth via
        // [copyMainDepth] at the start of each drawLayer.
        RenderSystem.depthMask(true)
        GlStateManager._viewport(0, 0, mainW, mainH)
    }

    fun unbind() {
        Minecraft.getInstance().mainRenderTarget.bindWrite(true)
    }

    /** Bind for write WITHOUT clearing and WITHOUT touching depth/color state.
     *  Used inside batched-render draw loops where the caller's render-type state
     *  (depthMask, depthFunc, blend, etc.) must be preserved across our bind /
     *  unbind — otherwise subsequent draws in the batch break.
     *  Viewport updates only if the FB was reallocated to match the main FB. */
    fun bindForDraw() {
        val mc = Minecraft.getInstance()
        val mainW = mc.mainRenderTarget.width
        val mainH = mc.mainRenderTarget.height
        if (fbId == -1 || width != mainW || height != mainH) {
            allocate(mainW, mainH)
            GlStateManager._viewport(0, 0, mainW, mainH)
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbId)
        // Own depth attachment is set at allocate time; no re-attach needed.
    }

    /** Clear the side FB's color attachment to transparent without leaving it
     *  bound. Idempotent within a frame: subsequent calls before [beginFrame]
     *  is invoked again are no-ops, so VS2's per-pass `shipsStartRenderingSodium`
     *  fires don't wipe the SOLID pass's accumulated ship pixels.
     *  Re-binds main FB before returning so callers don't have to. */
    fun clearOnly() {
        if (colorClearedThisFrame) return
        val mc = Minecraft.getInstance()
        val mainW = mc.mainRenderTarget.width
        val mainH = mc.mainRenderTarget.height
        if (fbId == -1 || width != mainW || height != mainH) {
            allocate(mainW, mainH)
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbId)
        RenderSystem.colorMask(true, true, true, true)
        RenderSystem.clearColor(0f, 0f, 0f, 0f)
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, false)
        mc.mainRenderTarget.bindWrite(true)
        colorClearedThisFrame = true
    }

    /** Blit the main FB's depth attachment into our own depth attachment.
     *  Idempotent within a frame — only the FIRST call per frame actually
     *  blits; subsequent calls are no-ops. This lets every cloaked-ship draw
     *  path (batched `drawLayer` HEAD, vanilla `renderShip` event, future
     *  paths) call it freely without coordination, while ensuring the side FB
     *  depth gets seeded with terrain depth exactly ONCE per frame.
     *
     *  Why once-per-frame: the ship's own depth writes must accumulate across
     *  layers (SOLID → CUTOUT → TRANSLUCENT) so translucent ship blocks
     *  depth-test against the opaque ship blocks behind them. A per-layer
     *  blit wipes the previous layer's ship-depth, so translucent draws on
     *  top of opaque regardless of 3D position.
     *
     *  Reset for the next frame via [markFrameStart] (called from VS2's
     *  `shipsStartRendering` event). */
    fun copyMainDepth() {
        if (depthCopiedThisFrame) return
        val mc = Minecraft.getInstance()
        val mainW = mc.mainRenderTarget.width
        val mainH = mc.mainRenderTarget.height
        if (fbId == -1 || width != mainW || height != mainH) {
            allocate(mainW, mainH)
        }
        val mainFb = mc.mainRenderTarget.frameBufferId
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainFb)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbId)
        // GL spec: depth blits MUST use GL_NEAREST (linear not allowed).
        GL30.glBlitFramebuffer(
            0, 0, mainW, mainH,
            0, 0, mainW, mainH,
            GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST,
        )
        // Unbind the READ_FRAMEBUFFER target (it was main) so subsequent code
        // that just calls bindWrite(...) and reads from the framebuffer
        // doesn't get a stale read binding.
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0)
        mc.mainRenderTarget.bindWrite(true)
        depthCopiedThisFrame = true
    }

    /** Reset per-frame state. Called from `LevelRenderer.renderLevel` HEAD —
     *  NOT from VS2 events, because `shipsStartRenderingSodium` fires per-pass
     *  rather than once per frame and would re-arm the "needs clear" state in
     *  the middle of accumulating ship pixels. The next [copyMainDepth] /
     *  [clearOnly] call after this performs a fresh blit / clear. */
    fun beginFrame() {
        depthCopiedThisFrame = false
        colorClearedThisFrame = false
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

        // Color attachment (RGBA8, sampled by the post-shader).
        GlStateManager._bindTexture(colorId)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?,
        )

        // OWN depth attachment (DEPTH_COMPONENT, float). Matches vanilla
        // RenderTarget's depth format so glBlitFramebuffer can copy main →
        // side without format conversion.
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
            LOG.warn("Concealment ship FB incomplete: 0x{}", Integer.toHexString(status))
        }
        Minecraft.getInstance().mainRenderTarget.bindWrite(true)
    }
}
