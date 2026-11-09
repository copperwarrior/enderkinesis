package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import java.util.function.IntSupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL30
import org.shipwrights.enderkinesis.client.MainColorOnlyTarget
import org.shipwrights.enderkinesis.client.RefractionPass

/**
 * Scene depth for the volume passes, without particles: particles write depth for each solid sprite texel, so a march
 * stopped at main depth cut a hole behind each particle. A [HalfResVolume] splits its march at glass and water and puts
 * the part behind them under the real translucent layer from copies taken around that one draw. Render thread only.
 */
object VolumeDepth {
    // Colour and depth before the translucent layer; alpha is main alpha, restored after the layer.
    private val opaque = DepthTextureCopy(GL11.GL_RGBA8)

    // Colour and depth after the translucent layer. Main alpha was 0 before it, so alpha = 1 - transmittance.
    private val glass = DepthTextureCopy(GL11.GL_RGBA8)

    // Glass, water, tripwire and clouds over opaque depth: where the march in front of the translucent layer stops.
    private val scene = DepthTextureCopy()

    // The glass copy on a split frame: only tripwire draws between the two, and the user chose to ignore it.
    private var sceneCopy = scene

    // Main depth with particles, just before the clouds: where main depth is nearer after the clouds, a cloud drew.
    private val beforeClouds = DepthTextureCopy()
    private val clearZero = FloatArray(4)

    private val perf = VolumePerf.group("volume depth")
    private val opaqueTimer = perf.timer("opaque copy")
    private val glassTimer = perf.timer("glass copy")
    private val alphaTimer = perf.timer("alpha restore")
    private val sceneTimer = perf.timer("scene copy")
    private val cloudCopyTimer = perf.timer("cloud copy")
    private val cloudTimer = perf.timer("cloud depth")
    private val alphaRestore = VolumeEffect("volume_alpha_restore", "OpaqueColourSampler" to IntSupplier { opaque.colourTextureId })
    private val cloudDepth = VolumeEffect(
        "volume_cloud_depth",
        "CloudDepthSampler" to IntSupplier { Minecraft.getInstance().mainRenderTarget.depthTextureId },
        "ParticleDepthSampler" to IntSupplier { beforeClouds.textureId },
    )
    private val noUniforms: (EffectInstance) -> Unit = {}

    // A volume sampled depth since the last capture point. The copy starts one frame after a volume appears.
    private var wanted = false
    private var splitWanted = false
    private var captured = false
    private var behindClaimed = false
    private var opaqueCopied = false
    private var glassCopied = false

    /** True while the passes march the part behind glass and water, just after the translucent layer. */
    var behind = false
        private set

    /** Goes up by one at each [beginFrame], so a pass can tell that it skipped a frame. */
    var frame = 0L
        private set

    /** Call at renderLevel HEAD, so a frame that skips the capture point never reads a stale copy. */
    fun beginFrame() {
        captured = false
        behind = false
        behindClaimed = false
        opaqueCopied = false
        glassCopied = false
        frame++
        VolumePerf.beginFrame()
    }

    /** A split pass marched this frame, so the next frame copies around the translucent layer. */
    fun requestSplit() {
        splitWanted = true
    }

