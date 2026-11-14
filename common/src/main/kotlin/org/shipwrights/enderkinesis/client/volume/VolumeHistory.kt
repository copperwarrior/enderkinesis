package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.pipeline.RenderTarget
import java.util.function.IntSupplier
import net.minecraft.client.renderer.EffectInstance
import org.joml.Matrix4f
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * Temporal accumulation for a [HalfResVolume]. The new march blends with the last result, which is reprojected by the
 * last view-projection and clamped to the 3x3 neighbourhood of the new march. Render thread only.
 */
internal class VolumeHistory(
    current: IntSupplier,
    halfDepth: IntSupplier,
    private val volumeDepth: IntSupplier?,
    /** The half depth of the last frame; with it, the history drops out where the scene depth changed. */
    private val prevDepth: IntSupplier? = null,
) {
    private val targets = arrayOf(
        HalfResTarget(GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_LINEAR),
        HalfResTarget(GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_LINEAR),
    )
    private var latest = 0
    private val effect = VolumeEffect(
        "volume_history_resolve",
        "CurrentSampler" to current,
        "HalfDepthSampler" to halfDepth,
        "HistorySampler" to IntSupplier { targets[latest].textureId },
        "VolumeDepthSampler" to (volumeDepth ?: halfDepth),
        "PrevDepthSampler" to (prevDepth ?: halfDepth),
    )

    // The march writes the volume depth only where explicit output locations exist: core in GL 3.3, MC asks for 3.2.
    private var volumeDepthOn: Boolean? = null
    private val prevViewProj = Matrix4f()
    private val rect = IntArray(RECT_SIZE)
    private val prevRect = IntArray(RECT_SIZE)
    private var prevCamX = 0.0
    private var prevCamY = 0.0
    private var prevCamZ = 0.0
    private var prevWidth = 0
    private var prevHeight = 0
    private var lastFrame = Long.MIN_VALUE
    private var weight = 0f
    private var view: VolumeView? = null
    private val pushUniforms: (EffectInstance) -> Unit = ::push

    val textureId: Int
        get() = targets[latest].textureId

    /**
     * Blends [current] into the history inside [halfRect] (half texels x0, y0, x1, y1), with that box as the scissor.
     * Returns false when the program did not load; [textureId] then holds no result for this frame.
     */
    fun resolve(main: RenderTarget, view: VolumeView, current: HalfResTarget, halfRect: IntArray): Boolean {
        val program = effect.get() ?: return false
        val frame = VolumeDepth.frame
        val dx = view.camX - prevCamX
        val dy = view.camY - prevCamY
        val dz = view.camZ - prevCamZ
        val cut = dx * dx + dy * dy + dz * dz > CUT_DISTANCE * CUT_DISTANCE
        val valid = frame == lastFrame + 1 && !cut && current.width == prevWidth && current.height == prevHeight
        weight = if (valid) HISTORY_WEIGHT else 0f
        halfRect.copyInto(rect, endIndex = RECT_SIZE)
        this.view = view
        // The history sampler must name a live texture also on the first frame, when its weight is 0.
        targets[latest].allocate(main)
        val out = 1 - latest
        targets[out].draw(main, program, pushUniforms)
        latest = out
        prevViewProj.set(view.viewProj)
        prevCamX = view.camX
        prevCamY = view.camY
        prevCamZ = view.camZ
        rect.copyInto(prevRect)
        prevWidth = current.width
        prevHeight = current.height
        lastFrame = frame
        return true
    }

    private fun push(e: EffectInstance) {
        val v = checkNotNull(view) { "VolumeHistory.push outside resolve" }
        v.pushInvViewProj(e)
        e.safeGetUniform("PrevViewProj").set(prevViewProj)
        // VolumeView matrices are camera-relative, so the camera move goes in as its own offset.
        e.safeGetUniform("CamDelta").set((v.camX - prevCamX).toFloat(), (v.camY - prevCamY).toFloat(), (v.camZ - prevCamZ).toFloat())
        targets[latest].pushHalfSize(e)
        e.safeGetUniform("Rect").set(rect[0].toFloat(), rect[1].toFloat(), rect[2].toFloat(), rect[3].toFloat())
        e.safeGetUniform("PrevRect").set(prevRect[0].toFloat(), prevRect[1].toFloat(), prevRect[2].toFloat(), prevRect[3].toFloat())
        e.safeGetUniform("HistoryWeight").set(weight)
        val useVolumeDepth = volumeDepthOn ?: (volumeDepth != null && GL.getCapabilities().let { it.OpenGL33 || it.GL_ARB_explicit_attrib_location })
            .also { volumeDepthOn = it }
        e.safeGetUniform("UseVolumeDepth").set(if (useVolumeDepth) 1f else 0f)
        e.safeGetUniform("DepthReject").set(if (prevDepth != null) 1f else 0f)
        v.pushDepthParams(e)
    }

    private companion object {
        const val RECT_SIZE = 4

        // Share of the reprojected history in each frame: about 7 frames to settle.
        const val HISTORY_WEIGHT = 0.85f

        // A camera move longer than this in one frame is a teleport or a cut: start the history again.
        const val CUT_DISTANCE = 4.0
    }
}
