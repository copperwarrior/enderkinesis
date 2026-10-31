package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ConstriliarGaitClockTest {

    private fun opensAt(gait: Float): List<Int> = (0..3).filter { ConstriliarGaitClock.inStartWindow(it, gait) }

    @Test
    fun `the crawl never opens two legs at once`() {
        for (step in 0 until 200) {
            opensAt(step / 200f).size shouldBeLessThanOrEqual 1
        }
    }

    @Test
    fun `the crawl opens every leg once a cycle`() {
        val seen = mutableSetOf<Int>()
        for (step in 0 until 200) seen += opensAt(step / 200f)
        seen shouldBe setOf(0, 1, 2, 3)
    }

    @Test
    fun `the trot opens diagonal pairs together`() {
        for (step in 0 until 200) {
            val open = opensAt(1f + step / 200f)
            if (open.isNotEmpty()) (open == listOf(0, 2) || open == listOf(1, 3)) shouldBe true
        }
    }

    @Test
    fun `the clock stops at idle and when not carrying`() {
        val clock = ConstriliarGaitClock()
        clock.tick(0.0, true)
        clock.encoded() shouldBe ConstriliarGaitClock.STOPPED
        clock.tick(0.16, false)
        clock.encoded() shouldBe ConstriliarGaitClock.STOPPED
    }

    @Test
    fun `fast walking trots and slow walking crawls`() {
        val clock = ConstriliarGaitClock()
        clock.tick(0.16, true)
        (clock.encoded() >= 1f) shouldBe true
        repeat(3) { clock.tick(0.03, true) }
        (clock.encoded() in 0f..0.9999f) shouldBe true
    }

    @Test
    fun `a trot leg may lift with its diagonal partner but not across pairs`() {
        ConstriliarGaitClock.supportAllows(0, 1.0f) { it == 2 } shouldBe true
        ConstriliarGaitClock.supportAllows(0, 1.0f) { it == 1 } shouldBe false
    }

    @Test
    fun `a crawl leg waits for any other swinging leg`() {
        ConstriliarGaitClock.supportAllows(0, 0.5f) { false } shouldBe true
        ConstriliarGaitClock.supportAllows(0, 0.5f) { it == 2 } shouldBe false
    }

    @Test
    fun `the next crawl lifter follows the window order`() {
        ConstriliarGaitClock.nextCrawlLifter(0.0f) shouldBe 2
        ConstriliarGaitClock.nextCrawlLifter(0.2f) shouldBe 1
        ConstriliarGaitClock.nextCrawlLifter(1.2f) shouldBe -1
    }
}
