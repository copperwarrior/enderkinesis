package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import java.util.function.IntSupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * The shared half-res layer of the volume passes: [begin], draw the march at [width] x [height] with `DepthSampler`
 * at [depthTexture], then [flush] one time at the end of the stage. A run of passes composites in draw order, so
 * call [flush] before a draw that is not in the layer must go over the passes. Render thread only.
 */
object VolumeLayer {
    private val colour = HalfResTarget(GL30.GL_RGBA16F, GL11.GL_RGBA)
    private val depth = HalfResTarget(GL30.GL_RG32F, GL30.GL_RG)

    /** Raw half-res scene depth (checkerboard min/max), for the `DepthSampler` of a march in the layer. */
    val depthTexture = IntSupplier { depth.textureId }

    val width: Int
        get() = colour.width
    val height: Int
        get() = colour.height

    private val downsample = VolumeEffect(
        "volume_half_depth",
        "NearDepthSampler" to IntSupplier { Minecraft.getInstance().mainRenderTarget.depthTextureId },
    )
    private val upsample = VolumeEffect(
        "volume_upsample",
        "HalfSampler" to IntSupplier { colour.textureId },
        "HalfDepthSampler" to depthTexture,
        // Read only with BehindOn; a sampler must still point at a real texture.
        "OpaqueColourSampler" to IntSupplier { colour.textureId },
        "GlassColourSampler" to IntSupplier { colour.textureId },
    )

    private val perf = VolumePerf.group("volume layer") { "${colour.width}x${colour.height}" }
    private val depthTimer = perf.timer("depth")
    private val upsampleTimer = perf.timer("upsample")

    private var open = false
    private var depthFrame = -1L
    private var depthSource = -1
    private var depthA = 0f
    private var depthB = 0f
    private val pushDownsample: (EffectInstance) -> Unit = ::pushDownsample
    private val pushUpsample: (EffectInstance) -> Unit = ::pushUpsample

    /**
     * Binds the layer at half size with the premultiplied blend. The first call of a run makes the half depth and
     * clears the layer. The pass JSON blend must be premultiplied on alpha too (srcalpha 1, dstalpha 1-srcalpha), or
     * additive (srcalpha zero, dstalpha one). False, with nothing bound, when a shared program did not load.
     */
    fun begin(main: RenderTarget, view: VolumeView): Boolean {
        val down = downsample.get() ?: return false
        if (upsample.get() == null) return false
        depthA = view.depthA
        depthB = view.depthB
        RenderSystem.disableScissor()
        if (!open) {
            open = true
            colour.allocate(main)
            refreshDepth(main, down)
            colour.bind(main)
            colour.clear()
        } else {
            colour.bind(main)
        }
        VolumeComposite.bindPremultipliedLayer()
        RenderSystem.disableCull()
        return true
    }

    fun pushScreenSize(effect: EffectInstance) = effect.safeGetUniform("ScreenSize").set(width.toFloat(), height.toFloat())

    /** Upsamples the run over main and restores the main state. Does nothing when no pass drew since the last flush. */
    fun flush(main: RenderTarget) {
        if (!open) return
        open = false
        val effect = upsample.get()
        if (effect != null) {
            RenderSystem.disableScissor()
            upsampleTimer.begin()
            VolumeComposite.drawOverMain(main, effect, pushUpsample, VolumeComposite.Blend.PREMULTIPLIED)
            upsampleTimer.end()
        }
        VolumeComposite.restore(main)
    }

    // A copy of the scene depth holds for the frame; main depth changes as the frame draws, so it is read again.
    private fun refreshDepth(main: RenderTarget, down: EffectInstance) {
        val source = VolumeDepth.textureId(main)
        if (depthFrame == VolumeDepth.frame && depthSource == source && source != main.depthTextureId) return
        depthFrame = VolumeDepth.frame
        depthSource = source
        depthTimer.begin()
        depth.draw(main, down, pushDownsample)
        depthTimer.end()
    }

    private fun pushDownsample(e: EffectInstance) {
        e.safeGetUniform("NearOn").set(0f)
    }

    private fun pushUpsample(e: EffectInstance) {
        e.safeGetUniform("DepthParams").set(depthA, depthB)
        colour.pushHalfSize(e)
        e.safeGetUniform("DepthTolerance").set(DEPTH_TOLERANCE)
        e.safeGetUniform("DitherFrame").set((VolumeDepth.frame % JITTER_PERIOD).toFloat())
        e.safeGetUniform("BehindOn").set(0f)
    }

    private const val JITTER_PERIOD = 64L

    // Relative linear-depth spread of the upsample depth weight on a surface that faces the camera.
    private const val DEPTH_TOLERANCE = 0.01f
}
