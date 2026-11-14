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
    fun `ten reeled spacings of 0 4 pay four items and leave no credit`() {
        val (items, credit) = reel(10, 0.4)
        items shouldBe 4
        credit shouldBe (0.0 plusOrMinus 1e-6)
    }

    @Test
    fun `a credit pays at the click that takes it to one block`() {
        reel(2, 0.4).first shouldBe 0
        reelItems(0.6, 0.4) shouldBe 1
        reelCreditLeft(0.3, 1.5) shouldBe (0.8 plusOrMinus 1e-9)
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
        validReelCredit(0.5) shouldBe 0.5
        validReelCredit(3.5) shouldBe 0.0
    }

    @Test
    fun `the reel floor keeps every link over one particle diameter`() {
        (MIN_REEL_SPACING > 2 * PARTICLE_RADIUS) shouldBe true
        reelFloor(40) shouldBe (40 * MIN_REEL_SPACING plusOrMinus 1e-9)
        atReelFloor(reelFloor(40), 40) shouldBe true
        atReelFloor(reelFloor(40) + 0.01, 40) shouldBe false
    }

    @Test
    fun `a vine at its reel floor gets fewer links when VS lays it out again`() {
        for (links in 3..200) (respacedLinks(reelFloor(links)) < links) shouldBe true
        // Two links at the floor lay out as two again, so the reel stops there.
        respacedLinks(reelFloor(2)) shouldBe 2
        respacedLinks(reelFloor(1)) shouldBe 1
        respacedLinks(1e-9) shouldBe 1
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
        items shouldBe 4
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
