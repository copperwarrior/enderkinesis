package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.client.SpellGeom.smooth
import org.shipwrights.enderkinesis.client.SpellGeom.whiten
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A scroll beam's body and what its elements add around it, drawn after the Staff of
 * Sundering-style prism (which only Purity beams keep; see [ScrollBeamClient]):
 *
 * - **Body:** a soft tube of the spell's light with a bright thread through it, fading in
 *   over the first half block from the hand.
 * - **Sunfire:** a shaft of sunlight instead: two shells of flowing noise light (the
 *   `rendertype_spell_sunfire` shader) round a white-hot core.
 * - **Growth:** vines winding round the beam at 16 texels per block, flowing outward and
 *   swaying, growing in from the hand, with leaf chips riding them.
 * - **Accents:** short lightning zaps crackling off a Chaos beam, small ice cubes turning
 *   round a Cold one, tiny white cubes in a Purity one, gas puffing off a Poison one. The rest
 *   of each element's accents are particles ([ScrollBeamClient.tick]).
 *
 * One render type at a time.
 */
object ScrollBeamDetail {

    /** Vanilla's foliage green, for the untinted vine texture. */
    private const val FOLIAGE = 0x48B518
    private const val VINE_HELICES = 2
    private const val VINE_RADIUS = 0.26
    private const val VINE_HALF_WIDTH = 0.09
    /** Turns per block along the beam. */
    private const val VINE_PITCH = 0.55
    private const val VINE_FLOW = 0.04
    private const val STEPS_PER_BLOCK = 6
    /** How far from the hand, in blocks, the vines take to grow to full. */
    private const val VINE_FADE_IN = 1.6
    private const val LEAF_SPACING = 0.55
    private const val TUBE_SIDES = 16
    /** How fast accents drift outward along a beam, in blocks a tick. */
    private const val ACCENT_DRIFT = 0.02

