package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.client.SpellGeom.whiten
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Spell
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

object SpellCore {
    /** [view] is the camera in the space of [pose]. [inHand] is true in the local first-person hand. */
    class Draw(val pose: PoseStack.Pose, val buffers: MultiBufferSource, val view: SpellView, val light: Int, val time: Float, val inHand: Boolean = false)

    private val ELEMENT_BLOCK = mapOf(
        Element.PYRE to "magma", Element.WINTER to "blue_ice", Element.GENESIS to "moss_block", Element.QUINTESSENCE to "calcite",
        Element.GRAVITY to "obsidian", Element.CHAOS to "purpur_block", Element.BLOOD to "nether_wart_block",
        Element.SUNFIRE to "shroomlight", Element.CORRUPTION to "slime_block", Element.VOID to "crying_obsidian",
    )
    private const val CARRY_R = 0.1

    fun carried(d: Draw, centre: Vec3, spell: Spell, axis: Vec3) {
        cubes(d, centre, CARRY_R * 0.6, spell, d.time * 0.5f, axis, 0.25f)
        looseGlyphs(d, centre, CARRY_R * 1.25, portions(spell, 6).map { it.color }, d.time, axis, 0.55f, 0f)
    }

    /** Use the same size for the first-person hand and for hands in the world. */
    fun heldSize(charge: Float, time: Float): Double = (0.11 + 0.05 * charge) * (1 + 0.06 * sin(time * 0.5))

    /** [pose] must map to camera space, which looks down -z from the origin. */
    fun viewIn(pose: PoseStack.Pose): SpellView {
        val inv = Matrix4f(pose.pose()).invert()
        val eye = inv.transformPosition(Vector3f())
        val rot = Matrix3f(pose.normal()).transpose()
        val left = rot.transform(Vector3f(-1f, 0f, 0f)).normalize()
        val up = rot.transform(Vector3f(0f, 1f, 0f)).normalize()
        return SpellView(Vec3(eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble()), Vec3(left.x.toDouble(), left.y.toDouble(), left.z.toDouble()), Vec3(up.x.toDouble(), up.y.toDouble(), up.z.toDouble()))
    }

    /** [charge] is 1 for a Beam, Spray or Chain. [r] comes from [heldSize]. */
    fun held(d: Draw, centre: Vec3, r: Double, spell: Spell, charge: Float, axis: Vec3) {
        val els = spell.magnitudes.keys.toList()
        cubes(d, centre, r * 0.6, spell, d.time, axis, 0.25f + 0.35f * charge)
        looseGlyphs(d, centre, r, List(11) { els[it % els.size].color }, d.time, axis, 0.6f + 0.35f * charge, SpellGeom.smooth(charge))
        axisRing(d, centre, axis, r * 1.9, r * 0.08, spell.tint(), 0.45f * SpellGeom.smooth((charge - 0.5f) / 0.5f))
    }

    fun thrown(d: Draw, centre: Vec3, r: Double, spell: Spell, axis: Vec3) {
        cubes(d, centre, r * 0.75, spell, d.time * 1.6f, axis, 0.4f)
        glyphRing(d, centre, r * 1.4, spell.tint(), d.time, axis, 9, r * 0.45)
    }

    /** Shares use the largest remainder. The deal spreads each element through the slots, so that they do not bunch. */
    fun portions(spell: Spell, n: Int): List<Element> {
        val total = spell.magnitudes.values.sum()
        val shares = spell.magnitudes.map { (e, m) -> Triple(e, m * n / total, (m * n / total).toInt()) }.toMutableList()
        var left = n - shares.sumOf { it.third }
        val counts = shares.associate { it.first to it.third }.toMutableMap()
        for (sh in shares.sortedByDescending { it.second - it.third }) if (left-- > 0) counts[sh.first] = counts.getValue(sh.first) + 1
        val dealt = counts.flatMap { (e, c) -> List(c) { e } }
        val order = intArrayOf(0, 7, 3, 4, 1, 6, 2, 5, 8, 15, 11, 12, 9, 14, 10, 13)
        val placed = arrayOfNulls<Element>(n)
        dealt.forEachIndexed { i, e -> placed[order[i % order.size] % n] = e }
        return placed.mapIndexed { i, e -> e ?: dealt[i % dealt.size] }
    }

    private fun cubes(d: Draw, centre: Vec3, half: Double, spell: Spell, time: Float, axis: Vec3, glowA: Float) {
        if (!SpellVfxRenderTypes.isReady()) return
        val m = d.pose.pose()
        val els = spell.magnitudes.keys.toList()
        val total = spell.magnitudes.values.sum()
        val up = axis.normalize()
        val (s1, s2) = SpellGeom.basis(up)
        class Placed(val e: Element, val i: Int, val p: Vec3, val h: Double, val yaw: Double, val pitch: Double)
        val placed = els.mapIndexed { i, e ->
            var h = half * cbrt((spell.magnitude(e) / total).toDouble())
            if (e == Element.BLOOD) h *= 1 + 0.18 * heartbeat(d.time)
            val n = els.size
            val an = time * (0.018 + 0.006 * i) * (if (i % 2 == 1) -1 else 1) + i * 2 * Math.PI / n
            val rad = if (n == 1) 0.0 else half * (1.15 + 0.3 * sin(time * 0.025 + i * 1.7))
            val p = centre.add(up.scale(half * 0.45 * sin(time * 0.03 + i * 2.1))).add(s1.scale(cos(an) * rad)).add(s2.scale(sin(an) * rad))
            Placed(e, i, p, h, (time * (0.05 + 0.02 * i) + i).toDouble(), (time * 0.04 + i * 0.7).toDouble())
        }
        // Fetch one buffer at a time. A new fetch from the same source ends the batch of the last buffer.
        val solid = d.buffers.getBuffer(SpellVfxRenderTypes.solid(BLOCK_ATLAS))
        for (c in placed) SpellShapes.texBox(solid, m, SpellGeom.cubeCorners(c.p, c.h, c.yaw, c.pitch, 0.5), SpellArt.block(ELEMENT_BLOCK[c.e] ?: "obsidian"), 0xFFFFFF, 1f, d.light, c.i)
        val glow = d.buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (c in placed) SpellShapes.glowCubeFaces(glow, m, SpellGeom.cubeCorners(c.p, c.h * 1.15, c.yaw, c.pitch, 0.5), c.e.color, glowA, 0.3)
        for (c in placed) {
            SpellCubeFx.record(m, c.p, c.h, c.e, d.inHand)
            if (c.e == Element.WINTER) coldWind(d, c.p, c.h, c.i)
        }
    }

