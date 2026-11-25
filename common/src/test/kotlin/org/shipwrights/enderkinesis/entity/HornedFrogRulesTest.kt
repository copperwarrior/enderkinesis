package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HornedFrogRulesTest {

    @Test
    fun `a frog is fed for five minutes after a meal`() {
        isRecentlyFed(NEVER_FED, 10_000).shouldBeFalse()
        isRecentlyFed(1_000, 1_000).shouldBeTrue()
        isRecentlyFed(1_000, 1_000 + FED_WINDOW_TICKS).shouldBeTrue()
        isRecentlyFed(1_000, 1_001 + FED_WINDOW_TICKS).shouldBeFalse()
        isRecentlyFed(2_000, 1_000).shouldBeFalse()
    }

    @Test
    fun `breeding needs the frog and two partners`() {
        frogBreedingTrio("a", emptyList()) { 0 }.shouldBeNull()
        frogBreedingTrio("a", listOf("b")) { 0 }.shouldBeNull()
    }

    @Test
    fun `the trio is the frog and its two nearest partners, and exactly one of them is the mother`() {
        for (pick in 0 until FROG_GROUP_SIZE) {
            val trio = frogBreedingTrio("a", listOf("b", "c", "d")) { pick }.shouldNotBeNull()
            trio.members shouldBe listOf("a", "b", "c")
            trio.members.count { it == trio.mother } shouldBe 1
            trio.mother shouldBe trio.members[pick]
        }
    }

    @Test
    fun `the tongue reaches a swarm hovering above water from the bank`() {
        // Swarm centre 2.2 blocks above the mouth, 2 blocks out, with a 0.9-block cloud.
        tongueCanReach(horizontal = 2.0, rise = 2.2, reach = 2.5 + 0.9).shouldBeTrue()
    }

    @Test
    fun `the tongue misses beyond its reach, too far below, or too steep`() {
        tongueCanReach(horizontal = 3.0, rise = 0.0, reach = 2.5).shouldBeFalse()
        tongueCanReach(horizontal = 1.0, rise = -1.0, reach = 2.5).shouldBeFalse()
        tongueCanReach(horizontal = 0.6, rise = 2.0, reach = 2.5).shouldBeFalse()
    }

    @Test
    fun `prey right overhead counts as a little way out`() {
        tongueCanReach(horizontal = 0.0, rise = 0.8, reach = 2.5).shouldBeTrue()
    }

    @Test
    fun `a planned leap comes down through the landing height at the planned distance`() {
        for (fromWater in listOf(false, true)) {
            for (distance in listOf(1.5, 3.0, 4.5, 6.0)) {
                for (rise in listOf(-1.0, 0.0, 0.5, 1.0)) {
                    val plan = planLeap(distance, rise, fromWater).shouldNotBeNull()
                    (plan.speed <= LEAP_MAX_SPEED).shouldBeTrue()
                    val path = leapPath(plan.speed, plan.lift, fromWater, plan.ticks)
                    val last = plan.ticks - 1
                    path[last * 2] shouldBe (distance plusOrMinus 1e-9)
                    (path[last * 2 + 1] <= rise).shouldBeTrue()
                    if (last > 0) (path[last * 2 - 1] > rise || path[last * 2 - 1] > path[last * 2 + 1]).shouldBeTrue()
                }
            }
        }
    }

    @Test
    fun `a leap prefers the lowest arc that fits`() {
        val short = planLeap(2.0, 0.0, fromWater = false).shouldNotBeNull()
        val long = planLeap(6.0, 0.0, fromWater = false).shouldNotBeNull()
        (short.lift <= long.lift).shouldBeTrue()
    }

    @Test
    fun `no leap reaches a ledge far above the frog`() {
        planLeap(2.0, 6.0, fromWater = false).shouldBeNull()
    }

    @Test
    fun `the path follows vanilla air drag`() {
        val path = leapPath(1.0, 0.5, fromWater = false, ticks = 2)
        path[0] shouldBe (1.0 plusOrMinus 1e-12)
        path[1] shouldBe (0.5 plusOrMinus 1e-12)
        path[2] shouldBe (1.91 plusOrMinus 1e-12)
        path[3] shouldBe (0.5 + (0.5 - 0.08) * 0.98 plusOrMinus 1e-12)
    }
}
