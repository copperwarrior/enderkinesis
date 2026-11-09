package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import java.util.function.IntSupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * A premultiplied volume pass at half resolution: [beginMarch], draw the march, then [composite].
 * `temporal = true` adds a [VolumeHistory]. Give the march program `"DepthSampler" to marchDepth`, so the upsample
 * knows the depth each half texel marched to. [name] and [perfDetails] label the pass in F3 and the perf log.
 * With [volumeDepth], the march also writes `layout(location = 1)` = (transmittance-weighted distance × alpha, alpha), so
 * the history reprojects the gas at its own depth, not at the surface behind it.
 * Draw the pass two times a frame: in [VolumeDepth.behind] just after the translucent layer, and again at renderLevel TAIL.
 * The march stops at R of marchDepth; a G above 0 is the raw glass depth where the part behind glass starts.
 * The part behind glass goes under the translucent layer through the before/after copies of [VolumeDepth].
 * Render thread only.
 */
class HalfResVolume(
    name: String,
    private val temporal: Boolean = false,
    private val depthTolerance: Float = DEPTH_TOLERANCE,
    private val volumeDepth: Boolean = false,
    perfDetails: (() -> String)? = null,
) {
    private val perf = VolumePerf.group(name) {
        val own = "${front.colour.width}x${front.colour.height}, temporal ${if (temporal) "on" else "off"}"
        perfDetails?.let { "$own, ${it()}" } ?: own
    }

    private inner class Layer(prefix: String) {
        val meanDepth = if (volumeDepth) HalfResTarget(GL30.GL_RG16F, GL30.GL_RG) else null
        val colour = HalfResTarget(GL30.GL_RGBA16F, GL11.GL_RGBA, second = meanDepth)
        val depth = HalfResTarget(GL30.GL_RG32F, GL30.GL_RG)
        val history = if (temporal) {
            VolumeHistory(IntSupplier { colour.textureId }, IntSupplier { depth.textureId }, meanDepth?.let { m -> IntSupplier { m.textureId } })
        } else {
            null
        }
        var resolved = false
        val halfRect = IntArray(RECT_SIZE)
        val fullRect = IntArray(RECT_SIZE)
        val depthTimer = perf.timer("${prefix}depth")
        val marchTimer = perf.timer("${prefix}march")
        val historyTimer = perf.timer("${prefix}history")
        val upsampleTimer = perf.timer("${prefix}upsample")
    }

    private val front = Layer("")
    private val back = Layer("behind ")
    private var layer = front

    /** Raw half-res scene depth (checkerboard min/max) of the current layer, for the DepthSampler of the march program. */
    val marchDepth = IntSupplier { layer.depth.textureId }

    val width: Int
        get() = layer.colour.width
    val height: Int
        get() = layer.colour.height

    private val downsample = VolumeEffect(
        "volume_half_depth",
        "NearDepthSampler" to IntSupplier { VolumeDepth.nearTextureId(Minecraft.getInstance().mainRenderTarget) },
    )
    private val upsampleSamplers = arrayOf(
        "HalfSampler" to IntSupplier { layer.history?.takeIf { layer.resolved }?.textureId ?: layer.colour.textureId },
        "HalfDepthSampler" to marchDepth,
        // Unread off the behind path; before the first copy the texture does not exist yet.
        "OpaqueColourSampler" to IntSupplier { orColour(VolumeDepth.opaqueColourTextureId()) },
        "GlassColourSampler" to IntSupplier { orColour(VolumeDepth.glassColourTextureId()) },
    )
    private val upsample = VolumeEffect("volume_upsample", *upsampleSamplers)
    private val upsampleBehind = VolumeEffect("volume_upsample_behind", *upsampleSamplers)
    private var underGlass = false
    private var view: VolumeView? = null
    private val pushDownsample: (EffectInstance) -> Unit = ::pushDownsample
    private val pushUpsample: (EffectInstance) -> Unit = ::pushUpsample

    /**
     * Fills the half-res depth, then binds the cleared colour target with the premultiplied-layer blend. The scissor
     * stays on [bounds] (null: the whole target). Pushes `JitterFrame` (0 to 63) to [march]. Returns false, with
     * nothing bound, when a shared program did not load.
     */
    fun beginMarch(main: RenderTarget, march: EffectInstance, bounds: ScreenBounds?): Boolean {
        val down = downsample.get() ?: return false
        if (upsample.get() == null || upsampleBehind.get() == null) return false
        layer = if (VolumeDepth.behind) back else front
        VolumeDepth.requestSplit()
        val l = layer
        l.colour.allocate(main)
        l.depth.allocate(main)
        if (bounds != null) {
            bounds.scissorRect(l.colour.width, l.colour.height, PAD, l.halfRect)
        } else {
            l.halfRect[0] = 0
            l.halfRect[1] = 0
            l.halfRect[2] = l.colour.width
            l.halfRect[3] = l.colour.height
        }
        scissorHalf()
        l.depthTimer.begin()
        l.depth.draw(main, down, pushDownsample)
        l.depthTimer.end()
        l.colour.bind(main)
        l.colour.clear()
        VolumeComposite.bindPremultipliedLayer()
        march.safeGetUniform("JitterFrame").set((VolumeDepth.frame % JITTER_PERIOD).toFloat())
        l.marchTimer.begin()
        return true
    }

    /**
     * Resolves the history when temporal, then upsamples over main inside [bounds]. Leaves the scissor off.
     * Call [VolumeComposite.restore] after the last volume pass of the frame.
     */
    fun composite(main: RenderTarget, view: VolumeView, bounds: ScreenBounds?) {
        val l = layer
        l.marchTimer.end()
        scissorHalf()
        l.resolved = l.history?.let {
            l.historyTimer.begin()
            val ok = it.resolve(main, view, l.colour, l.halfRect)
            l.historyTimer.end()
            ok
        } ?: false
        RenderSystem.disableScissor()
        this.view = view
        if (bounds != null) {
            bounds.scissorRect(main.width, main.height, PAD, l.fullRect)
        } else {
            l.fullRect[0] = 0
            l.fullRect[1] = 0
            l.fullRect[2] = main.width
            l.fullRect[3] = main.height
        }
        underGlass = l === back && VolumeDepth.claimBehind()
        val effect = (if (underGlass) upsampleBehind else upsample).get() ?: return
        scissorFull()
        l.upsampleTimer.begin()
        val blend = if (underGlass) VolumeComposite.Blend.REPLACE else VolumeComposite.Blend.PREMULTIPLIED
        VolumeComposite.drawOverMain(main, effect, pushUpsample, blend)
        l.upsampleTimer.end()
        underGlass = false
        RenderSystem.disableScissor()
    }

    private fun orColour(texture: Int): Int = if (texture > 0) texture else layer.colour.textureId

    private fun scissorHalf() {
        val r = layer.halfRect
        RenderSystem.enableScissor(r[0], r[1], r[2] - r[0], r[3] - r[1])
    }

    private fun scissorFull() {
        val r = layer.fullRect
        RenderSystem.enableScissor(r[0], r[1], r[2] - r[0], r[3] - r[1])
    }

    private fun pushDownsample(e: EffectInstance) {
        e.safeGetUniform("NearOn").set(if (layer === back) 1f else 0f)
    }

    private fun pushUpsample(e: EffectInstance) {
        checkNotNull(view) { "HalfResVolume upsample outside composite" }.pushDepthParams(e)
        layer.colour.pushHalfSize(e)
        e.safeGetUniform("DepthTolerance").set(depthTolerance)
        e.safeGetUniform("DitherFrame").set((VolumeDepth.frame % JITTER_PERIOD).toFloat())
        e.safeGetUniform("BehindOn").set(if (underGlass) 1f else 0f)
    }

    private companion object {
        const val RECT_SIZE = 4

        // The upsample and the history clamp read one texel past each march texel.
        const val PAD = 1
        const val JITTER_PERIOD = 64L

        // Relative linear-depth gap at which a half texel counts as other geometry.
        const val DEPTH_TOLERANCE = 0.01f
    }
}