    /** [toRing] 0..1 moves the glyphs into a ring across [axis]. */
    private fun looseGlyphs(d: Draw, c: Vec3, r: Double, cols: List<Int>, time: Float, axis: Vec3, alpha: Float, toRing: Float) {
        if (!SpellVfxRenderTypes.isReady()) return
        val m = d.pose.pose()
        val buf = d.buffers.getBuffer(SpellVfxRenderTypes.glow(PARTICLE_ATLAS))
        val up = axis.normalize()
        val (s1, s2) = SpellGeom.basis(up)
        val n = cols.size
        for (i in 0 until n) {
            val an = time * (0.03 + 0.012 * i) * (if (i % 2 == 1) -1 else 1) + i * 1.7
            val rad = r * (1.2 + 0.35 * sin(time * 0.02 + i))
            val loose = c.add(up.scale(r * 0.5 * sin(time * 0.025 + i * 2.1))).add(s1.scale(cos(an) * rad)).add(s2.scale(sin(an) * rad))
            val ringAn = time * 0.11 + i * 2 * Math.PI / n
            val ring = c.add(s1.scale(cos(ringAn) * r * 1.4)).add(s2.scale(sin(ringAn) * r * 1.4))
            SpellShapes.sprite(buf, m, d.view, loose.lerp(ring, toRing.toDouble()), r * 0.45, glyph(i * 5 + 2), whiten(cols[i], 0.2f), alpha, SpellShapes.FULL_BRIGHT)
        }
    }

    private fun glyphRing(d: Draw, c: Vec3, radius: Double, rgb: Int, time: Float, axis: Vec3, n: Int, size: Double) {
        if (!SpellVfxRenderTypes.isReady()) return
        val m = d.pose.pose()
        val buf = d.buffers.getBuffer(SpellVfxRenderTypes.glow(PARTICLE_ATLAS))
        val (s1, s2) = SpellGeom.basis(axis.normalize())
        for (i in 0 until n) {
            val an = time * 0.11 + i * 2 * Math.PI / n
            SpellShapes.sprite(buf, m, d.view, c.add(s1.scale(cos(an) * radius)).add(s2.scale(sin(an) * radius)), size, glyph(i * 5), whiten(rgb, 0.2f), 0.95f, SpellShapes.FULL_BRIGHT)
        }
    }

    private fun axisRing(d: Draw, c: Vec3, axis: Vec3, radius: Double, width: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        val m = d.pose.pose()
        val glow = d.buffers.getBuffer(AegisRenderType.FACE_GLOW)
        val (s1, s2) = SpellGeom.basis(axis.normalize())
        fun at(an: Double, rr: Double) = c.add(s1.scale(cos(an) * rr)).add(s2.scale(sin(an) * rr))
        for (k in 0 until 32) {
            val a0 = 2 * Math.PI * k / 32; val a1 = 2 * Math.PI * (k + 1) / 32
            SpellShapes.quad(glow, m, at(a0, radius - width), at(a1, radius - width), at(a1, radius), at(a0, radius), rgb, 0f, 0f, alpha, alpha)
            SpellShapes.quad(glow, m, at(a0, radius), at(a1, radius), at(a1, radius + width), at(a0, radius + width), rgb, alpha, alpha, 0f, 0f)
        }
    }

    private fun coldWind(d: Draw, p: Vec3, h: Double, seed: Int) {
        val m = d.pose.pose()
        val glow = d.buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (k in 0 until 3) {
            val tilt = seed * 1.3 + k * 2.1
            val (s1, s2) = SpellGeom.basis(Vec3(sin(tilt), cos(tilt * 0.7) + 0.4, sin(tilt * 1.7)).normalize())
            val radius = h * (1.9 + 0.45 * k)
            val head = d.time * (0.16 + 0.05 * k) + k * 2.2
            val count = 14
            val pts = List(count) { j ->
                val an = head - j * 2.4 / (count - 1)
                val rr = radius * (1 + 0.06 * sin(an * 3 + k))
                p.add(s1.scale(cos(an) * rr)).add(s2.scale(sin(an) * rr))
            }
            val alphas = FloatArray(count) { 1f - it / (count - 1f) }
            SpellShapes.strip(glow, m, d.view, pts, { i -> h * 0.22 * (1 - i / count.toDouble()) }, 0xDFF4FF, 0.75f, true, alphas)
        }
    }

    private fun glyph(i: Int): Sprite = SpellArt.glyph(i)

    /** Keep in time with the Blood screen pulse in the `spell_cube_fx` shader. */
    fun heartbeat(time: Float): Double {
        val k = time * 0.25
        return max(0.0, sin(k.toDouble())).pow(8) + 0.6 * max(0.0, sin(k - 0.6)).pow(8)
    }
}
