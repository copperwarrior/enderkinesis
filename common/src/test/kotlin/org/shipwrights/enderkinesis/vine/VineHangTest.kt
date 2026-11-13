package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class VineHangTest {

    private val eps = 1.0e-9
    private val clearance = VINE_HALF_WIDTH + DRAWN_GAP

    @Test
    fun `the rope is in front of the left shoulder at every look`() {
        // Yaw 0 looks south (+z) and the left is east (+x): the rope is south-east of the body.
        val south = orbitTargetAngle(0.0)
        -cos(south) shouldBe (sin(rad(90.0 - HANG_ROPE_FORWARD_DEGREES)) plusOrMinus eps)
        -sin(south) shouldBe (cos(rad(90.0 - HANG_ROPE_FORWARD_DEGREES)) plusOrMinus eps)
        for (step in 0 until 72) {
            val yaw = step * 5.0 - 180.0
            val angle = orbitTargetAngle(yaw)
            // The rope seen from the body is opposite to the body offset from the rope.
            val ropeX = -cos(angle); val ropeZ = -sin(angle)
            val ahead = ropeX * -sin(rad(yaw)) + ropeZ * cos(rad(yaw))
            val left = ropeX * cos(rad(yaw)) + ropeZ * sin(rad(yaw))
            ahead shouldBe (sin(rad(HANG_ROPE_FORWARD_DEGREES)) plusOrMinus eps)
            left shouldBe (cos(rad(HANG_ROPE_FORWARD_DEGREES)) plusOrMinus eps)
        }
    }

    @Test
    fun `the rope never goes through the drawn torso or head`() {
        val px = 0.9375 / 16
        val ropeClearance = clearance - 1e-9
        for (step in 0 until 360) {
            // The orbit ease and a wall slide can put the rope at any angle to the body, so test every angle.
            val a = rad(step.toDouble())
            val left = sin(a) * HANG_RADIUS; val ahead = cos(a) * HANG_RADIUS
            boxGap(left, ahead, 4 * px, 4 * px) shouldBeGreaterThanOrEqual ropeClearance
            boxGap(left, ahead, 4 * px, 2 * px) shouldBeGreaterThanOrEqual ropeClearance
        }
        // At the hang angle the rope also clears a pitched head, which turns about the side axis and stays 8 px wide.
        val hang = rad(90.0 - HANG_ROPE_FORWARD_DEGREES)
        sin(hang) * HANG_RADIUS - 4 * px shouldBeGreaterThanOrEqual ropeClearance
    }

    @Test
    fun `the side offset puts the rope just outside the drawn model at every look`() {
        val clear = 2.0 / 16 + DRAWN_GAP
        val half = 4.0 * 0.9375 / 16
        HANG_RADIUS shouldBe (max(half * sqrt(2.0) + clear, (half + clear) / cos(rad(HANG_ROPE_FORWARD_DEGREES))) plusOrMinus eps)
        for (step in 0 until 72) {
            val angle = orbitTargetAngle(step * 5.0)
            val cx = cos(angle) * HANG_RADIUS
            val cz = sin(angle) * HANG_RADIUS
            hangDrop(cx, cz, 0.0, 0.0, Double.POSITIVE_INFINITY, 0.0) shouldBe HAND_REACH
        }
        hangDrop(0.0, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, 0.0) shouldBe Double.POSITIVE_INFINITY
    }

    @Test
    fun `the whole orbit is free on a steep rope that leans, and the rope stays out of the model`() {
        // Real ropes are never exactly vertical: the old square-slab drop blocked almost every side here.
        for (deg in listOf(80.0, 85.0, 89.0, 89.9)) {
            val t = tan(rad(deg))
            for (lean in listOf(0.0, 30.0, 77.0)) {
                val dx = cos(rad(lean)); val dz = sin(rad(lean))
                for (k in 0 until 64) {
                    val a = k * PI / 32
                    val r = sideRadius(a, dx, dz, t, 10.0)
                    r.isNaN() shouldBe false
                    r shouldBeGreaterThanOrEqual HANG_RADIUS
                    r shouldBeLessThanOrEqual HANG_RADIUS + MAX_SIDE_EXTRA
                    ropeClearsModel(cos(a) * r, sin(a) * r, HAND_REACH, dx, dz, t, 10.0)
                }
                // Across the lean the body needs no extra radius.
                sideRadius(atan2(dx, -dz), dx, dz, t, 10.0) shouldBe (HANG_RADIUS plusOrMinus eps)
            }
        }
    }

    @Test
    fun `the slope picks the under-hang on level ropes and the side hang on steep ones`() {
        sideHangWeight(1.0, 0.0, 0.0) shouldBe 0.0
        sideHangWeight(cos(rad(30.0)), -sin(rad(30.0)), 0.0) shouldBe 0.0
        sideHangWeight(cos(rad(HANG_SLOPE_UNDER)), sin(rad(HANG_SLOPE_UNDER)), 0.0) shouldBe (0.0 plusOrMinus eps)
        val mid = (HANG_SLOPE_UNDER + HANG_SLOPE_SIDE) / 2
        sideHangWeight(0.0, -sin(rad(mid)), cos(rad(mid))) shouldBe (0.5 plusOrMinus 1e-6)
        sideHangWeight(cos(rad(HANG_SLOPE_SIDE)), sin(rad(HANG_SLOPE_SIDE)), 0.0) shouldBe (1.0 plusOrMinus eps)
        sideHangWeight(0.0, -1.0, 0.0) shouldBe 1.0
        var last = 0.0
        for (deg in 0..90) {
            val w = sideHangWeight(cos(rad(deg.toDouble())), sin(rad(deg.toDouble())), 0.0)
            w shouldBeGreaterThanOrEqual last
            last = w
        }
    }

    @Test
    fun `a free-hanging end centres the body under it`() {
        endHangWeight(0.0, true) shouldBe 0.0
        endHangWeight(END_BLEND, true) shouldBe 1.0
        endHangWeight(0.0, false) shouldBe 1.0
    }

    @Test
    fun `the under-hang drops the head under a slanted rope`() {
        for (deg in listOf(0.0, 10.0, 20.0, 35.0, 50.0)) {
            val t = tan(rad(deg))
            for (reach in listOf(0.0, 0.2, 5.0)) {
                val drop = hangDrop(0.0, 0.0, 1.0, 0.0, t, reach)
                drop shouldBeGreaterThanOrEqual HAND_REACH
                ropeClearsModel(0.0, 0.0, drop, 1.0, 0.0, t, reach)
            }
        }
    }

    @Test
    fun `the drop clears the rope for an offset box on a slanted rope`() {
        val dx = cos(rad(30.0)); val dz = sin(rad(30.0))
        for (deg in listOf(15.0, 40.0, 55.0)) {
            val t = tan(rad(deg))
            for (k in 0 until 16) {
                val a = k * PI / 8
                val cx = cos(a) * 0.4; val cz = sin(a) * 0.4
                val drop = hangDrop(cx, cz, dx, dz, t, 3.0)
                ropeClearsModel(cx, cz, drop, dx, dz, t, 3.0)
            }
        }
    }

    @Test
    fun `a free look returns the look side`() {
        chooseOrbitAngle(1.0, 0.0) { false } shouldBe 1.0
    }

    @Test
    fun `a wall on the look side slides the body round to the nearest free side`() {
        val desired = 0.0
        // A wall blocks every side within 1 radian of the look side.
        val wall = OrbitBlocker { abs(wrapAngle(it - desired)) < 1.0 }
        val towardPlus = chooseOrbitAngle(desired, 0.5, wall)
        towardPlus shouldBe (3 * ORBIT_SEARCH_STEP plusOrMinus eps)
        val towardMinus = chooseOrbitAngle(desired, -0.5, wall)
        towardMinus shouldBe (-3 * ORBIT_SEARCH_STEP plusOrMinus eps)
    }

    @Test
    fun `a wall on one side only sends the body to the other side`() {
        val wall = OrbitBlocker { sin(it) > -0.1 }
        val angle = chooseOrbitAngle(0.0, 0.0, wall)
        sin(angle) shouldBeLessThanOrEqual -0.1
    }

    @Test
    fun `with every side blocked there is no side`() {
        chooseOrbitAngle(0.3, 0.0) { true }.isNaN() shouldBe true
    }

    @Test
    fun `the orbit eases the short way round with a capped step`() {
        val near = PI - 0.05
        val stepped = stepOrbitAngle(near, -near)
        // The short way crosses +pi, not back through 0.
        abs(wrapAngle(stepped - near)) shouldBeLessThanOrEqual 0.1
        abs(stepped) shouldBeGreaterThanOrEqual near
        val turn = stepOrbitAngle(0.0, PI / 2)
        turn shouldBe (MAX_ORBIT_STEP plusOrMinus eps)
        var angle = 0.0
        repeat(60) { angle = stepOrbitAngle(angle, 2.0) }
        angle shouldBe (2.0 plusOrMinus 1e-6)
    }

    /**
     * Samples the rope axis: where it is over the model's round footprint (grown by the rope), it must be at least
     * the rope clearance over the head top at -[drop] or under the feet.
     */
    @Suppress("LongParameterList")
    private fun ropeClearsModel(cx: Double, cz: Double, drop: Double, dx: Double, dz: Double, t: Double, reach: Double) {
        var s = -4.0
        while (s <= reach) {
            val px = s * dx - cx; val pz = s * dz - cz
            if (px * px + pz * pz < (HANG_RADIUS - 1e-6) * (HANG_RADIUS - 1e-6)) {
                val y = -s * t
                val over = y >= -drop + clearance - 1e-9
                val under = y <= -drop - DRAWN_HEIGHT - clearance + 1e-9
                (over || under) shouldBe true
            }
            s += 0.001
        }
    }

    /** Distance from the point ([x], [z]) to a centred box of half sizes [hx] and [hz]. */
    private fun boxGap(x: Double, z: Double, hx: Double, hz: Double): Double {
        val dx = max(abs(x) - hx, 0.0); val dz = max(abs(z) - hz, 0.0)
        return sqrt(dx * dx + dz * dz)
    }

    private fun rad(deg: Double) = Math.toRadians(deg)
}
