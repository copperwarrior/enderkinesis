package org.shipwrights.enderkinesis.worldgen

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Shapes the user picked in the Wogor Tree Picker artifact; numbers match its previews. */
object WogorTreeShapes {
    private const val TAU = PI * 2

    private fun flaredTrunk(t: WogorTreeBuilder, h: Int, flare: Double) {
        for (y in 0 until h) {
            t.disc(0, y, 0, max(0.4, flare - y * 0.6)) { x, yy, z -> t.wood(x, yy, z) }
        }
    }

    fun cypress(t: WogorTreeBuilder, flare: Double, knees: Boolean): Boolean {
        val h = t.ri(11, 16)
        if (!t.columnClear(0, h + 1)) return false
        flaredTrunk(t, h, flare)
        var y = h / 2
        while (y < h) {
            repeat(t.ri(1, 2)) {
                val a = t.rf(0.0, TAU)
                val len = t.rf(1.5, 3.2)
                val end = Pt(cos(a) * len, y + t.rf(0.0, 1.0), sin(a) * len)
                t.logPath(listOf(Pt(0.0, y.toDouble(), 0.0), end))
                t.blob(end.x, end.y + 0.6, end.z, 2.1, 0.9, 2.1, 0.3)
            }
            y += t.ri(1, 2)
        }
        t.blob(0.0, h.toDouble(), 0.0, 3.0, 1.0, 3.0, 0.25)
        t.blob(0.0, h + 1.0, 0.0, 2.0, 0.7, 2.0, 0.2)
        if (knees) kneeRing(t)
        t.vines(0.05)
        return true
    }

    /** Knees rise from the mud to 1–2 blocks above the local water surface (or the mud on dry spots). */
    private fun kneeRing(t: WogorTreeBuilder) {
        repeat(t.ri(8, 14)) {
            val a = t.rf(0.0, TAU)
            val d = t.rf(2.6, 6.5)
            val x = (cos(a) * d).roundToInt()
            val z = (sin(a) * d).roundToInt()
            val floor = (KNEE_SCAN_TOP downTo KNEE_SCAN_BOTTOM).firstOrNull { !t.isOpen(x, it, z) && !t.isWater(x, it, z) }
            if (floor == null || !t.isMud(x, floor, z)) return@repeat
            val surface = (floor + 1..KNEE_SCAN_TOP).lastOrNull { t.isWater(x, it, z) } ?: floor
            for (yy in floor + 1..surface + t.ri(1, 2)) t.wood(x, yy, z)
        }
    }

    fun leaner(t: WogorTreeBuilder): Boolean {
        val a = t.rf(0.0, TAU)
        val h = t.ri(8, 11).toDouble()
        val bend = t.rf(3.0, 5.0)
        val c = cos(a)
        val s = sin(a)
        val trunk = bez(Pt(0.0, 0.0, 0.0), Pt(c * bend * 0.2, h * 0.55, s * bend * 0.2), Pt(c * bend, h, s * bend))
        if (!t.pathClear(trunk)) return false
        t.disc(0, 0, 0, 1.0) { x, y, z -> t.wood(x, y, z) }
        t.logPath(trunk)
        val side = a + t.rf(1.0, 2.0)
        val sideStart = Pt(c * bend * 0.5, h * 0.65, s * bend * 0.5)
        val sideEnd = Pt(sideStart.x + cos(side) * 2.5, sideStart.y + 2, sideStart.z + sin(side) * 2.5)
        t.logPath(listOf(sideStart, sideEnd))
        t.blob(c * (bend + 1.2), h + 1, s * (bend + 1.2), 3.8, 2.0, 3.8, 0.2)
        t.blob(sideEnd.x, h * 0.65 + 2.5, sideEnd.z, 2.0, 1.3, 2.0, 0.2)
        t.vines(0.1)
        return true
    }

