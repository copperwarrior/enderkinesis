package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.client.SpellGeom.easeOut
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellVfx
import kotlin.math.sin
import kotlin.random.Random

/**
 * Two beams that meet badly, before and after it goes ([org.shipwrights.enderkinesis.spell.BeamManager]):
 *
 * - **Building up to an explosion** (half a second): the beams run into the crossing,
 *   flickering; each pair of glyphs that will annihilate, one of each opposite, flies together
 *   from either side and is gone in a flash of white; sparks of both beams' colours spit off
 *   the crossing.
 * - **Pressing together head-on:** a disc of light flattening out between them.
 * - **Snapping back:** when it goes, each beam whips back from the crossing to its caster's
 *   hand, sparking at its tip ([ScrollBeamClient.snap]).
 */
object BeamClashes {
    private class Clash(val kind: SpellVfx.Kind, val at: Vec3, val other: Vec3, val rgbA: Int, val rgbB: Int, val pairs: List<Element>, val start: Long, val ticks: Int)

    // Render thread only.
    private val clashes = ArrayList<Clash>()

    /** Seconds the beams take to snap back. */
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
        // A flash follows the last pair a little after the blow; keep it that long.
        clashes.removeIf { now - it.start > it.ticks + 4 }
    }

    /** How bright a beam ending at [end] is drawn: it flickers while it's building up to an
     *  explosion there. [gameTime] is the world's time with its partial tick. */
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
                    // Pressed into a flattening disc of light, facing along the beams.
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
        val light = buffers.getBuffer(SpellVfxRenderTypes.glow(TextureAtlas.LOCATION_PARTICLES))
        for (c in clashes) {
            if (c.kind != SpellVfx.Kind.BEAM_CHARGE) continue
            val t = time - c.start
            val side = c.other.subtract(c.at)
            // The pairs flying together, one glyph from either side.
            eachPair(c, t) { i, k ->
                if (k > 1f) return@eachPair
                val e = c.pairs[i]
                val off = 1.2 * (1 - easeOut(k.toDouble()))
                for ((element, sign) in listOf(e to 1, (e.opposite ?: e) to -1)) {
                    val glyph = SpellArt.particle("sga_" + ('a' + Math.floorMod(i * 5 + if (sign > 0) 1 else 9, 26)))
                    SpellShapes.sprite(light, m, view, c.at.add(side.scale(sign * off)), 0.24, glyph, element.color, 1f, SpellShapes.FULL_BRIGHT)
                }
            }
            // Sparks spitting off the crossing until it goes.
            if (t < c.ticks) {
                val r = Random(c.start.toInt() * 31 + t.toInt())
                for (i in 0 until 4) {
                    val p = c.at.add(SpellGeom.spread(r).scale(0.4 * r.nextDouble()))
                    val spark = SpellArt.particle("spark_" + r.nextInt(8))
                    SpellShapes.sprite(light, m, view, p, 0.16, spark, if (i % 2 == 0) c.rgbB else c.rgbA, 1f, SpellShapes.FULL_BRIGHT)
                }
            }
        }
    }

    /** Each pair of [c] that's showing at [t] ticks in, and how far through it is: up to 1 its
     *  glyphs fly together, then it flashes. */
    private inline fun eachPair(c: Clash, t: Float, draw: (Int, Float) -> Unit) {
        val n = c.pairs.size
        for (i in 0 until n) {
            // Spread over the build-up, the last done a little before the blow.
            val born = 1f + i * (c.ticks - 4f) / maxOf(1, n)
            val k = (t - born) / 5f
            if (k < 0f || k > 1.3f) continue
            draw(i, k)
        }
    }

    /** Sparks at the tip of a beam snapping back, [cut] of the way. */
    fun snapSparks(light: VertexConsumer, m: org.joml.Matrix4f, view: SpellView, tip: Vec3, cut: Float, seed: Int) {
        val r = Random(seed)
        val frame = (cut * 7).toInt().coerceIn(0, 7)
        for (i in 0 until 5) {
            val p = tip.add((r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3)
            SpellShapes.sprite(light, m, view, p, 0.16, SpellArt.particle("spark_$frame"), 0xFFFFFF, 1f - cut, SpellShapes.FULL_BRIGHT)
        }
    }

    /** Draws everything into [inner] with its opacity scaled by [k]. */
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
