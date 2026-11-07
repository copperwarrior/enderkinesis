package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.shaders.BlendMode
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.EffectInstance
import org.lwjgl.opengl.GL14
import org.shipwrights.enderkinesis.client.MainColorOnlyTarget
import org.shipwrights.enderkinesis.client.RefractionPass

/** Alpha-blends volume passes over main colour while the pass samples main depth. */
object VolumeComposite {
    private val OPAQUE_BLEND = BlendMode()

    /** The JSON blend of the pass must match. */
    enum class Blend(val src: GlStateManager.SourceFactor, val dst: GlStateManager.DestFactor) {
        ALPHA(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA),

        /** The pass outputs colour × alpha, so colour above alpha adds light. */
        PREMULTIPLIED(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA),
        ADDITIVE(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE),
    }

    fun drawOverMain(
        main: RenderTarget, effect: EffectInstance, pushUniforms: (EffectInstance) -> Unit, blend: Blend = Blend.ALPHA,
    ) {
        bindOverMain(main, blend)
        RefractionPass.drawQuad(effect, main.width, main.height, pushUniforms)
    }

    fun bindOverMain(main: RenderTarget, blend: Blend = Blend.ALPHA) {
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()
        RenderSystem.depthMask(false)
        MainColorOnlyTarget.bind(main)
        GlStateManager._viewport(0, 0, main.width, main.height)
        // BlendMode.apply skips all state, enableBlend too, when this effect's mode was the last one applied.
        RenderSystem.enableBlend()
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD)
        RenderSystem.blendFuncSeparate(blend.src, blend.dst, GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE)
    }

    /**
     * Premultiplied "over" on colour and alpha into a target that the caller bound and cleared, so a run of
     * passes there composites later as one premultiplied layer. The JSON blend of the pass must match.
     */
    fun bindPremultipliedLayer() {
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.enableBlend()
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD)
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
        )
    }

    /** Call one time after the volume passes of a frame. */
    fun restore(main: RenderTarget) {
        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
        RenderSystem.enableCull()
        // Core shaders apply an opaque BlendMode, which turns blending off when the last mode was not
        // opaque; leaving a volume pass's mode last made the GUI vignette draw opaque.
        OPAQUE_BLEND.apply()
        RenderSystem.defaultBlendFunc()
        main.bindWrite(false)
        GlStateManager._viewport(0, 0, main.width, main.height)
    }
}
