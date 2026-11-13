package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.valkyrienskies.core.api.chains.ChainEnd

class VineShortenTest {

    /** Items paid when [links] links of [spacing] are reeled in one by one, and the credit left. */
    private fun reel(links: Int, spacing: Double, startCredit: Double = 0.0): Pair<Int, Double> {
        var credit = startCredit
        var items = 0
        repeat(links) {
            items += reelItems(credit, spacing)
            credit = reelCreditLeft(credit, spacing)
        }
        return items to credit
    }

    @Test
    fun `ten reeled spacings of 0 4 pay one item and leave no credit`() {
        val (items, credit) = reel(10, 0.4)
        items shouldBe 1
        credit shouldBe (0.0 plusOrMinus 1e-6)
    }

    @Test
    fun `a credit pays at the click that takes it to four blocks`() {
        reel(9, 0.4).first shouldBe 0
        reelItems(3.6, 0.4) shouldBe 1
        reelCreditLeft(3.0, 1.5) shouldBe (0.5 plusOrMinus 1e-9)
    }

    @Test
    fun `any mix of reels and a fade pays the floor of the placed length, never more`() {
        for (links in 2..60) {
            val spacing = 0.4
            val length = links * spacing
            val placedFor = woggakVineCost(length)
            for (reeled in 0 until links) {
                val (items, credit) = reel(reeled, spacing)
                val remaining = (links - reeled) * spacing
                val total = items + woggakVineRefund(remaining + credit)
                total shouldBe woggakVineRefund(length)
                (total <= placedFor) shouldBe true
            }
        }
    }

    @Test
    fun `a cut after reels pays no more than the floor, as the credit stays with one piece`() {
        val spacing = 0.4
        val links = 37
        val (items, credit) = reel(7, spacing)
        val left = (links - 7) * spacing
        // Two pieces of the rest; the credit goes with the first.
        val a = 11 * spacing
        val b = left - a
        val total = items + woggakVineRefund(a + credit) + woggakVineRefund(b)
        (total <= woggakVineRefund(links * spacing)) shouldBe true
    }

    @Test
    fun `a bad saved credit counts as nothing, and a credit never reaches a full item`() {
        validReelCredit(Double.NaN) shouldBe 0.0
        validReelCredit(-1.0) shouldBe 0.0
        validReelCredit(Double.POSITIVE_INFINITY) shouldBe 0.0
        (validReelCredit(9.0) < BLOCKS_PER_ITEM) shouldBe true
        woggakVineRefund(validReelCredit(9.0)) shouldBe 0
        validReelCredit(BLOCKS_PER_ITEM - 1e-10) shouldBe 0.0
        validReelCredit(3.5) shouldBe 3.5
    }

    @Test
    fun `a vine is not shortened under one link`() {
        shortenedLinks(1) shouldBe -1L
        shortenedLinks(2) shouldBe 1L
        shortenedLinks(25) shouldBe 24L
    }

    /** Game ticks at which a free-end reel held for [ticks] ticks rebuilds, from the press at tick 0. */
    private fun rebuildTicks(spacing: Double, ticks: Long): List<Long> {
        var pull = Double.MAX_VALUE
        var last = Long.MIN_VALUE / 2
        val out = ArrayList<Long>()
        for (t in 0 until ticks) {
            pull = nextReelPull(pull, spacing)
            if (!rebuildDue(pull, spacing, t - last)) continue
            out.add(t)
            pull = 0.0
            last = t
        }
        return out
    }

    @Test
    fun `a tap at a free end takes in one link at once`() {
        rebuildTicks(0.4, 1) shouldBe listOf(0L)
        rebuildTicks(0.4, REBUILD_TICKS) shouldBe listOf(0L)
    }

    @Test
    fun `a held free-end reel never rebuilds faster than every few ticks`() {
        val ticks = rebuildTicks(0.4, 40)
        ticks shouldBe (0L until 40L step REBUILD_TICKS).toList()
        for (i in 1 until ticks.size) (ticks[i] - ticks[i - 1] >= REBUILD_TICKS) shouldBe true
    }

    @Test
    fun `a held free-end reel with long links keeps the reel speed`() {
        val spacing = 1.0
        val ticks = rebuildTicks(spacing, 200)
        // Ten seconds of reel; the press link comes on top of the steady rate.
        ((ticks.size - 1) * spacing) shouldBe (REEL_SPEED * 10 plusOrMinus spacing)
    }

    @Test
    fun `the reel target is one whole link under the rest length, and none at one link`() {
        reelTarget(4.0, 0.4) shouldBe (3.6 plusOrMinus 1e-9)
        reelTarget(3.99999999, 0.4) shouldBe (3.6 plusOrMinus 1e-9)
        reelTarget(0.8, 0.4) shouldBe (0.4 plusOrMinus 1e-9)
        reelTarget(0.4, 0.4) shouldBe -1.0
    }

    @Test
    fun `an attached reel pays only the length that came in, once, with stale and longer snapshots`() {
        // Rest lengths that the server sees on each tick: stale repeats, and one link that the vine gets back.
        val snapshots = doubleArrayOf(8.0, 8.0, 7.6, 7.6, 7.6, 7.2, 7.6, 7.2, 6.8, 6.8, 6.4, 6.0, 5.6, 5.2, 4.8, 4.4, 4.0)
        var paidRest = snapshots[0]
        var credit = 0.0
        var items = 0
        var paidLength = 0.0
        for (rest in snapshots) {
            val came = reelArrival(paidRest, rest)
            (came >= 0.0) shouldBe true
            paidLength += came
            items += reelItems(credit, came)
            credit = reelCreditLeft(credit, came)
            paidRest = paidRestAfter(paidRest, rest)
        }
        // The length got back at 7.2 -> 7.6 is not paid again when it comes in.
        paidLength shouldBe (8.0 - 4.0 plusOrMinus 1e-9)
        items shouldBe 1
        reelArrival(5.0, 5.0) shouldBe 0.0
        reelArrival(5.0, 6.0) shouldBe 0.0
        reelArrival(Double.NaN, 1.0) shouldBe 0.0
        reelArrival(5.0, Double.NaN) shouldBe 0.0
        paidRestAfter(5.0, 6.0) shouldBe 5.0
        paidRestAfter(5.0, Double.NaN) shouldBe 5.0
        paidRestAfter(Double.NaN, 4.0) shouldBe 4.0
    }

    @Test
    fun `the shortened end is the one nearer the clicked node`() {
        nearestEnd(0, 10) shouldBe ChainEnd.START
        nearestEnd(5, 10) shouldBe ChainEnd.START
        nearestEnd(6, 10) shouldBe ChainEnd.END
        nearestEnd(10, 10) shouldBe ChainEnd.END
    }
}
