package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.BufferBuilder
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource

/** Separate batched-vertex source for entities riding cloaking ships.
 *
 *  Vanilla's `LevelRenderer.renderLevel` builds one `MultiBufferSource.BufferSource`,
 *  passes it to every entity's `EntityRenderer.render`, and flushes it once via
 *  `endBatch()` after the entity loop. By swapping the bufferSource argument for
 *  cloaked entities (see [org.shipwrights.enderkinesis.mixin.EntityRenderDispatcherCloakingMixin]),
 *  their vertex data accumulates HERE instead of vanilla's source — we then bind the
 *  side FB and flush this source so cloaked entities land in the refraction layer. */
object CloakBufferSource {

    private val backing: BufferBuilder = BufferBuilder(256 * 1024)
    /** Vanilla's standard immediate-mode batched source. We just feed our own builder. */
    private val source: MultiBufferSource.BufferSource = MultiBufferSource.immediate(backing)

    @JvmStatic
    fun get(): MultiBufferSource.BufferSource = source

    /** Bind the ship FB, call endBatch on our source (flushes all accumulated render
     *  types to the now-bound side FB), then restore main FB. */
    @JvmStatic
    fun flushToShipFB() {
        ConcealmentShipFB.bindForDraw()
        try {
            source.endBatch()
        } finally {
            Minecraft.getInstance().mainRenderTarget.bindWrite(true)
        }
    }
}
