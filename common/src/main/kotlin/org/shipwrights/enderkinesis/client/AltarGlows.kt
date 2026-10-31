package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.MultiBufferSource
import org.joml.Matrix4f
import org.joml.Vector3f

// Vanilla draws translucent blocks after block entities, so the translucent altar covers no-depth glows.
// The renderer queues them here in view space, and the spell pass draws them after translucent blocks.
object AltarGlows {
    private class Glow(val at: Vector3f, val rgb: Int, val alpha: Float, val size: Float)
    private class Quad(val corners: Array<Vector3f>, val rgb: Int, val alpha: Float)

    // Render thread only. Filled by block entity renderers, emptied by [flush] each frame.
    private val glows = ArrayList<Glow>()
    private val quads = ArrayList<Quad>()
    private const val MAX = 4096

    fun isEmpty(): Boolean = glows.isEmpty() && quads.isEmpty()

    /** [at] is in view space. */
    fun glow(at: Vector3f, rgb: Int, alpha: Float, size: Float) {
        if (glows.size < MAX) glows += Glow(at, rgb, alpha, size)
    }

    /** [corners] are in view space. */
    fun quad(corners: Array<Vector3f>, rgb: Int, alpha: Float) {
        if (quads.size < MAX) quads += Quad(corners, rgb, alpha)
    }

    /** The queue is already in view space, so this uses an identity transform. */
    fun flush(buffers: MultiBufferSource) {
        if (isEmpty()) return
        val m = Matrix4f()
        if (quads.isNotEmpty()) {
            val c = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
            for (q in quads) {
                val r = (q.rgb shr 16) and 0xFF; val g = (q.rgb shr 8) and 0xFF; val b = q.rgb and 0xFF
                val a = (q.alpha.coerceIn(0f, 1f) * 255).toInt()
                for (p in q.corners) c.vertex(m, p.x, p.y, p.z).color(r, g, b, a).endVertex()
            }
        }
        if (glows.isNotEmpty() && SpellVfxRenderTypes.isSoftGlowReady()) {
            val sprite = SpellArt.particle("flash")
            val c = buffers.getBuffer(SpellVfxRenderTypes.softGlow(PARTICLE_ATLAS))
            for (g in glows) {
                // In view space, a camera-facing quad lies in x and y.
                val s = g.size / 2
                val r = (g.rgb shr 16) and 0xFF; val gr = (g.rgb shr 8) and 0xFF; val b = g.rgb and 0xFF
                val a = (g.alpha.coerceIn(0f, 1f) * 255).toInt()
                val corners = arrayOf(
                    floatArrayOf(-s, -s, sprite.u0, sprite.v1), floatArrayOf(-s, s, sprite.u0, sprite.v0),
                    floatArrayOf(s, s, sprite.u1, sprite.v0), floatArrayOf(s, -s, sprite.u1, sprite.v1),
                )
                for (p in corners) {
                    c.vertex(m, g.at.x + p[0], g.at.y + p[1], g.at.z).uv(p[2], p[3]).color(r, gr, b, a).uv2(SpellShapes.FULL_BRIGHT).endVertex()
                }
            }
        }
        glows.clear()
        quads.clear()
    }
}
