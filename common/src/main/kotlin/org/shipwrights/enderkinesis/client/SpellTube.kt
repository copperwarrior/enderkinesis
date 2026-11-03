package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.sin

/** Rim-lit tubes along a beam path, with the warbly radius of the Supplication of Sselith. [cam] and the piece points
 *  must be in the frame that [m] draws from. */
object SpellTube {
    private const val TUBE_SIDES = 12
    private const val TUBE_STEPS_PER_BLOCK = 2.0

    /** Distance from the hand, in blocks, at which a beam gets to full width. */
    private const val FADE_IN = 0.6
    private val SIDE_COS = DoubleArray(TUBE_SIDES + 1) { cos(2 * Math.PI * it / TUBE_SIDES) }
    private val SIDE_SIN = DoubleArray(TUBE_SIDES + 1) { sin(2 * Math.PI * it / TUBE_SIDES) }

    /** [from] is the distance from the hand to [start], in blocks. */
    class Piece(val start: Vec3, val dir: Vec3, val length: Double, val from: Double) {
        val basis = SpellGeom.basis(dir)
        fun at(dist: Double): Vec3 = start.add(dir.scale(dist))
    }

    fun growIn(dist: Double): Double = SpellGeom.smooth(dist / FADE_IN)

    fun throb(dist: Double, age: Float): Double = 1 + 0.15 * sin(dist * 1.1 - age * 0.45) + 0.08 * sin(dist * 2.9 + age * 0.7)

    fun pulse(dist: Double, age: Float, speed: Double, spacing: Double): Double {
        val x = ((dist - age * speed) % spacing + spacing) % spacing
        val off = minOf(x, spacing - x) / 0.7
        return Math.exp(-off * off)
    }

    /** A light tube is rim-lit, so it looks like a volume. A [dark] tube is densest where it faces the eye. */
    @Suppress("LongParameterList") // Shape options, all with defaults past the colour.
    fun tube(
        c: VertexConsumer, m: Matrix4f, cam: Vec3, pieces: List<Piece>, radius: (Double) -> Double, rgb: Int, alpha: Float,
        dark: Boolean = false, grow: Boolean = true, alphaAt: (Double) -> Float = { 1f },
    ) {
        val red = (rgb shr 16) and 0xFF; val green = (rgb shr 8) and 0xFF; val blue = rgb and 0xFF
        for (p in pieces) {
            val (s1, s2) = p.basis
            val steps = maxOf(2, Math.ceil(p.length * TUBE_STEPS_PER_BLOCK).toInt())
            for (j in 0 until steps) {
                val d0 = p.length * j / steps; val d1 = p.length * (j + 1) / steps
                val c0 = p.at(d0); val c1 = p.at(d1)
                val g0 = if (grow) growIn(p.from + d0) else 1.0; val g1 = if (grow) growIn(p.from + d1) else 1.0
                val r0 = radius(p.from + d0) * (0.4 + 0.6 * g0); val r1 = radius(p.from + d1) * (0.4 + 0.6 * g1)
                val f0 = alphaAt(p.from + d0); val f1 = alphaAt(p.from + d1)
                if (f0 <= 0.004f && f1 <= 0.004f) continue
                val a0 = (g0 * f0).toFloat(); val a1 = (g1 * f1).toFloat()
                for (k in 0 until TUBE_SIDES) {
                    vertex(c, m, cam, s1, s2, k, c0, r0, alpha * a0, dark, red, green, blue)
                    vertex(c, m, cam, s1, s2, k, c1, r1, alpha * a1, dark, red, green, blue)
                    vertex(c, m, cam, s1, s2, k + 1, c1, r1, alpha * a1, dark, red, green, blue)
                    vertex(c, m, cam, s1, s2, k + 1, c0, r0, alpha * a0, dark, red, green, blue)
                }
            }
        }
    }

    @Suppress("LongParameterList") // Hot path: raw values, no allocation for each vertex.
    private fun vertex(
        c: VertexConsumer, m: Matrix4f, cam: Vec3, s1: Vec3, s2: Vec3, k: Int, centre: Vec3, r: Double, alpha: Float,
        dark: Boolean, red: Int, green: Int, blue: Int,
    ) {
        val co = SIDE_COS[k]; val si = SIDE_SIN[k]
        val ox = s1.x * co + s2.x * si; val oy = s1.y * co + s2.y * si; val oz = s1.z * co + s2.z * si
        val px = centre.x + ox * r; val py = centre.y + oy * r; val pz = centre.z + oz * r
        val vx = px - cam.x; val vy = py - cam.y; val vz = pz - cam.z
        val len = Math.sqrt(vx * vx + vy * vy + vz * vz)
        // Vec3.normalize gives ZERO below 1e-4.
        val facing = (if (len < 1.0e-4) 0.0 else Math.abs(ox * vx + oy * vy + oz * vz) / len).toFloat()
        val shade = if (dark) 0.35f + 0.65f * facing * facing else (1f - facing).let { 0.1f + 0.9f * it * it }
        SpellShapes.vertex(c, m, px, py, pz, red, green, blue, alpha * shade)
    }
}