    /**
     * Call just before the real translucent terrain layer: copies main, then clears main alpha so the layer leaves
     * 1 - transmittance there. True when the caller must call [afterTranslucent] just after the layer.
     */
    fun beforeTranslucent(main: RenderTarget): Boolean {
        if (!splitWanted) return false
        splitWanted = false
        // Fabulous (not supported) draws the layer into its own target. Without the restore, main alpha would stay wrong.
        if (Minecraft.useShaderTransparency() || alphaRestore.get() == null) return false
        // Blits and glClearBuffer obey the scissor; glClearBuffer obeys the colour mask too.
        RenderSystem.disableScissor()
        opaqueTimer.begin()
        val prevFbo = boundDrawFramebuffer(main)
        opaque.copyFrom(main, prevFbo)
        opaqueCopied = true
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.frameBufferId)
        RenderSystem.colorMask(false, false, false, true)
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, clearZero)
        RenderSystem.colorMask(true, true, true, true)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo)
        opaqueTimer.end()
        return true
    }

    /** Call just after the real translucent layer, then draw the passes behind glass, then call [endBehind]. */
    fun afterTranslucent(main: RenderTarget) {
        RenderSystem.disableScissor()
        glassTimer.begin()
        glass.copyFrom(main)
        glassTimer.end()
        glassCopied = true
        behind = true
    }

    /** Ends the passes behind glass and gives main back the alpha the translucent layer would have left. */
    fun endBehind(main: RenderTarget) {
        behind = false
        val effect = alphaRestore.get()
        if (effect != null) {
            MainColorOnlyTarget.bind(main)
            GlStateManager._viewport(0, 0, main.width, main.height)
            RenderSystem.disableScissor()
            RenderSystem.disableDepthTest()
            RenderSystem.depthMask(false)
            RenderSystem.disableCull()
            RenderSystem.enableBlend()
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD)
            // alpha = a_opaque * T + (1 - T), as the layer's ONE, ONE_MINUS_SRC_ALPHA alpha blend would give.
            RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE_MINUS_DST_ALPHA, GlStateManager.DestFactor.ONE,
            )
            alphaTimer.begin()
            RefractionPass.drawQuad(effect, main.width, main.height, noUniforms)
            alphaTimer.end()
        }
        VolumeComposite.restore(main)
    }

    /**
     * The part behind glass of one volume a frame goes under the translucent layer; false for the others, which then
     * composite over it.
     */
    internal fun claimBehind(): Boolean {
        if (!behind || behindClaimed) return false
        behindClaimed = true
        return true
    }

    internal fun opaqueColourTextureId(): Int = opaque.colourTextureId

    /** Main depth from just before the translucent layer, or -1 when no split copied it this frame. */
    fun opaqueDepthTextureId(): Int = if (opaqueCopied) opaque.textureId else -1

    internal fun glassColourTextureId(): Int = glass.colourTextureId

    /** Call just before the particle pass. */
    fun capture(main: RenderTarget) {
        if (!wanted) return
        wanted = false
        if (glassCopied) {
            sceneCopy = glass
        } else {
            sceneTimer.begin()
            scene.copyFrom(main)
            sceneTimer.end()
            sceneCopy = scene
        }
        captured = true
    }

    /** Call just before the clouds. True when the caller must call [afterClouds] just after them. */
    fun beforeClouds(main: RenderTarget): Boolean {
        if (!captured) return false
        cloudCopyTimer.begin()
        beforeClouds.copyFrom(main)
        cloudCopyTimer.end()
        return true
    }

    /**
     * Puts the cloud depth into the scene copy: the texels where main depth came nearer during the clouds. When that is
     * the glass copy, nothing reads glass depth after the behind pass.
     */
    fun afterClouds(main: RenderTarget) {
        val effect = cloudDepth.get() ?: return
        val prevFbo = boundDrawFramebuffer(main)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneCopy.fbo)
        GlStateManager._viewport(0, 0, main.width, main.height)
        RenderSystem.disableScissor()
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(GL11.GL_ALWAYS)
        RenderSystem.depthMask(true)
        RenderSystem.colorMask(false, false, false, false)
        RenderSystem.disableBlend()
        RenderSystem.disableCull()
        cloudTimer.begin()
        RefractionPass.drawQuad(effect, main.width, main.height, noUniforms)
        cloudTimer.end()
        RenderSystem.depthFunc(GL11.GL_LEQUAL)
        RenderSystem.colorMask(true, true, true, true)
        VolumeComposite.restore(main)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo)
    }

    /** The depth texture a volume pass samples; main depth until a copy exists this frame. */
    fun textureId(main: RenderTarget): Int {
        wanted = true
        return when {
            behind -> opaque.textureId
            captured -> sceneCopy.textureId
            else -> main.depthTextureId
        }
    }

    /** Glass and water depth, where the march behind them starts. Valid only while [behind]. */
    fun nearTextureId(main: RenderTarget): Int = if (behind) glass.textureId else main.depthTextureId
}
