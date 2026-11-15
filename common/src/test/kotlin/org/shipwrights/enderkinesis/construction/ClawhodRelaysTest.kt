package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ClawhodRelaysTest {

    // Nodes on a line, 6 blocks apart: with a reach of 8 each joins only its neighbours.
    private val xs = doubleArrayOf(0.0, 6.0, 12.0, 18.0)
    private val ys = DoubleArray(4)
    private val zs = DoubleArray(4)

    private fun path(requester: (Int) -> Boolean, supply: (Int) -> Boolean, count: Int = 4) =
        relayPath(xs, ys, zs, count, requester, supply, 8.0, IntArray(4), IntArray(4))?.toList()

    @Test
    fun `the chain runs from the supply side to the requester side`() {
        path({ it == 0 }, { it == 3 }) shouldBe listOf(3, 2, 1, 0)
    }

    @Test
    fun `one node that reaches both is a chain of one`() {
        path({ it == 1 }, { it == 1 }) shouldBe listOf(1)
    }

    @Test
    fun `a gap breaks the chain`() {
        path({ it == 0 }, { it == 3 }, count = 2) shouldBe null
    }

    @Test
    fun `the shortest chain wins`() {
        path({ it == 0 || it == 2 }, { it == 3 }) shouldBe listOf(3, 2)
    }
}
