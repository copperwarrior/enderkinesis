package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import org.joml.Vector3f

/**
 * View-space vertices kept until the end of the frame. A [RenderType] that is not a fixed buffer ends its batch when
 * another type takes the buffer, so this keeps each such texture to one batch for all vines.
 */
internal class VertexStash(private val type: RenderType) {
    private var floats = FloatArray(INITIAL_VERTICES * FLOATS)
    private var lights = IntArray(INITIAL_VERTICES)
    private var size = 0

    @Suppress("LongParameterList") // One vertex: position, UV, alpha, light and normal.
    fun add(x: Float, y: Float, z: Float, u: Float, v: Float, alpha: Float, light: Int, normal: Vector3f) {
        if (size == lights.size) {
            floats = floats.copyOf(size * 2 * FLOATS)
            lights = lights.copyOf(size * 2)
        }
        val o = size * FLOATS
        floats[o] = x; floats[o + 1] = y; floats[o + 2] = z
        floats[o + 3] = u; floats[o + 4] = v; floats[o + 5] = alpha
        floats[o + 6] = normal.x; floats[o + 7] = normal.y; floats[o + 8] = normal.z
        lights[size] = light
        size++
    }

    fun clear() {
        size = 0
    }

    /** Draws the kept vertices and empties the stash; an empty stash does not touch [buffers]. */
    fun flush(buffers: MultiBufferSource) {
        if (size == 0) return
        val out = buffers.getBuffer(type)
        for (i in 0 until size) {
            val o = i * FLOATS
            out.vertex(
                floats[o], floats[o + 1], floats[o + 2], 1f, 1f, 1f, floats[o + 5],
                floats[o + 3], floats[o + 4], OverlayTexture.NO_OVERLAY, lights[i], floats[o + 6], floats[o + 7], floats[o + 8],
            )
        }
        size = 0
    }
}

private const val FLOATS = 9
private const val INITIAL_VERTICES = 256
