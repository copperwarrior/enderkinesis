package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.util.Mth
import org.joml.Vector4f

/** The screen rectangle that holds a set of camera-relative boxes, in NDC. Render thread only. */
class ScreenBounds {
    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f
    private val corner = Vector4f()
    private val rect = IntArray(RECT_SIZE)

    val isEmpty: Boolean
        get() = minX >= maxX || minY >= maxY

    fun reset() {
        minX = 1f
        minY = 1f
        maxX = -1f
        maxY = -1f
    }

    @Suppress("LongParameterList") // Mirrors VolumeView.boxVisible.
    fun addBox(view: VolumeView, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        if (!view.boxVisible(x0, y0, z0, x1, y1, z1)) return
        for (i in 0 until CORNERS) {
            corner.set(if (i and 1 == 0) x0 else x1, if (i and 2 == 0) y0 else y1, if (i and 4 == 0) z0 else z1, 1f)
            corner.mul(view.viewProj)
            // A corner at or behind the eye has no screen position: the box can cover the whole screen.
            if (corner.w <= MIN_W) {
                minX = -1f
                minY = -1f
                maxX = 1f
                maxY = 1f
                return
            }
            val x = Mth.clamp(corner.x / corner.w, -1f, 1f)
            val y = Mth.clamp(corner.y / corner.w, -1f, 1f)
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
    }

    /** Sets the GL scissor box to these bounds on a target of [width] x [height], grown by [pad] pixels. */
    fun applyScissor(width: Int, height: Int, pad: Int) {
        scissorRect(width, height, pad, rect)
        RenderSystem.enableScissor(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
    }

    /** Writes the box of [applyScissor] to [out] as x0, y0, x1, y1, with x1 and y1 exclusive. */
    fun scissorRect(width: Int, height: Int, pad: Int, out: IntArray) {
        require(out.size >= RECT_SIZE) { "scissorRect needs $RECT_SIZE slots" }
        out[0] = Mth.clamp(Mth.floor((minX * 0.5f + 0.5f) * width) - pad, 0, width)
        out[1] = Mth.clamp(Mth.floor((minY * 0.5f + 0.5f) * height) - pad, 0, height)
        out[2] = Mth.clamp(Mth.ceil((maxX * 0.5f + 0.5f) * width) + pad, 0, width)
        out[3] = Mth.clamp(Mth.ceil((maxY * 0.5f + 0.5f) * height) + pad, 0, height)
    }

    private companion object {
        const val CORNERS = 8
        const val RECT_SIZE = 4
        const val MIN_W = 1.0e-3f
    }
}
