package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SwarmRulesTest {

    @Test
    fun `small swarms do not split`() {
        for (count in 0 until SWARM_SPLIT_MIN) swarmSplitSize(count) shouldBe 0
    }

    @Test
    fun `a split leaves two parts that add up and are at least half the split minimum`() {
        for (count in SWARM_SPLIT_MIN..SWARM_MAX_FLIES) {
            val leaving = swarmSplitSize(count)
            leaving shouldBeGreaterThanOrEqual SWARM_SPLIT_MIN / 2
            (count - leaving) shouldBeGreaterThanOrEqual SWARM_SPLIT_MIN / 2
        }
    }

    @Test
    fun `a merge never makes a swarm larger than the cap`() {
        swarmCanMerge(SWARM_MAX_FLIES / 2, SWARM_MAX_FLIES / 2).shouldBeTrue()
        swarmCanMerge(SWARM_MAX_FLIES / 2 + 1, SWARM_MAX_FLIES / 2).shouldBeFalse()
        swarmCanMerge(0, 5).shouldBeFalse()
    }

    @Test
    fun `a split swarm can merge back`() {
        for (count in SWARM_SPLIT_MIN..SWARM_MAX_FLIES) {
            val leaving = swarmSplitSize(count)
            swarmCanMerge(count - leaving, leaving).shouldBeTrue()
        }
    }

    @Test
    fun `exactly one of two swarms absorbs the other`() {
        val cases = listOf(Triple(5, 9, true), Triple(9, 5, false), Triple(7, 7, true))
        for ((count, otherCount, lowerIdFirst) in cases) {
            val a = swarmAbsorbs(count, 1, otherCount, 2)
            val b = swarmAbsorbs(otherCount, 2, count, 1)
            (a xor b).shouldBeTrue()
            if (count == otherCount) a shouldBe lowerIdFirst
        }
        swarmAbsorbs(9, 2, 5, 1).shouldBeTrue()
    }

    @Test
    fun `a swat splits a swarm into pieces of two to five that add up`() {
        for (count in SWARM_DISPERSE_MIN..SWARM_MAX_FLIES) {
            for (wanted in listOf(0, 2, 3, 5, 9)) {
                val pieces = swarmDispersePieces(count) { wanted }
                pieces.sum() shouldBe count
                for (piece in pieces) piece shouldBeInRange SWARM_DISPERSE_MIN..SWARM_DISPERSE_MAX
            }
        }
    }

    @Test
    fun `a swat on a tiny or empty swarm splits nothing`() {
        swarmDispersePieces(1) { 3 } shouldBe listOf(1)
        swarmDispersePieces(0) { 3 } shouldBe emptyList()
    }

    @Test
    fun `the cloud grows with the count and shrinks to a cover block at rest`() {
        var last = 0f
        for (count in 1..SWARM_MAX_FLIES) {
            val radius = swarmRadius(count, resting = false)
            (radius > last).shouldBeTrue()
            last = radius
        }
        (swarmRadius(SWARM_MAX_FLIES, resting = false) < 1.5f).shouldBeTrue()
        (swarmRadius(SWARM_MAX_FLIES, resting = true) <= 0.5f).shouldBeTrue()
    }
}
