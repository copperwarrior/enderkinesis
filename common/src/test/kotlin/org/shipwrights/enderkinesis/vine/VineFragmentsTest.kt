package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.joml.Vector3d
import org.junit.jupiter.api.Test

class VineFragmentsTest {

    private fun straight(nodes: Int, spacing: Double) = DoubleArray(nodes * 3) { i -> if (i % 3 == 0) (i / 3) * spacing else 0.0 }

    private fun length(path: List<Vector3d>): Double = path.zipWithNext { a, b -> a.distance(b) }.sum()

    @Test
    fun `no broken link gives one run over every node`() {
        unbrokenRuns(10, emptySet()) shouldBe listOf(0..9)
    }

    @Test
    fun `each broken link splits, and no run bridges a gap`() {
        unbrokenRuns(10, setOf(2, 6)) shouldBe listOf(0..2, 3..6, 7..9)
    }

    @Test
    fun `neighbouring breaks leave no run between them`() {
        unbrokenRuns(10, setOf(3, 4)) shouldBe listOf(0..3, 5..9)
    }

    @Test
    fun `breaks at both ends drop the single loose nodes`() {
        unbrokenRuns(6, setOf(0, 4)) shouldBe listOf(1..4)
    }

    @Test
    fun `every link broken gives no run`() {
        unbrokenRuns(4, setOf(0, 1, 2)) shouldBe emptyList()
    }

    @Test
    fun `a stretched run comes back at its rest length`() {
        // Five links of rest 0.4 stretched to 1.0 each.
        val path = restPath(straight(6, 1.0), 0..5, 2.0, FragmentPivot.CENTROID)
        path shouldNotBe null
        length(path!!) shouldBe (2.0 plusOrMinus 1e-9)
        path.size shouldBe 6
    }

    @Test
    fun `a slack run comes back at its rest length`() {
        val path = restPath(straight(5, 0.1), 0..4, 1.6, FragmentPivot.CENTROID)!!
        length(path) shouldBe (1.6 plusOrMinus 1e-9)
    }

    @Test
    fun `the anchored end stays where it is`() {
        val positions = straight(6, 1.0)
        val first = restPath(positions, 0..5, 2.0, FragmentPivot.FIRST)!!
        first.first().distance(0.0, 0.0, 0.0) shouldBe (0.0 plusOrMinus 1e-12)
        val last = restPath(positions, 0..5, 2.0, FragmentPivot.LAST)!!
        last.last().distance(5.0, 0.0, 0.0) shouldBe (0.0 plusOrMinus 1e-12)
    }

    @Test
    fun `the deformed shape is kept`() {
        // An L shape: the corner stays a right angle after the scale.
        val positions = doubleArrayOf(0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 2.0, 2.0, 0.0)
        val path = restPath(positions, 0..2, 1.0, FragmentPivot.FIRST)!!
        val a = Vector3d(path[1]).sub(path[0])
        val b = Vector3d(path[2]).sub(path[1])
        a.dot(b) shouldBe (0.0 plusOrMinus 1e-12)
        length(path) shouldBe (1.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a sub-run uses only its own nodes`() {
        val path = restPath(straight(10, 1.0), 3..6, 1.2, FragmentPivot.CENTROID)!!
        path.size shouldBe 4
        length(path) shouldBe (1.2 plusOrMinus 1e-9)
    }

    @Test
    fun `a run with no length gives no path`() {
        restPath(DoubleArray(9), 0..2, 1.0, FragmentPivot.CENTROID) shouldBe null
    }
}
