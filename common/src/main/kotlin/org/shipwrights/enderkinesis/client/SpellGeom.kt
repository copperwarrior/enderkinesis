package org.shipwrights.enderkinesis.client

import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.shipwrights.enderkinesis.util.smoothstep

object SpellGeom {
    val UP = Vec3(0.0, 1.0, 0.0)

    /** Corner indices follow [cubeCorners]. */
    val FACES = arrayOf(
        intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4),
        intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5),
    )
    val EDGES = arrayOf(
        intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(4, 5), intArrayOf(6, 7),
        intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(4, 6), intArrayOf(5, 7),
        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7),
    )

    fun basis(dir: Vec3): Pair<Vec3, Vec3> {
        val s1 = dir.cross(if (Math.abs(dir.y) < 0.9) UP else Vec3(1.0, 0.0, 0.0)).normalize()
        return s1 to dir.cross(s1)
    }

    fun around(s: Pair<Vec3, Vec3>, angle: Double, r: Double): Vec3 =
        s.first.scale(cos(angle) * r).add(s.second.scale(sin(angle) * r))

    /** The rotation order is [roll] about z, then [pitch] about x, then [yaw] about the vertical. */
    fun cubeCorners(c: Vec3, half: Double, yaw: Double, pitch: Double, roll: Double = 0.0, hy: Double = half, hz: Double = half): Array<Vec3> {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch); val cr = cos(roll); val sr = sin(roll)
        return Array(8) { i ->
            var x = if (i and 1 != 0) half else -half
            var y = if (i and 2 != 0) hy else -hy
            val z = if (i and 4 != 0) hz else -hz
            val x1 = x * cr - y * sr; val y1 = x * sr + y * cr; x = x1; y = y1
            val y2 = y * cp - z * sp; val z2 = y * sp + z * cp
            Vec3(c.x + x * cy - z2 * sy, c.y + y2, c.z + x * sy + z2 * cy)
        }
    }

    fun prismCorners(from: Vec3, dir: Vec3, length: Double, w: Double, spin: Double): Array<Vec3> {
        val (s1, s2) = basis(dir)
        val c = cos(spin); val s = sin(spin)
        val a = s1.scale(c).add(s2.scale(s)); val b = s1.scale(-s).add(s2.scale(c))
        return Array(8) { i ->
            from.add(a.scale(if (i and 1 != 0) w else -w)).add(b.scale(if (i and 2 != 0) w else -w)).add(dir.scale(if (i and 4 != 0) length else 0.0))
        }
    }

    fun jagged(random: Random, a: Vec3, b: Vec3, jaggedness: Double): List<Vec3> {
        val span = b.subtract(a)
        val length = span.length()
        val dir = if (length < 1.0e-6) UP else span.scale(1.0 / length)
        val (s1, s2) = basis(dir)
        val steps = (length * 3).toInt().coerceIn(6, 28)
        return List(steps + 1) { i ->
            val f = i.toDouble() / steps
            if (i == 0 || i == steps) a.lerp(b, f)
            else {
                val amp = length * jaggedness * sin(Math.PI * f)
                a.lerp(b, f).add(s1.scale((random.nextDouble() - 0.5) * 2 * amp)).add(s2.scale((random.nextDouble() - 0.5) * 2 * amp))
            }
        }
    }

    fun bezier(a: Vec3, c: Vec3, b: Vec3, u: Double): Vec3 = a.lerp(c, u).lerp(c.lerp(b, u), u)

    fun gauss(random: Random): Double = sqrt(-2 * ln(random.nextDouble() + 1.0e-9)) * cos(2 * Math.PI * random.nextDouble())

    fun spread(random: Random, ySquash: Double = 1.0, yLift: Double = 0.0): Vec3 =
        Vec3(gauss(random), gauss(random) * ySquash + yLift, gauss(random)).normalize()

    fun smooth(x: Double): Double = smoothstep(x)
    fun smooth(x: Float): Float = smoothstep(x)
    fun easeOut(t: Double): Double = 1 - (1 - t) * (1 - t) * (1 - t)

    fun whiten(rgb: Int, f: Float): Int {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        return ((r + (255 - r) * f).toInt() shl 16) or ((g + (255 - g) * f).toInt() shl 8) or (b + (255 - b) * f).toInt()
    }

    fun darken(rgb: Int, f: Float): Int {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        return ((r * f).toInt() shl 16) or ((g * f).toInt() shl 8) or (b * f).toInt()
    }
}
