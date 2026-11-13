package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.round

class VineThrowTest {

    private val spacing = chainSpacing(0.39 + 0.39, VINE_TARGET_SPACING)

    private fun isWholeSpacings(length: Double) = abs(length / spacing - round(length / spacing)) < 1e-7

    @Test
    fun `a throw is four blocks of vine for each item`() {
        throwLength(1) shouldBe 4.0
        throwLength(64) shouldBe 256.0
    }

    @Test
    fun `the tip speed follows the bow power and stops at a full draw`() {
        throwSpeed(1f) shouldBe (20.0 plusOrMinus 1e-9)
        throwSpeed(0.5f) shouldBe (10.0 plusOrMinus 1e-6)
        throwSpeed(2f) shouldBe (20.0 plusOrMinus 1e-9)
        throwSpeed(-1f) shouldBe 0.0
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
            unpaidItems(items, target) shouldBe 0
        }
    }

    @Test
    fun `the payout target keeps one spacing ahead of the tip, never drops and never passes the full length`() {
        val full = fullReelTarget(throwLength(2), spacing)
        var current = 2 * spacing
        var distance = 0.0
        while (distance < 12.0) {
            val next = payoutTarget(distance, spacing, current, full)
            isWholeSpacings(next) shouldBe true
            (next >= current) shouldBe true
            (next <= full + 1e-12) shouldBe true
            if (next < full) (next >= distance + spacing - 1e-9) shouldBe true
            current = next
            distance += 0.37
        }
        current shouldBe full
        payoutTarget(0.0, spacing, current, full) shouldBe full
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
    fun `a throw that stops short gives back the items its paid length does not refund`() {
        unpaidItems(5, 0.0) shouldBe 5
        unpaidItems(5, 8.0) shouldBe 3
        unpaidItems(5, 7.9) shouldBe 4
        unpaidItems(5, 40.0) shouldBe 0
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
