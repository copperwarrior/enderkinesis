package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import java.nio.ByteBuffer
import java.util.function.IntSupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12

// Direct fullscreen-quad refraction pass — bypasses vanilla `PostChain` to
// dodge a Mesa-on-Flatpak bug where `PostPass.process`'s swap-target
// ping-pong doesn't land its output on the screen for gfx1201 / RDNA4.
// Vanilla creeper / spider spectator post-effects exhibit the same failure
// on the affected runtime, ruling out our shader code; the breakage is in
// the framework.
//
// Pipeline per call:
//   1. glCopyTexSubImage2D main FB's color → scratch texture we own.
//   2. Re-assert main FB as draw target (defensive — bubble / mask / ship FBs
//      may have left a side FB bound).
//   3. Set GL state for a fullscreen quad (no depth, no cull, no blend).
//   4. Bind DiffuseSampler from our scratch, plus any caller-supplied aux
//      samplers (e.g. MaskSampler / ShipSampler for the cloak).
//   5. Push ProjMat / InSize / OutSize (shared by all three refraction vsh
//      files), then the caller's effect-specific uniforms.
//   6. effect.apply() → draw quad → effect.clear(). Main FB now holds the
//      refracted composite. No intermediate `swap` target, no blit-back pass.
object RefractionPass {

    private var scratchTexId: Int = -1
    private var width: Int = 0
    private var height: Int = 0

    private fun ensureScratch(w: Int, h: Int) {
        if (scratchTexId != -1 && width == w && height == h) return
        destroy()
        scratchTexId = GlStateManager._genTexture()
        GlStateManager._bindTexture(scratchTexId)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?,
        )
        width = w
        height = h
    }

    fun destroy() {
        if (scratchTexId != -1) {
            GlStateManager._deleteTexture(scratchTexId)
            scratchTexId = -1
        }
        width = 0
        height = 0
    }

    /** Run one refraction pass.
     *
     *  @param effect the EffectInstance to execute. Sampler bindings + uniforms
     *      are pushed inside this call; the effect is `apply()`-d, the quad
     *      drawn, then `clear()`-d. Caller owns the EffectInstance lifecycle.
     *  @param extraSamplers aux samplers to bind by JSON name beyond
     *      DiffuseSampler — e.g. MaskSampler / ShipSampler for the cloak.
     *  @param configureUniforms callback to push effect-specific uniforms
     *      (e.g. SessionTime, CloakProgress) after the shared
     *      ProjMat / InSize / OutSize are set. */
    fun run(
        effect: EffectInstance,
        extraSamplers: List<Pair<String, IntSupplier>> = emptyList(),
        configureUniforms: (EffectInstance) -> Unit = {},
    ) {
        val mc = Minecraft.getInstance()
        val w = mc.mainRenderTarget.width
        val h = mc.mainRenderTarget.height
        if (w <= 0 || h <= 0) return
        ensureScratch(w, h)

        // glCopyTexSubImage2D reads from the bound READ framebuffer.
        // mainRenderTarget.bindWrite uses GL_FRAMEBUFFER which binds both
        // READ and DRAW to main, so by the time we get here from
        // renderLevel TAIL, main is the active read source.
        GlStateManager._bindTexture(scratchTexId)
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)

        mc.mainRenderTarget.bindWrite(false)
        GlStateManager._viewport(0, 0, w, h)

        RenderSystem.disableBlend()
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()
        RenderSystem.depthMask(false)
        RenderSystem.colorMask(true, true, true, true)

        effect.setSampler("DiffuseSampler", IntSupplier { scratchTexId })
        for ((name, supplier) in extraSamplers) {
            effect.setSampler(name, supplier)
        }

        // Y-up ortho (bottom=0, top=h) matches vanilla PostPass.process — the
        // quad's vertex (0,0) maps to the framebuffer's bottom-left, which is
        // also where texture coordinate (0,0) lives, so the vsh's
        // `texCoord = Position / OutSize` aligns with the sampled FB content.
        // Y-down (top=0, bottom=h) flips the output vertically.
        val proj = Matrix4f().setOrtho(0f, w.toFloat(), 0f, h.toFloat(), 1000f, 3000f)
        effect.safeGetUniform("ProjMat").set(proj)
        effect.safeGetUniform("InSize").set(w.toFloat(), h.toFloat())
        effect.safeGetUniform("OutSize").set(w.toFloat(), h.toFloat())

        configureUniforms(effect)

        effect.apply()

        // Vertex Z hardcoded to 0.2 by the vsh — vertex z here is irrelevant.
        val savedProj = Matrix4f(RenderSystem.getProjectionMatrix())
        RenderSystem.setProjectionMatrix(proj, VertexSorting.ORTHOGRAPHIC_Z)
        val buf = Tesselator.getInstance().builder
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)
        buf.vertex(0.0, 0.0, 0.0).endVertex()
        buf.vertex(w.toDouble(), 0.0, 0.0).endVertex()
        buf.vertex(w.toDouble(), h.toDouble(), 0.0).endVertex()
        buf.vertex(0.0, h.toDouble(), 0.0).endVertex()
        BufferUploader.draw(buf.end())
        RenderSystem.setProjectionMatrix(savedProj, VertexSorting.DISTANCE_TO_ORIGIN)

        effect.clear()
        RenderSystem.depthMask(true)
        RenderSystem.enableBlend()
        RenderSystem.enableDepthTest()
        RenderSystem.enableCull()
    }
}
