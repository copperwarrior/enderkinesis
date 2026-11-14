package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.round

class VineThrowTest {

    private val spacing = chainSpacing(0.39 + 0.39, VINE_TARGET_SPACING)

    private fun isWholeSpacings(length: Double) = abs(length / spacing - round(length / spacing)) < 1e-7

    @Test
    fun `a throw is one block of vine for each item`() {
        throwLength(1) shouldBe 1.0
        throwLength(64) shouldBe 64.0
    }

    @Test
    fun `the tip speed follows the bow power and stops at a full draw`() {
        throwSpeed(1f) shouldBe (40.0 plusOrMinus 1e-9)
        throwSpeed(0.5f) shouldBe (20.0 plusOrMinus 1e-6)
        throwSpeed(2f) shouldBe (40.0 plusOrMinus 1e-9)
        throwSpeed(-1f) shouldBe 0.0
    }

    /** Flies [flight] with no blocks in the way until it ends; returns the steps. */
    private fun flyFree(flight: ThrowFlight): Int {
        val next = Vector3d()
        var steps = 0
        while (!flight.ended && steps < 10_000) {
            flight.moveTo(next, flight.advance(next), false)
            steps++
        }
        return steps
    }

    private fun pathLength(flight: ThrowFlight): Double {
        val p = flight.points
        var sum = 0.0
        for (i in 0 until flight.count - 1) sum += Vector3d(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]).distance(p[i * 3 + 3], p[i * 3 + 4], p[i * 3 + 5])
        return sum
    }

    @Test
    fun `one arc step moves by the velocity, then drag and gravity act as on an arrow`() {
        val flight = ThrowFlight(Vector3d(0.0, 64.0, 0.0), Vector3d(2.0, 0.0, 0.0), 64.0)
        val next = Vector3d()
        flight.advance(next) shouldBe (2.0 plusOrMinus 1e-12)
        next.x shouldBe (2.0 plusOrMinus 1e-12)
        next.y shouldBe (64.0 plusOrMinus 1e-12)
        flight.velocity.x shouldBe (2.0 * THROW_DRAG plusOrMinus 1e-12)
        flight.velocity.y shouldBe (-THROW_GRAVITY plusOrMinus 1e-12)
        flight.moveTo(next, 2.0, false)
        flight.advance(next)
        (next.y < 64.0) shouldBe true
    }

    @Test
    fun `a free flight stops at exactly the vine length, and its path is that long`() {
        for ((items, speed) in listOf(1 to 2.0, 16 to 2.0, 64 to 2.0, 64 to 0.2, 5 to 0.0)) {
            val flight = ThrowFlight(Vector3d(0.0, 64.0, 0.0), Vector3d(0.0, speed * 0.6, speed * 0.8), throwLength(items))
            val steps = flyFree(flight)
            flight.ended shouldBe true
            (steps < MAX_FLIGHT_STEPS) shouldBe true
            flight.flown shouldBe (throwLength(items) plusOrMinus 1e-9)
            pathLength(flight) shouldBe (throwLength(items) plusOrMinus 1e-2)
            flight.tip.distance(flight.points[(flight.count - 1) * 3], flight.points[(flight.count - 1) * 3 + 1],
                flight.points[(flight.count - 1) * 3 + 2]) shouldBe (0.0 plusOrMinus 1e-12)
        }
    }

    @Test
    fun `the first two links of a throw give a chain of two links`() {
        spacing shouldBe (0.39 plusOrMinus 1e-12)
        chainSpacing(1.0, 0.4) shouldBe (1.0 / 3 plusOrMinus 1e-12)
    }

    @Test
    fun `the full reel target is whole spacings, pays back every item, and is under one spacing too long`() {
        for (items in 1..64) {
            val length = throwLength(items)
            val target = fullReelTarget(length, spacing)
            isWholeSpacings(target) shouldBe true
            (target >= length - 1e-9) shouldBe true
            (target - length < spacing) shouldBe true
            woggakVineRefund(target) shouldBe items
        }
    }

    @Test
    fun `the reel rate fills one spacing of credit in one physics tick, even for a slow throw`() {
        for (speed in listOf(0.0, 2.0, 20.0)) {
            val rate = throwReelRate(speed, spacing)
            (rate >= speed) shouldBe true
            (minOf(spacing, rate / 60.0) >= spacing) shouldBe true
        }
    }

    @Test
    fun `a throw spacing pays out one node per physics tick faster than the tip flies, and is never over one item`() {
        for (speed in listOf(0.0, 4.0, 20.0, 40.0, 50.0, 200.0)) {
            val s = throwSpacing(speed)
            (s >= 0.39 - 1e-12) shouldBe true
            (s <= BLOCKS_PER_ITEM) shouldBe true
            if (speed * 1.1 / 60.0 <= BLOCKS_PER_ITEM) (s * 60.0 >= speed) shouldBe true
        }
        throwSpacing(40.0) shouldBe (40.0 * 1.1 / 60.0 plusOrMinus 1e-12)
    }

    @Test
    fun `a one-link throw seed gives a chain of exactly one link at the throw spacing`() {
        for (speed in listOf(0.0, 20.0, 40.0)) {
            val s = throwSpacing(speed)
            val seed = ReelSeed(Vector3d(1.0, 64.0, -3.0), Vector3d(0.6, 0.0, 0.8), s, 1)
            seed.path.size shouldBe 2
            seed.spacing shouldBe seed.length
            seed.length shouldBe (s plusOrMinus 1e-9)
            // VS gives each node mass per length * length / nodes; one node weighs one spacing of vine.
            (seed.massPerLength * seed.length / 2) shouldBe (MASS_PER_LENGTH * seed.spacing plusOrMinus 1e-9)
        }
    }

    @Test
    fun `the pay-out target is whole spacings, never over the hand distance or the thrown items`() {
        val s = throwSpacing(40.0)
        for (items in listOf(1, 3, 64)) {
            val maxLinks = maxThrowLinks(throwLength(items), s)
            (maxLinks * s <= throwLength(items) + 1e-9) shouldBe true
            for (distance in listOf(0.0, 0.1, s, 2.5 * s, 10.0, 100.0)) {
                val target = throwPayoutTarget(distance, s, maxLinks)
                abs(target / s - round(target / s)) shouldBe (0.0 plusOrMinus 1e-7)
                (target >= s - 1e-12) shouldBe true
                (target <= maxOf(distance, s) + 1e-9) shouldBe true
                (target <= throwLength(items) + 1e-9) shouldBe true
            }
        }
    }

    @Test
    fun `the hand is in front of the eye, to the side of its arm`() {
        val eye = Vec3(0.0, 64.0, 0.0)
        // Yaw 0 looks along +Z; the right side is then -X.
        val right = handPoint(eye, Vec3(0.0, 0.0, 1.0), 0f, 1.0)
        right.x shouldBe (-0.3 plusOrMinus 1e-9)
        right.y shouldBe (63.75 plusOrMinus 1e-9)
        right.z shouldBe (0.5 plusOrMinus 1e-9)
        handPoint(eye, Vec3(0.0, 0.0, 1.0), 0f, -1.0).x shouldBe (0.3 plusOrMinus 1e-9)
    }
}
