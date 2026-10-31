package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.client.SpellGeom.easeOut
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellVfx
import kotlin.math.sin
import kotlin.random.Random

object BeamClashes {
    private class Clash(val kind: SpellVfx.Kind, val at: Vec3, val other: Vec3, val rgbA: Int, val rgbB: Int, val pairs: List<Element>, val start: Long, val ticks: Int)

    // Render thread only.
    private val clashes = ArrayList<Clash>()

    const val SNAP_SECONDS = 0.18f

    fun add(kind: SpellVfx.Kind, a: Vec3, b: Vec3, size: Float, rgb: Int, life: Int, seed: Int, now: Long) {
        val pairs = if (kind == SpellVfx.Kind.BEAM_CHARGE) SpellVfx.chargePairsOf(seed) else emptyList()
        clashes += Clash(kind, a, b, rgb, size.toInt(), pairs, now, life.coerceAtLeast(1))
    }

    fun isEmpty(): Boolean = clashes.isEmpty()

    fun reset() {
        clashes.clear()
    }

    fun tick(now: Long) {
        // Keep the clash 4 ticks past its life, for the flash of the last pair.
        clashes.removeIf { now - it.start > it.ticks + 4 }
    }

    /** [gameTime] includes the partial tick. */
    fun flicker(end: Vec3, gameTime: Float): Float {
        val time = SpellClock.of(gameTime.toDouble())
        for (c in clashes) {
            if (c.kind != SpellVfx.Kind.BEAM_CHARGE || time - c.start >= c.ticks) continue
            if (c.at.distanceToSqr(end) < 1.5 * 1.5) return 0.6f + 0.4f * sin(time * 2.7f)
        }
        return 1f
    }

    /** [pose] is at the world origin. */
    fun render(pose: PoseStack, buffers: MultiBufferSource, view: SpellView, time: Float) {
        if (clashes.isEmpty()) return
        val m = pose.last().pose()
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (c in clashes) {
            val t = time - c.start
            when (c.kind) {
                SpellVfx.Kind.BEAM_PRESS -> if (t < c.ticks) {
                    val k = (t / c.ticks).coerceIn(0f, 1f)
                    val basis = SpellGeom.basis(c.other.subtract(c.at).normalize())
                    val rad = 0.3 + 0.9 * k
                    for (j in 0 until 24) {
                        val a0 = j * 2 * Math.PI / 24; val a1 = (j + 1) * 2 * Math.PI / 24
                        SpellShapes.quad(glow, m, c.at, c.at, c.at.add(SpellGeom.around(basis, a1, rad)), c.at.add(SpellGeom.around(basis, a0, rad)), 0xFFFFFF, 0.9f, 0.9f, 0f, 0f)
                    }
                }
                SpellVfx.Kind.BEAM_CHARGE -> eachPair(c, t) { _, k -> if (k > 1f) SpellShapes.disc(glow, m, view, c.at, 0.3 + (k - 1) * 3.0, 0xFFFFFF, 1f - (k - 1f) / 0.3f) }
                else -> {}
            }
        }
        if (!SpellVfxRenderTypes.isReady()) return
        val light = buffers.getBuffer(SpellVfxRenderTypes.glow(PARTICLE_ATLAS))
        for (c in clashes) {
            if (c.kind != SpellVfx.Kind.BEAM_CHARGE) continue
            val t = time - c.start
            val side = c.other.subtract(c.at)
            eachPair(c, t) { i, k ->
                if (k > 1f) return@eachPair
                val e = c.pairs[i]
                val off = 1.2 * (1 - easeOut(k.toDouble()))
                for ((element, sign) in listOf(e to 1, (e.opposite ?: e) to -1)) {
                    val glyph = SpellArt.glyph(i * 5 + if (sign > 0) 1 else 9)
                    SpellShapes.sprite(light, m, view, c.at.add(side.scale(sign * off)), 0.24, glyph, element.color, 1f, SpellShapes.FULL_BRIGHT)
                }
            }
            if (t < c.ticks) {
                val r = Random(c.start.toInt() * 31 + t.toInt())
                for (i in 0 until 4) {
                    val p = c.at.add(SpellGeom.spread(r).scale(0.4 * r.nextDouble()))
                    val spark = SpellArt.spark(r.nextInt(8))
                    SpellShapes.sprite(light, m, view, p, 0.16, spark, if (i % 2 == 0) c.rgbB else c.rgbA, 1f, SpellShapes.FULL_BRIGHT)
                }
            }
        }
    }

    /** A [k] value up to 1 is the fly-in; above 1 is the flash. */
    private inline fun eachPair(c: Clash, t: Float, draw: (Int, Float) -> Unit) {
        val n = c.pairs.size
        for (i in 0 until n) {
            // The last pair finishes a little before the blow.
            val born = 1f + i * (c.ticks - 4f) / maxOf(1, n)
            val k = (t - born) / 5f
            if (k < 0f || k > 1.3f) continue
            draw(i, k)
        }
    }

    fun snapSparks(light: VertexConsumer, m: org.joml.Matrix4f, view: SpellView, tip: Vec3, cut: Float, seed: Int) {
        val r = Random(seed)
        val frame = (cut * 7).toInt().coerceIn(0, 7)
        for (i in 0 until 5) {
            val p = tip.add((r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3)
            SpellShapes.sprite(light, m, view, p, 0.16, SpellArt.spark(frame), 0xFFFFFF, 1f - cut, SpellShapes.FULL_BRIGHT)
        }
    }

    class Dimmed(private val inner: MultiBufferSource, private val k: Float) : MultiBufferSource {
        override fun getBuffer(type: RenderType): VertexConsumer = Dim(inner.getBuffer(type), k)
    }

    private class Dim(private val inner: VertexConsumer, private val k: Float) : VertexConsumer {
        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer { inner.vertex(x, y, z); return this }
        override fun color(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer { inner.color(red, green, blue, (alpha * k).toInt()); return this }
        override fun uv(u: Float, v: Float): VertexConsumer { inner.uv(u, v); return this }
        override fun overlayCoords(u: Int, v: Int): VertexConsumer { inner.overlayCoords(u, v); return this }
        override fun uv2(u: Int, v: Int): VertexConsumer { inner.uv2(u, v); return this }
        override fun normal(x: Float, y: Float, z: Float): VertexConsumer { inner.normal(x, y, z); return this }
        override fun endVertex() = inner.endVertex()
        override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int) = inner.defaultColor(red, green, blue, (alpha * k).toInt())
        override fun unsetDefaultColor() = inner.unsetDefaultColor()
    }
}
