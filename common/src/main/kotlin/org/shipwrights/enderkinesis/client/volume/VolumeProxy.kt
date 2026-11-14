package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.EffectInstance

/**
 * Draws a volume program over the far faces of a box instead of the full screen, so a small
 * volume costs only the pixels it covers. Far faces cover each pixel one time, also with the
 * camera in the box. The program uses the `volume_proxy` vertex shader: set `ViewProj`
 * ([VolumeView.pushViewProj]), `LocalToView`, `BoxMin` and `BoxMax`. Render thread only.
 */
object VolumeProxy {
    private var cube: VertexBuffer? = null

    fun begin(main: RenderTarget, blend: VolumeComposite.Blend = VolumeComposite.Blend.ALPHA) {
        VolumeComposite.bindOverMain(main, blend)
        beginBound()
    }

    /**
     * Like [begin], into [VolumeLayer], and sets `ScreenSize` of [effect] to the layer size. The JSON blend of [effect]
     * must suit the layer. End the run with [end]; the stage owner flushes the layer. False when nothing is bound.
     */
    fun beginLayer(main: RenderTarget, view: VolumeView, effect: EffectInstance): Boolean {
        if (!VolumeLayer.begin(main, view)) return false
        VolumeLayer.pushScreenSize(effect)
        beginBound()
        return true
    }

    /** Like [begin], for a target and blend that the caller bound. End the run with [endBound]. */
    fun beginBound() {
        // The cube faces wind inward, so back-face culling keeps only the far faces.
        RenderSystem.enableCull()
        (cube ?: buildCube().also { cube = it }).bind()
    }

    /** Call after the uniforms of this box are set. */
    fun draw(effect: EffectInstance) {
        effect.apply()
        drawApplied()
    }

    /**
     * Draws with the program that the last [draw] bound, so a run of boxes binds the program one time.
     * Call [com.mojang.blaze3d.shaders.Uniform.upload] on the uniforms that changed for this box first.
     */
    fun drawApplied() {
        checkNotNull(cube) { "VolumeProxy.draw without begin" }.draw()
    }

    fun end(main: RenderTarget, effect: EffectInstance) {
        endBound(effect)
        VolumeComposite.restore(main)
    }

    /** Ends a [beginBound] run. The caller restores its target and state. */
    fun endBound(effect: EffectInstance) {
        effect.clear()
        VertexBuffer.unbind()
    }

    private fun buildCube(): VertexBuffer {
        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)
        for (axis in 0 until 3) {
            for (side in 0..1) emitInwardFace(builder, axis, side)
        }
        return VertexBuffer(VertexBuffer.Usage.STATIC).also {
            it.bind()
            it.upload(builder.end())
            VertexBuffer.unbind()
        }
    }

    private fun emitInwardFace(builder: com.mojang.blaze3d.vertex.BufferBuilder, axis: Int, side: Int) {
        val u = (axis + 1) % 3
        val v = (axis + 2) % 3
        val corners = Array(4) { DoubleArray(3) }
        val uv = arrayOf(intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1))
        for (i in 0 until 4) {
            corners[i][axis] = side.toDouble()
            corners[i][u] = uv[i][0].toDouble()
            corners[i][v] = uv[i][1].toDouble()
        }
        // (u × v) is +axis, so this order winds CCW seen from +axis; the face on side 1 must turn around.
        val order = if (side == 1) intArrayOf(3, 2, 1, 0) else intArrayOf(0, 1, 2, 3)
        for (i in order) builder.vertex(corners[i][0], corners[i][1], corners[i][2]).endVertex()
    }
}
