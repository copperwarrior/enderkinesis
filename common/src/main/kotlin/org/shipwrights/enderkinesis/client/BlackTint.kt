package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture

/**
 * Vanilla entity shaders do `tex * vertexColor`, so black vertex colour gives solid black RGB
 * while alpha cutout still honours the skin. Render thread only.
 */
class BlackTintBufferSource(var delegate: MultiBufferSource) : MultiBufferSource {

    private val cache = HashMap<RenderType, BlackTintVertexConsumer>(8)

    override fun getBuffer(renderType: RenderType): VertexConsumer {
        val fresh = delegate.getBuffer(renderType)
        if (fresh is BlackTintVertexConsumer) return fresh
        val wrapper = cache.getOrPut(renderType) { BlackTintVertexConsumer(fresh) }
        wrapper.delegate = fresh
        return wrapper
    }
}

/** Chained calls return `this`, not the delegate, so later links stay tinted. */
class BlackTintVertexConsumer(var delegate: VertexConsumer) : VertexConsumer {

    override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
        delegate.vertex(x, y, z)
        return this
    }

    override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer {
        delegate.color(0, 0, 0, 255)
        return this
    }

    override fun uv(u: Float, v: Float): VertexConsumer {
        delegate.uv(u, v)
        return this
    }

    // The entity shader mixes the hurt overlay in after the vertex colour, so a hurt entity would flash red.
    override fun overlayCoords(u: Int, v: Int): VertexConsumer {
        delegate.overlayCoords(OverlayTexture.NO_OVERLAY)
        return this
    }

    override fun uv2(u: Int, v: Int): VertexConsumer {
        delegate.uv2(u, v)
        return this
    }

    override fun normal(x: Float, y: Float, z: Float): VertexConsumer {
        delegate.normal(x, y, z)
        return this
    }

    override fun endVertex() {
        delegate.endVertex()
    }

    override fun defaultColor(r: Int, g: Int, b: Int, a: Int) {
        delegate.defaultColor(0, 0, 0, 255)
    }

    override fun unsetDefaultColor() {
        delegate.unsetDefaultColor()
    }
}
