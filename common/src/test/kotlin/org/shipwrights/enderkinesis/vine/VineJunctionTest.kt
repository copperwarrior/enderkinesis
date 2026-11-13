package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VineJunctionTest {

    private val held = 1L
    private val other = 2L

    /** Level along +x: node i at x = i. */
    private val level = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 2.0, 0.0, 0.0, 3.0, 0.0, 0.0, 4.0, 0.0, 0.0)

    /** Hangs down from its start node at (2, 0, 0): node i at y = -i. */
    private val hangingFromStart = doubleArrayOf(2.0, 0.0, 0.0, 2.0, -1.0, 0.0, 2.0, -2.0, 0.0)

    /** Rises to its last node at (2, 0, 0): node i at y = i - 2. */
    private val risingToEnd = doubleArrayOf(2.0, -2.0, 0.0, 2.0, -1.0, 0.0, 2.0, 0.0, 0.0)

    /** The knot at node 2 of the level vine, where the start of [hangingFromStart] is joined. */
    private fun branchAtInterior() = JunctionWays().apply {
        addVineWays(held, level, 5, 2)
        addVineWays(other, hangingFromStart, 3, 0)
    }

    /** The knot at the last node of [risingToEnd] (held), joined to node 2 of the level vine. */
    private fun heldEndOnInterior() = JunctionWays().apply {
        addVineWays(held, risingToEnd, 3, 2)
        addVineWays(other, level, 5, 2)
    }

    @Test
    fun `a branch end on an interior node adds one way, and the held vine adds both`() {
        val ways = branchAtInterior()
        ways.size shouldBe 3
        ways.chain[2] shouldBe other
        ways.dir[2] shouldBe 1
    }

    @Test
    fun `a wish down at an interior knot takes the joined vine that hangs down`() {
        val ways = branchAtInterior()
        val way = ways.best(0.3, -1.0, 0.0, MOVE_MIN_COS, held, 1)
        ways.chain[way] shouldBe other
        ways.node[way] shouldBe 0
        ways.dir[way] shouldBe 1
    }

    @Test
    fun `a wish along the held vine crosses the knot in both directions`() {
        val ways = branchAtInterior()
        val forward = ways.best(1.0, 0.0, 0.0, MOVE_MIN_COS, held, 1)
        ways.chain[forward] shouldBe held
        ways.dir[forward] shouldBe 1
        val back = ways.best(-1.0, 0.0, 0.0, MOVE_MIN_COS, held, -1)
        ways.chain[back] shouldBe held
        ways.dir[back] shouldBe -1
    }

    @Test
    fun `from the joined vine a wish back up takes the held vine both ways`() {
        val ways = JunctionWays().apply {
            addVineWays(other, hangingFromStart, 3, 0)
            addVineWays(held, level, 5, 2)
        }
        val right = ways.best(1.0, 0.5, 0.0, MOVE_MIN_COS, other, -1)
        ways.chain[right] shouldBe held
        ways.dir[right] shouldBe 1
        val left = ways.best(-1.0, 0.5, 0.0, MOVE_MIN_COS, other, -1)
        ways.chain[left] shouldBe held
        ways.dir[left] shouldBe -1
    }

    @Test
    fun `a held end on an interior node goes on along the other vine either way, or back down`() {
        val ways = heldEndOnInterior()
        ways.size shouldBe 3
        val east = ways.best(1.0, 0.2, 0.0, MOVE_MIN_COS, held, 1)
        ways.chain[east] shouldBe other
        ways.dir[east] shouldBe 1
        val west = ways.best(-1.0, 0.2, 0.0, MOVE_MIN_COS, held, 1)
        ways.chain[west] shouldBe other
        ways.dir[west] shouldBe -1
        val down = ways.best(0.0, -1.0, 0.0, MOVE_MIN_COS, held, 1)
        ways.chain[down] shouldBe held
        ways.dir[down] shouldBe -1
    }

    @Test
    fun `a climb up into a held end takes a way of the joined vine`() {
        val ways = heldEndOnInterior()
        val way = ways.best(0.25, 1.0, 0.0, -1.0, held, 1)
        ways.chain[way] shouldBe other
        ways.dir[way] shouldBe 1
    }

    @Test
    fun `the current way wins a near tie, and a wish across every way takes none`() {
        val ways = JunctionWays().apply {
            add(held, 2, 1, 1.0, 0.0, 0.0)
            add(other, 0, 1, 1.0, 0.0, 0.02)
        }
        ways.chain[ways.best(1.0, 0.0, 0.01, MOVE_MIN_COS, held, 1)] shouldBe held
        ways.chain[ways.best(1.0, 0.0, 0.01, MOVE_MIN_COS, other, 1)] shouldBe other
        ways.best(0.0, 1.0, 0.0, MOVE_MIN_COS, held, 1) shouldBe -1
    }

    @Test
    fun `a move finds the first knot it reaches, not the one it starts on`() {
        val knots = setOf(2, 3)
        firstNodeCrossed(1.5, 2.0) { it in knots } shouldBe 2
        firstNodeCrossed(1.5, 3.5) { it in knots } shouldBe 2
        firstNodeCrossed(2.0, 2.4) { it in knots } shouldBe -1
        firstNodeCrossed(3.6, 2.9) { it in knots } shouldBe 3
        firstNodeCrossed(3.0, 2.6) { it in knots } shouldBe -1
        firstNodeCrossed(2.0, 2.0) { it in knots } shouldBe -1
    }

    @Test
    fun `a grip sits on a node only at a whole node`() {
        nodeAt(2.0) shouldBe 2
        nodeAt(0.0) shouldBe 0
        nodeAt(2.4) shouldBe -1
    }
}
