package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.TextureAtlas
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

/**
 * A spell held or thrown: a cube for each of its elements, of that element's own block, its volume
 * in proportion to the element's share of the glyphs (together, one block's worth), drifting round
 * each other, with enchanting glyphs round them.
 *
 * - **Carried** (in the hand, not being cast): the cubes drift loosely with a few glyphs.
 * - **Held** (charging a Bolt, or holding a Beam, Spray or Chain): the loose glyphs are drawn into
 *   a circle across the arm as it charges, and a ring of light comes up as they settle.
 * - **Thrown** (a Bolt in flight): the cubes tumble through their glyph circle, which faces along
 *   the flight.
 *
 * Each element's cube carries its own screen effect round it ([SpellCubeFx]), Cold's cube has
 * icy wind circling it, and Blood's beats like a heart. Everything is drawn in [Draw.pose]'s
 * space, with [Draw.view] the camera there; [axis] is the arm or the flight.
 */
object SpellCore {
    /** Where and how a spell is being drawn; [inHand] if in your own first-person hand. */
    class Draw(val pose: PoseStack.Pose, val buffers: MultiBufferSource, val view: SpellView, val light: Int, val time: Float, val inHand: Boolean = false)

    private val ELEMENT_BLOCK = mapOf(
        Element.HEAT to "magma", Element.COLD to "blue_ice", Element.GROWTH to "moss_block", Element.PURITY to "calcite",
        Element.GRAVITY to "obsidian", Element.CHAOS to "purpur_block", Element.BLOOD to "nether_wart_block",
        Element.SUNFIRE to "shroomlight", Element.POISON to "slime_block", Element.VOID to "crying_obsidian",
    )
    private const val CARRY_R = 0.1

    fun carried(d: Draw, centre: Vec3, spell: Spell, axis: Vec3) {
        cubes(d, centre, CARRY_R * 0.6, spell, d.time * 0.5f, axis, 0.25f)
        looseGlyphs(d, centre, CARRY_R * 1.25, portions(spell, 6).map { it.color }, d.time, axis, 0.55f, 0f)
    }

    /** A held spell's size at [charge] 0..1, swelling and easing back with [time]: the same in
     *  your own first-person hand as in anyone's hand in the world. */
    fun heldSize(charge: Float, time: Float): Double = (0.11 + 0.05 * charge) * (1 + 0.06 * sin(time * 0.5))

    /** The camera in [pose]'s space, when [pose] goes from that space to the camera's (looking
     *  down -z from the origin), as an entity's or the first-person hand's does. */
    fun viewIn(pose: PoseStack.Pose): SpellView {
        val inv = Matrix4f(pose.pose()).invert()
        val eye = inv.transformPosition(Vector3f())
        val rot = Matrix3f(pose.normal()).transpose()
        val left = rot.transform(Vector3f(-1f, 0f, 0f)).normalize()
        val up = rot.transform(Vector3f(0f, 1f, 0f)).normalize()
        return SpellView(Vec3(eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble()), Vec3(left.x.toDouble(), left.y.toDouble(), left.z.toDouble()), Vec3(up.x.toDouble(), up.y.toDouble(), up.z.toDouble()))
    }

    /** [charge] 0..1 (1 for a Beam, Spray or Chain); [r] the core's size ([heldSize]). */
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

    // ---- Pieces ---------------------------------------------------------------------------------

    /** [n] slots shared among the spell's elements in proportion to their glyphs (largest
     *  remainder), dealt out so each element's share is spread through rather than bunched. */
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

    /** One cube per element, sized by its share, drifting round [centre] round [axis]. */
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
            // Blood's cube beats like a heart, in time with its pulse on the screen.
            if (e == Element.BLOOD) h *= 1 + 0.18 * heartbeat(d.time)
            val n = els.size
            val an = time * (0.018 + 0.006 * i) * (if (i % 2 == 1) -1 else 1) + i * 2 * Math.PI / n
            val rad = if (n == 1) 0.0 else half * (1.15 + 0.3 * sin(time * 0.025 + i * 1.7))
            val p = centre.add(up.scale(half * 0.45 * sin(time * 0.03 + i * 2.1))).add(s1.scale(cos(an) * rad)).add(s2.scale(sin(an) * rad))
            Placed(e, i, p, h, (time * (0.05 + 0.02 * i) + i).toDouble(), (time * 0.04 + i * 0.7).toDouble())
        }
        // One buffer at a time: fetching another from the same source ends the last one's batch.
        val solid = d.buffers.getBuffer(SpellVfxRenderTypes.solid(TextureAtlas.LOCATION_BLOCKS))
        for (c in placed) SpellShapes.texBox(solid, m, SpellGeom.cubeCorners(c.p, c.h, c.yaw, c.pitch, 0.5), SpellArt.block(ELEMENT_BLOCK[c.e] ?: "obsidian"), 0xFFFFFF, 1f, d.light, c.i)
        val glow = d.buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (c in placed) SpellShapes.glowCubeFaces(glow, m, SpellGeom.cubeCorners(c.p, c.h * 1.15, c.yaw, c.pitch, 0.5), c.e.color, glowA, 0.3)
        for (c in placed) {
            SpellCubeFx.record(m, c.p, c.h, c.e, d.inHand)
            if (c.e == Element.COLD) coldWind(d, c.p, c.h, c.i)
        }
    }

    /** Glyphs drifting lazily round [c] at their own heights and speeds; [toRing] 0..1 draws
     *  them into a ring across [axis]. One glyph per colour in [cols]. */
    private fun looseGlyphs(d: Draw, c: Vec3, r: Double, cols: List<Int>, time: Float, axis: Vec3, alpha: Float, toRing: Float) {
        if (!SpellVfxRenderTypes.isReady()) return
        val m = d.pose.pose()
        val buf = d.buffers.getBuffer(SpellVfxRenderTypes.glow(TextureAtlas.LOCATION_PARTICLES))
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

    /** A circle of [n] glyphs turning round [c], across [axis]. */
    private fun glyphRing(d: Draw, c: Vec3, radius: Double, rgb: Int, time: Float, axis: Vec3, n: Int, size: Double) {
        if (!SpellVfxRenderTypes.isReady()) return
        val m = d.pose.pose()
        val buf = d.buffers.getBuffer(SpellVfxRenderTypes.glow(TextureAtlas.LOCATION_PARTICLES))
        val (s1, s2) = SpellGeom.basis(axis.normalize())
        for (i in 0 until n) {
            val an = time * 0.11 + i * 2 * Math.PI / n
            SpellShapes.sprite(buf, m, d.view, c.add(s1.scale(cos(an) * radius)).add(s2.scale(sin(an) * radius)), size, glyph(i * 5), whiten(rgb, 0.2f), 0.95f, SpellShapes.FULL_BRIGHT)
        }
    }

    /** A thin ring of light round [c], across [axis]. */
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

    /** Cold's wind round a cube at [p], [h] its half-width: three thin icy ribbons on tilted
     *  orbits, each a bright head with a fading, thinning tail, passing in front of the cube and
     *  behind it. */
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

    private fun glyph(i: Int): Sprite = SpellArt.particle("sga_" + ('a' + Math.floorMod(i, 26)))

    /** Blood's heartbeat, 0..1: a beat and a softer second beat, as the screen pulse beats. */
    fun heartbeat(time: Float): Double {
        val k = time * 0.25
        return max(0.0, sin(k.toDouble())).pow(8) + 0.6 * max(0.0, sin(k - 0.6)).pow(8)
    }
}
