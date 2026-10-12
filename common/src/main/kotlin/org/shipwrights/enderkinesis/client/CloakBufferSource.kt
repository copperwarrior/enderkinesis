package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType

/** Deferred batched-vertex source for entities + block entities riding cloaking
 *  ships.
 *
 *  NOT a vanilla `MultiBufferSource.immediate(...)`: an immediate source with a
 *  single fallback builder draws the PREVIOUS render type the moment a consumer
 *  requests a different type — and those mid-loop draws land in whatever
 *  framebuffer is bound at that instant, i.e. the MAIN FB during the entity/BE
 *  render loop. The result was cloaked content rendering fully visible in its
 *  correct position while the side FB (what the concealment shader composites
 *  and fades) stayed empty.
 *
 *  This source instead keeps one [BufferBuilder] per render type and draws
 *  nothing until [flushToShipFB] binds [ConcealmentShipFB] and ends every
 *  batch there. Builders are retained across frames for capacity reuse,
 *  matching vanilla's own per-type buffer map behaviour. */
object CloakBufferSource : MultiBufferSource {

    /** Insertion-ordered so flush order matches request order — solid entity
     *  layers before their translucent overlays, same relative order vanilla's
     *  fallback source would have drawn them in. */
    private val builders = LinkedHashMap<RenderType, BufferBuilder>()

    override fun getBuffer(type: RenderType): VertexConsumer {
        val builder = builders.getOrPut(type) { BufferBuilder(type.bufferSize()) }
        if (!builder.building()) builder.begin(type.mode(), type.format())
        return builder
    }

    @JvmStatic
    fun get(): MultiBufferSource = this

    /** Bind the ship FB, end every accumulated per-type batch into it, then
     *  restore the main FB. */
    @JvmStatic
    fun flushToShipFB() {
        var anyBuilding = false
        for (builder in builders.values) {
            if (builder.building()) { anyBuilding = true; break }
        }
        if (!anyBuilding) return
        ConcealmentShipFB.bindForDraw()
        try {
            for ((type, builder) in builders) {
                if (builder.building()) type.end(builder, RenderSystem.getVertexSorting())
            }
        } finally {
            Minecraft.getInstance().mainRenderTarget.bindWrite(true)
        }
    }
}
