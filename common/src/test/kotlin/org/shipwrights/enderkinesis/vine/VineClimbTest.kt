package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VineClimbTest {

    private val eps = 1.0e-9

    /** A straight vertical vine: node i at y = -i, each link 1 block. */
    private val straight = doubleArrayOf(0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, -2.0, 0.0, 0.0, -3.0, 0.0)

    /** A vine bent sharply at node 2: up 1, up 1, then back down 1 (a peak), then down 2. */
    private val peak = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 2.0, 0.0, 0.1, 1.0, 0.0, 0.1, -1.0, 0.0)

    @Test
    fun `small steps move inside one link`() {
        climbAlong(straight, 4, 1.0, 1, 0.25) shouldBe (1.25 plusOrMinus eps)
        climbAlong(straight, 4, 1.0, -1, 0.25) shouldBe (0.75 plusOrMinus eps)
    }

    @Test
    fun `steps pass across nodes without a stop in both directions`() {
        climbAlong(straight, 4, 0.9, 1, 0.3) shouldBe (1.2 plusOrMinus eps)
        climbAlong(straight, 4, 1.1, -1, 0.3) shouldBe (0.8 plusOrMinus eps)
        climbAlong(straight, 4, 0.5, 1, 2.0) shouldBe (2.5 plusOrMinus eps)
    }

    @Test
    fun `steps stop at the end nodes and do not go past them`() {
        climbAlong(straight, 4, 2.8, 1, 5.0) shouldBe (3.0 plusOrMinus eps)
        climbAlong(straight, 4, 0.2, -1, 5.0) shouldBe (0.0 plusOrMinus eps)
        climbAlong(straight, 4, 3.0, 1, 0.2) shouldBe (3.0 plusOrMinus eps)
        climbAlong(straight, 4, 0.0, -1, 0.2) shouldBe (0.0 plusOrMinus eps)
    }

    @Test
    fun `steady steps move a steady distance across a sharp bend`() {
        var grip = 1.5
        // Node 2 is the peak; ten steps of 0.1 go 0.5 to it and 0.5 past it, down the far side.
        repeat(10) { grip = climbAlong(peak, 5, grip, 1, 0.1) }
        grip shouldBe (2.0 + 0.5 / linkLength(peak, 2) plusOrMinus eps)
        (gripCoordinate(peak, 5, grip, 1) < 2.0) shouldBe true
    }

    @Test
    fun `a zero length link is crossed without using distance`() {
        val doubled = doubleArrayOf(0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, -1.0, 0.0, 0.0, -2.0, 0.0)
        climbAlong(doubled, 4, 0.5, 1, 1.0) shouldBe (2.5 plusOrMinus eps)
    }

    @Test
    fun `a single node vine does not move`() {
        val one = doubleArrayOf(1.0, 2.0, 3.0)
        climbAlong(one, 1, 0.0, 1, 1.0) shouldBe 0.0
        gripCoordinate(one, 1, 0.0, 1) shouldBe 2.0
    }

    @Test
    fun `grip coordinates interpolate and the last node uses the last link`() {
        gripCoordinate(straight, 4, 1.25, 1) shouldBe (-1.25 plusOrMinus eps)
        gripLink(3.0, 4) shouldBe 2
        gripCoordinate(straight, 4, 3.0, 1) shouldBe (-3.0 plusOrMinus eps)
    }

    @Test
    fun `climb direction picks the node direction that goes up or down`() {
        climbDirection(straight, 4, 1.0, 0.0, 1.0, 0.0) shouldBe -1
        climbDirection(straight, 4, 1.0, 0.0, -1.0, 0.0) shouldBe 1
        climbDirection(straight, 4, 0.0, 0.0, 1.0, 0.0) shouldBe -1
        climbDirection(straight, 4, 3.0, 0.0, -1.0, 0.0) shouldBe 1
    }

    private fun linkLength(nodes: DoubleArray, link: Int): Double {
        val k = link * 3
        return Math.sqrt(
            (nodes[k + 3] - nodes[k]) * (nodes[k + 3] - nodes[k]) +
                (nodes[k + 4] - nodes[k + 1]) * (nodes[k + 4] - nodes[k + 1]) +
                (nodes[k + 5] - nodes[k + 2]) * (nodes[k + 5] - nodes[k + 2]),
        )
    }

    @Test
    fun `a climb into an end stands on that end, and a climb away does not`() {
        climbEndReached(0.0, 4, -1) shouldBe 0
        climbEndReached(3.0, 4, 1) shouldBe 1
        climbEndReached(0.0, 4, 1) shouldBe -1
        climbEndReached(3.0, 4, -1) shouldBe -1
        climbEndReached(1.5, 4, 1) shouldBe -1
    }

    @Test
    fun `a knot node counts as crossed in both directions, but not from on it`() {
        crossesNode(1.9, 2.1, 2) shouldBe true
        crossesNode(2.1, 1.9, 2) shouldBe true
        crossesNode(1.8, 2.0, 2) shouldBe true
        crossesNode(2.0, 2.2, 2) shouldBe false
        crossesNode(2.0, 1.8, 2) shouldBe false
        crossesNode(1.1, 1.5, 2) shouldBe false
    }

    @Test
    fun `the branch that goes more along the wish scores higher`() {
        val up = alongWish(0.0, 1.0, 0.0, 0.0, 1.0, 0.0)
        val side = alongWish(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val down = alongWish(0.0, -1.0, 0.0, 0.0, 1.0, 0.0)
        up shouldBe (1.0 plusOrMinus eps)
        side shouldBe (0.0 plusOrMinus eps)
        down shouldBe (-1.0 plusOrMinus eps)
        alongWish(0.0, 0.0, 0.0, 0.0, 1.0, 0.0) shouldBe -2.0
    }

    /** A level vine along +x: node i at x = i, each link 1 block. */
    private val level = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 2.0, 0.0, 0.0, 3.0, 0.0, 0.0)

    @Test
    fun `a wish along a level vine moves the grip at full share with nothing left to pump`() {
        val split = splitWish(level, 4, 1.5, 0, 1.0, 0.0, 0.0, WishSplit())
        split.dir shouldBe 1
        split.share shouldBe (1.0 plusOrMinus eps)
        split.restX shouldBe (0.0 plusOrMinus eps)
        split.restZ shouldBe (0.0 plusOrMinus eps)
        splitWish(level, 4, 1.5, 0, -1.0, 0.0, 0.0, WishSplit()).dir shouldBe -1
    }

    @Test
    fun `a slanted wish moves by its share and pumps with the part across the vine`() {
        val h = Math.sqrt(0.5)
        val split = splitWish(level, 4, 1.5, 0, h, 0.0, h, WishSplit())
        split.dir shouldBe 1
        split.share shouldBe (h plusOrMinus eps)
        split.restX shouldBe (0.0 plusOrMinus eps)
        split.restZ shouldBe (h plusOrMinus eps)
    }

    @Test
    fun `a wish across the vine or under the threshold only pumps`() {
        val across = splitWish(level, 4, 1.5, 0, 0.0, 0.0, 1.0, WishSplit())
        across.dir shouldBe 0
        across.restZ shouldBe (1.0 plusOrMinus eps)
        val angle = Math.acos(MOVE_MIN_COS) + 0.01
        val weak = splitWish(level, 4, 1.5, 0, Math.cos(angle), 0.0, Math.sin(angle), WishSplit())
        weak.dir shouldBe 0
        weak.restX shouldBe (Math.cos(angle) plusOrMinus eps)
        val strong = splitWish(level, 4, 1.5, 0, Math.cos(angle - 0.02), 0.0, Math.sin(angle - 0.02), WishSplit())
        strong.dir shouldBe 1
    }

    @Test
    fun `a level look pumps a vertical vine, and a look down or up climbs it`() {
        splitWish(straight, 4, 1.5, 0, 1.0, 0.0, 0.0, WishSplit()).dir shouldBe 0
        // Node direction +1 goes down the straight vine.
        val h = Math.sqrt(0.5)
        val down = splitWish(straight, 4, 1.5, 0, h, -h, 0.0, WishSplit())
        down.dir shouldBe 1
        down.restX shouldBe (h plusOrMinus eps)
        down.restY shouldBe (0.0 plusOrMinus eps)
        splitWish(straight, 4, 1.5, 0, 0.0, 1.0, 0.0, WishSplit()).dir shouldBe -1
    }

    @Test
    fun `a kept direction does not turn back when the vine turns against the wish`() {
        splitWish(level, 4, 1.5, 1, -1.0, 0.0, 0.0, WishSplit()).dir shouldBe 0
        splitWish(level, 4, 1.5, -1, -1.0, 0.0, 0.0, WishSplit()).dir shouldBe -1
    }

    @Test
    fun `a level vine is flat and a vertical vine is steep`() {
        isSteepAt(level, 4, 1.5) shouldBe false
        isSteepAt(straight, 4, 1.5) shouldBe true
        isSteepAt(doubleArrayOf(1.0, 2.0, 3.0), 1, 0.0) shouldBe true
    }

    @Test
    fun `a mantle needs a full body height of room over the vine top`() {
        val height = 1.8
        val feet = mantleFeetY(10.0)
        (feet > 10.0 + VINE_HALF_WIDTH) shouldBe true
        // A ceiling whose bottom is at [ceiling]: the box is free when its top is under it.
        fun roomUnder(ceiling: Double) = hasMantleRoom(0.0, 10.0, 0.0, 0.3, height) { _, minY, _, _, maxY, _ ->
            minY shouldBe (feet plusOrMinus eps)
            maxY <= ceiling
        }
        roomUnder(feet + height) shouldBe true
        roomUnder(feet + height - 0.01) shouldBe false
    }

    @Test
    fun `the mantle box is centred on the grip with the body width`() {
        hasMantleRoom(2.0, 0.0, -3.0, 0.3, 1.8) { minX, _, minZ, maxX, _, maxZ ->
            minX shouldBe (1.7 plusOrMinus eps)
            maxX shouldBe (2.3 plusOrMinus eps)
            minZ shouldBe (-3.3 plusOrMinus eps)
            maxZ shouldBe (-2.7 plusOrMinus eps)
            true
        } shouldBe true
    }
}