    fun render(pose: PoseStack, buffers: MultiBufferSource, cam: Vec3, segments: List<BeamSegment>, spell: Spell, gameTime: Double, rgb: Int) {
        if (segments.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val camera = mc.gameRenderer.mainCamera
        val view = SpellView(cam, Vec3(camera.leftVector), Vec3(camera.upVector))
        val els = spell.magnitudes.keys
        val time = SpellClock.of(gameTime)
        pose.pushPose()
        pose.translate(-cam.x, -cam.y, -cam.z)
        val m = pose.last().pose()

        if (Element.POISON in els) {
            val shade = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
            each(segments, 1.4, 0.02, time) { p, u, r, k, g, s ->
                if (k % 3 == 0) {
                    val q = p.add(SpellGeom.around(SpellGeom.basis(s.direction), r.nextDouble() * Math.PI * 2, 0.25 + 0.4 * u)).add(0.0, 0.4 * u, 0.0)
                    SpellShapes.puff(shade, m, view, q, 0.12 + 0.2 * u, if (k % 2 != 0) 0x4F7A2A else 0x2F4A18, 0.35f * sin(Math.PI * u).toFloat() * g)
                }
            }
        }

        if (SpellVfxRenderTypes.isReady() && (Element.GROWTH in els || Element.COLD in els)) {
            val blocks = buffers.getBuffer(SpellVfxRenderTypes.sprite(TextureAtlas.LOCATION_BLOCKS))
            if (Element.GROWTH in els) vines(blocks, m, level, view, segments, gameTime)
            if (Element.COLD in els) {
                val ice = SpellArt.block("packed_ice")
                each(segments, 2.5, 0.025, time) { p, _, _, k, g, s ->
                    if (k % 4 == 0) {
                        val c = p.add(SpellGeom.around(SpellGeom.basis(s.direction), time * 0.04 + k, 0.32))
                        SpellShapes.texBox(blocks, m, SpellGeom.cubeCorners(c, 0.04, time * 0.1 + k, k.toDouble()), ice, 0xFFFFFF, g, SpellShapes.lightAt(level, c), k)
                    }
                }
            }
        }

        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        val sunfire = Element.SUNFIRE in els
        val prism = ScrollBeamClient.prism(spell)
        val pulse = 1 + 0.06 * sin(time * 0.4)
        if (!prism && !sunfire) {
            // A plain volume of the spell's light, with a bright thread through it.
            var along = 0.0
            for (seg in segments) {
                tube(glow, m, cam, seg, along, 0.34 * pulse, rgb, 0.3f)
                tube(glow, m, cam, seg, along, 0.14 * pulse, whiten(rgb, 0.45f), 0.5f)
                SpellShapes.strip(glow, m, view, listOf(seg.start, seg.start.lerp(seg.end, 0.5), seg.end), { 0.05 }, whiten(rgb, 0.7f), 0.6f, false,
                    floatArrayOf(if (along == 0.0) 0.2f else 1f, 1f, 1f))
                along += seg.length
            }
        }
        if (sunfire) {
            // A body of sunlight under the shells, so it holds up against a bright sky.
            var along = 0.0
            for (seg in segments) {
                if (!prism) tube(glow, m, cam, seg, along, 0.2 * pulse, Element.SUNFIRE.color, 0.35f)
                along += seg.length
            }
            for (seg in segments) {
                SpellShapes.strip(glow, m, view, listOf(seg.start, seg.start.lerp(seg.end, 0.25), seg.start.lerp(seg.end, 0.5), seg.end), { 0.045 }, 0xFFF8E6, 0.75f, false,
                    floatArrayOf(0.3f, 0.9f, 1f, 1f))
            }
            SpellShapes.disc(glow, m, view, segments.last().end, 0.3 * (1 + 0.05 * sin(time * 0.35)), Element.SUNFIRE.color, 0.3f)
        }
        if (Element.CHAOS in els) zaps(glow, m, view, segments, time)
        if (Element.PURITY in els) {
            each(segments, 3.0, 0.04, time) { p, _, r, k, g, s ->
                if (k % 6 == 0) {
                    val q = p.add(SpellGeom.around(SpellGeom.basis(s.direction), r.nextDouble() * Math.PI * 2 + time * 0.03, 0.18 + 0.3 * r.nextDouble()))
                    SpellShapes.glowCubeFaces(glow, m, SpellGeom.cubeCorners(q, 0.035, time * 0.1 + k, k.toDouble()), 0xFFFFFF, 0.6f * g, 0.5)
                }
            }
        }

        if (sunfire && SpellVfxRenderTypes.isSunfireReady()) {
            // Two shells of light; with a prism it's a sheath round the prism.
            val sun = buffers.getBuffer(SpellVfxRenderTypes.SUNFIRE)
            val sheath = if (prism) 0.6f else 1f
            val shine = 1 + 0.05 * sin(time * 0.35)
            val total = segments.sumOf { it.length }
            var along = 0.0
            for (seg in segments) {
                sunShell(sun, m, seg, along, total, 0.26 * shine, 0xC98A26, 0.7f * sheath)
                sunShell(sun, m, seg, along, total, (if (prism) 0.17 else 0.13) * shine, 0xE6B53C, 0.9f * sheath)
                along += seg.length
            }
        }
        pose.popPose()
    }

    /**
     * The knot where two beams merge: both parents' colours wound into rings round a white-hot
     * core, the merged spell's glyphs circling it, and a fusion forming in the merge flashing
     * out of it in its own colour for the first moments ([age], ticks since the merge began).
     */
    fun knot(pose: PoseStack, buffers: MultiBufferSource, cam: Vec3, at: Vec3, parentA: Int, parentB: Int, rgb: Int, fused: List<Element>, age: Float, gameTime: Double) {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val view = SpellView(cam, Vec3(camera.leftVector), Vec3(camera.upVector))
        val time = SpellClock.of(gameTime)
        pose.pushPose()
        pose.translate(-cam.x, -cam.y, -cam.z)
        val m = pose.last().pose()
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (k in 0 until 3) {
            val turn = (time * (if (k % 2 != 0) -8.0 else 8.0) + k * 40) * Math.PI / 180
            SpellShapes.ring(glow, m, at, 0.55 - k * 0.12, 0.05, if (k % 2 != 0) parentB else parentA, 0.7f, turn)
        }
        SpellShapes.disc(glow, m, view, at, 0.45, rgb, 0.6f)
        SpellShapes.disc(glow, m, view, at, 0.18, 0xFFFFFF, 0.95f)
        fused.forEachIndexed { i, fusion ->
            val e = (age - 1 - i * 2) / 12f
            if (e > 0f && e < 1f) SpellShapes.disc(glow, m, view, at, 0.6 + 1.6 * e, fusion.color, 0.9f * (1f - e))
        }
        if (SpellVfxRenderTypes.isReady()) {
            val glyphs = buffers.getBuffer(SpellVfxRenderTypes.glow(TextureAtlas.LOCATION_PARTICLES))
            for (i in 0 until 8) {
                val an = time * 0.12 + i * Math.PI / 4
                val p = at.add(0.0, sin(an) * 0.7, 0.0).add(view.left.scale(cos(an) * 0.7))
                SpellShapes.sprite(glyphs, m, view, p, 0.14, SpellArt.particle("sga_" + ('a' + (i * 3) % 26)), whiten(rgb, 0.3f), 0.8f, SpellShapes.FULL_BRIGHT)
            }
        }
        pose.popPose()
    }

    /**
     * Points spread along the beam, each cycling through its own short life ([u], 0..1) as it
     * flows out: about [perBlock] per block, each running [rate] of its life per tick. [g] grows
     * from 0 at the hand to 1 a block out. Nothing in the first 0.4 blocks.
     */
    private inline fun each(segments: List<BeamSegment>, perBlock: Double, rate: Double, time: Float, fn: (Vec3, Double, Random, Int, Float, BeamSegment) -> Unit) {
        val total = segments.sumOf { it.length }
        if (total < 0.3) return
        // Each accent has its own slot at a fixed spacing from the hand, drifting outward, so
        // it keeps its place, pace and progress however long the beam is as it's aimed about.
        val spacing = 1.0 / perBlock
        val drift = time * ACCENT_DRIFT
        val first = Math.floor(-drift / spacing).toInt() - 1
        val last = Math.ceil((total - drift) / spacing).toInt()
        for (k in first..last) {
            val r = Random(k * 7919L + 13)
            val u = ((time * rate + r.nextDouble()) % 1.0 + 1.0) % 1.0
            val d = (k + r.nextDouble()) * spacing + drift
            if (d < 0.4 || d > total) continue
            var left = d
            for (seg in segments) {
                if (left <= seg.length) {
                    fn(seg.start.add(seg.direction.scale(left)), u, r, k, smooth(d / 1.0).toFloat(), seg)
                    break
                }
                left -= seg.length
            }
        }
    }

    /** A rim-lit tube of light along [seg]: brightest where it turns away from the eye, so it
     *  reads as a volume. It fades in over the first half block of the whole beam. */
    private fun tube(c: VertexConsumer, m: Matrix4f, cam: Vec3, seg: BeamSegment, along: Double, radius: Double, rgb: Int, alpha: Float) {
        val (s1, s2) = SpellGeom.basis(seg.direction)
        val steps = maxOf(2, Math.ceil(seg.length * 2).toInt())
        for (j in 0 until steps) {
            val d0 = seg.length * j / steps; val d1 = seg.length * (j + 1) / steps
            val g0 = smooth((along + d0) / 0.5).toFloat(); val g1 = smooth((along + d1) / 0.5).toFloat()
            val a0 = seg.start.add(seg.direction.scale(d0)); val a1 = seg.start.add(seg.direction.scale(d1))
            for (k in 0 until TUBE_SIDES) {
                val o0 = s1.scale(cos(2 * Math.PI * k / TUBE_SIDES)).add(s2.scale(sin(2 * Math.PI * k / TUBE_SIDES)))
                val o1 = s1.scale(cos(2 * Math.PI * (k + 1) / TUBE_SIDES)).add(s2.scale(sin(2 * Math.PI * (k + 1) / TUBE_SIDES)))
                val corners = arrayOf(Triple(a0, o0, g0), Triple(a1, o0, g1), Triple(a1, o1, g1), Triple(a0, o1, g0))
                val p = corners.map { (centre, out, g) -> centre.add(out.scale(radius * (0.4 + 0.6 * g))) }
                val a = FloatArray(4) { i ->
                    val (_, out, g) = corners[i]
                    val rim = 1f - Math.abs(out.dot(p[i].subtract(cam).normalize())).toFloat()
                    alpha * g * (0.1f + 0.9f * rim * rim)
                }
                SpellShapes.quad(c, m, p[0], p[1], p[2], p[3], rgb, a[0], a[1], a[2], a[3])
            }
        }
    }

    /** One shell of Sunfire's light along [seg]: the shader does the rest. */
    private fun sunShell(c: VertexConsumer, m: Matrix4f, seg: BeamSegment, along: Double, total: Double, radius: Double, rgb: Int, alpha: Float) {
        val (s1, s2) = SpellGeom.basis(seg.direction)
        val steps = maxOf(2, Math.ceil(seg.length * 2).toInt())
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        for (j in 0 until steps) {
            val d0 = seg.length * j / steps; val d1 = seg.length * (j + 1) / steps
            for (k in 0 until TUBE_SIDES) {
                val o0 = s1.scale(cos(2 * Math.PI * k / TUBE_SIDES)).add(s2.scale(sin(2 * Math.PI * k / TUBE_SIDES)))
                val o1 = s1.scale(cos(2 * Math.PI * (k + 1) / TUBE_SIDES)).add(s2.scale(sin(2 * Math.PI * (k + 1) / TUBE_SIDES)))
                for ((d, o) in listOf(d0 to o0, d1 to o0, d1 to o1, d0 to o1)) {
                    val p = seg.start.add(seg.direction.scale(d)).add(o.scale(radius))
                    c.vertex(m, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).uv((along + d).toFloat(), total.toFloat())
                        .color(r, g, b, a).normal(o.x.toFloat(), o.y.toFloat(), o.z.toFloat()).endVertex()
                }
            }
        }
    }

    /** Short zaps of lightning crackling off the beam, re-striking every couple of ticks. */
    private fun zaps(c: VertexConsumer, m: Matrix4f, view: SpellView, segments: List<BeamSegment>, time: Float) {
        val strike = (time / 2).toInt()
        for (seg in segments) {
            if (seg.length < 0.6) continue
            val basis = SpellGeom.basis(seg.direction)
            val n = maxOf(2, Math.round(seg.length * 0.6).toInt())
            for (k in 0 until n) {
                val r = Random(k * 131 + strike * 17)
                val d = 0.5 + r.nextDouble() * (seg.length - 0.5)
                if (r.nextDouble() < 0.35) continue
                val a = seg.start.add(seg.direction.scale(d))
                val b = a.add(seg.direction.scale((r.nextDouble() - 0.3) * 0.8)).add(SpellGeom.around(basis, r.nextDouble() * Math.PI * 2, 0.5 + 0.6 * r.nextDouble()))
                val pts = SpellGeom.jagged(r, a, b, 0.2)
                val w = { i: Int -> 1 - 0.85 * (i.toDouble() / (pts.size - 1)).pow(1.4) }
                SpellShapes.strip(c, m, view, pts, { 0.14 * w(it) }, Element.CHAOS.color, 0.5f, true)
                SpellShapes.strip(c, m, view, pts, { 0.03 * w(it) }, whiten(Element.CHAOS.color, 0.75f), 1f, false)
            }
        }
    }

    // ---- Growth: vines and leaves -----------------------------------------------------------

    /** Where helix [k] is [d] blocks along [seg], and the outward direction there. The helix
     *  flows outward and sways: its radius breathes and its angle wanders. */
    private fun helix(seg: BeamSegment, k: Int, d: Double, gameTime: Double): Pair<Vec3, Vec3> {
        val (s1, s2) = SpellGeom.basis(seg.direction)
        val sway = 0.35 * sin(d * 0.8 + gameTime * 0.05 + k * 2.1) + 0.15 * sin(d * 2.3 - gameTime * 0.09 + k)
        val angle = 2 * Math.PI * (VINE_PITCH * d - gameTime * VINE_FLOW + k.toDouble() / VINE_HELICES) + sway
        val radius = VINE_RADIUS * (1.0 + 0.25 * sin(d * 1.3 - gameTime * 0.08 + k * 2.0))
        val out = s1.scale(cos(angle)).add(s2.scale(sin(angle)))
        return seg.start.add(seg.direction.scale(d)).add(out.scale(radius)) to out
    }

    /** 0 at the hand, growing smoothly to 1 over [VINE_FADE_IN] blocks. */
    private fun growIn(distance: Double): Float = smooth(distance / VINE_FADE_IN).toFloat()

    private fun vines(c: VertexConsumer, m: Matrix4f, level: ClientLevel, view: SpellView, segments: List<BeamSegment>, gameTime: Double) {
        val vine = SpellArt.block("vine")
        var along = 0.0
        for (seg in segments) {
            val light = SpellShapes.lightAt(level, seg.start.lerp(seg.end, 0.5))
            for (k in 0 until VINE_HELICES) {
                // The whole vine as one ribbon, so the texture runs on unbroken at 16 texels a block.
                val steps = maxOf(2, (seg.length * STEPS_PER_BLOCK).toInt())
                val points = ArrayList<Vec3>(); val outs = ArrayList<Vec3>(); val grows = FloatArray(steps + 1)
                for (i in 0..steps) {
                    val d = seg.length * i / steps
                    val (p, out) = helix(seg, k, d, gameTime)
                    points += p; outs += out; grows[i] = growIn(along + d)
                }
                val edges = List(points.size) { i ->
                    val alongDir = points[minOf(steps, i + 1)].subtract(points[maxOf(0, i - 1)])
                    alongDir.cross(outs[i]).normalize().scale(VINE_HALF_WIDTH * (0.3 + 0.7 * grows[i]))
                }
                SpellShapes.stripPx(c, m, points, edges, vine, FOLIAGE, grows, if (k == 1) 0.3 else 0.7, light)
                // Leaf chips riding the vine outward.
                val flow = (gameTime * VINE_FLOW / VINE_PITCH) % LEAF_SPACING
                var d = flow + k * LEAF_SPACING / VINE_HELICES
                var i = 0
                while (d < seg.length) {
                    val g = growIn(along + d)
                    if (g > 0.3f) {
                        val (at, out) = helix(seg, k, d, gameTime)
                        val r = Random(i * 977 + k * 13)
                        val roll = (r.nextInt(4) * Math.PI / 2) + 0.35 * sin(gameTime * 0.15 + r.nextDouble() * 6.28)
                        SpellShapes.leafChip(c, m, view, at.add(out.scale(0.06)), 0.16 + 0.06 * r.nextDouble(), roll, i * 2 + k, if (i % 2 != 0) 0x6FBF3A else 0x4A9A25, 1f, light)
                    }
                    d += LEAF_SPACING
                    i++
                }
            }
            along += seg.length
        }
    }
}