    fun tieredWeeper(t: WogorTreeBuilder): Boolean {
        val h = t.ri(10, 13)
        if (!t.columnClear(0, h + 2)) return false
        flaredTrunk(t, h, 1.4)
        for (f in TIERS) {
            val top = f == TIERS.last()
            val ty = (h * f).roundToInt() - if (top) 1 else 0
            val n = t.ri(4, 6)
            val a0 = t.rf(0.0, TAU)
            for (i in 0 until n) {
                val a = a0 + i * TAU / n
                val len = t.rf(2.0, 3.5) * if (top) 0.6 else 1.0
                val end = Pt(cos(a) * len, ty + 1.0, sin(a) * len)
                t.logPath(listOf(Pt(0.0, ty.toDouble(), 0.0), end))
                t.blob(end.x, end.y + 0.5, end.z, 2.2, 1.0, 2.2, 0.2)
            }
            t.blob(0.0, ty + 1.0, 0.0, 2.5, 1.0, 2.5, 0.2)
        }
        t.blob(0.0, h + 1.5, 0.0, 1.8, 1.5, 1.8, 0.15)
        t.curtain(minR = 1.5, p = 0.45, min = 2, max = 4)
        return true
    }

    /** Leans toward the nearest water; returns false when no water lies within reach of the bank. */
    fun bankWeeper(t: WogorTreeBuilder): Boolean {
        val toWater = waterHeading(t) ?: return false
        val a = toWater + t.rf(-0.3, 0.3)
        val c = cos(a)
        val s = sin(a)
        val sway = t.rf(-0.4, 0.4)
        // (u along the lean, y, w across it) → world-relative x/z.
        fun p(u: Double, y: Double, w: Double) = Pt(u * c - w * s, y, u * s + w * c)
        val trunk = bez(p(0.0, 0.0, 0.0), p(1.2, 4.0, sway), p(5.0, 7.5, sway * 5))
        if (!t.pathClear(trunk)) return false
        t.disc(0, 0, 0, 1.1) { x, y, z -> t.wood(x, y, z) }
        t.logPath(trunk)
        t.logPath(listOf(p(3.0, 5.5, sway * 3), p(4.5, 8.5, sway * 3 + 2)))
        t.logPath(listOf(p(3.5, 6.0, sway * 3.5), p(5.5, 8.5, sway * 3.5 - 2)))
        val crown = p(5.5, 9.0, sway * 5)
        t.blob(crown.x, crown.y, crown.z, 4.6, 2.6, 4.6, 0.18)
        t.curtain(minR = 2.0, p = 0.62, min = 4, max = 11, cx = crown.x, cz = crown.z, intoWater = 1)
        return true
    }

    private fun waterHeading(t: WogorTreeBuilder): Double? {
        for (d in 2..BANK_SEARCH) {
            for (k in 0 until HEADINGS) {
                val a = k * TAU / HEADINGS
                val x = (cos(a) * d).roundToInt()
                val z = (sin(a) * d).roundToInt()
                if ((-2..0).any { t.isWater(x, it, z) }) return a
            }
        }
        return null
    }

    fun knobWillow(t: WogorTreeBuilder): Boolean {
        val h = t.ri(3, 4)
        if (!t.columnClear(0, h + 1)) return false
        flaredTrunk(t, h, 1.3)
        t.blob(0.0, h.toDouble(), 0.0, 1.6, 1.2, 1.6, 0.0, wood = true)
        repeat(t.ri(10, 14)) {
            val a = t.rf(0.0, TAU)
            val tilt = t.rf(0.25, 0.7)
            val len = t.rf(3.0, 6.0)
            val c = cos(a)
            val s = sin(a)
            val start = Pt(c * 1.2, h + 0.8, s * 1.2)
            val end = Pt(start.x + c * tilt * len, start.y + len, start.z + s * tilt * len)
            val mid = start.lerp(end, 0.4)
            t.logPath(listOf(start, mid))
            t.leafPath(listOf(mid, end))
            t.blob(end.x, end.y, end.z, 1.2, 1.0, 1.2, 0.3)
        }
        t.curtain(minR = 1.5, p = 0.35, min = 2, max = 4)
        return true
    }

    private val TIERS = doubleArrayOf(0.45, 0.7, 1.0)
    private const val KNEE_SCAN_TOP = 2
    private const val KNEE_SCAN_BOTTOM = -4
    private const val BANK_SEARCH = 7
    private const val HEADINGS = 8
}
