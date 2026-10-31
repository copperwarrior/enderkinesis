package org.shipwrights.enderkinesis.physics

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FootForceSolverTest {

    private val corners = arrayOf(
        doubleArrayOf(-0.45, -0.3, 0.45), doubleArrayOf(0.45, -0.3, 0.45),
        doubleArrayOf(0.45, -0.3, -0.45), doubleArrayOf(-0.45, -0.3, -0.45),
    )

    private fun solver(vararg stance: Int): FootForceSolver {
        val s = FootForceSolver()
        for (i in 0 until FootForceSolver.FEET) {
            val c = corners[i]
            s.setFoot(i, c[0], c[1], c[2], if (i in stance) 1.0 else 0.0)
        }
        return s
    }

    /** Net force and torque about the COM that the feet make. */
    private fun wrench(s: FootForceSolver): DoubleArray {
        val w = DoubleArray(6)
        for (i in 0 until FootForceSolver.FEET) {
            val r = corners[i]
            val fx = s.force[i * 3]; val fy = s.force[i * 3 + 1]; val fz = s.force[i * 3 + 2]
            w[0] += fx; w[1] += fy; w[2] += fz
            w[3] += r[1] * fz - r[2] * fy
            w[4] += r[2] * fx - r[0] * fz
            w[5] += r[0] * fy - r[1] * fx
        }
        return w
    }

    @Test
    fun `four feet share the weight evenly`() {
        val s = solver(0, 1, 2, 3)
        s.solve(0.0, 4000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, true, 1.0)
        for (i in 0 until 4) s.force[i * 3 + 1] shouldBe (1000.0 plusOrMinus 20.0)
    }

    @Test
    fun `four feet make a requested pitch torque`() {
        val s = solver(0, 1, 2, 3)
        s.solve(0.0, 4000.0, 0.0, 500.0, 0.0, 0.0, 0.0, 1.0, 0.0, true, 1.0)
        val w = wrench(s)
        w[1] shouldBe (4000.0 plusOrMinus 40.0)
        w[3] shouldBe (500.0 plusOrMinus 25.0)
    }

    @Test
    fun `feet never pull on flat ground`() {
        val s = solver(0, 1, 2, 3)
        s.solve(0.0, 500.0, 0.0, 3000.0, 0.0, 0.0, 0.0, 1.0, 0.0, true, 1.0)
        for (i in 0 until 4) s.force[i * 3 + 1] shouldBeGreaterThanOrEqual 0.0
    }

    @Test
    fun `sideways force stays inside the friction cone`() {
        val s = solver(0, 1, 2, 3)
        s.solve(4000.0, 2000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, true, 0.8)
        for (i in 0 until 4) {
            val fn = s.force[i * 3 + 1]
            Math.hypot(s.force[i * 3], s.force[i * 3 + 2]) shouldBeLessThanOrEqual 0.8 * fn + 1e-6
        }
    }

    @Test
    fun `a diagonal pair carries the weight without a huge opposed force`() {
        val s = solver(0, 2)
        s.solve(0.0, 4000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, true, 1.0)
        s.force[1] shouldBe (2000.0 plusOrMinus 50.0)
        s.force[7] shouldBe (2000.0 plusOrMinus 50.0)
        s.force[4] shouldBe 0.0
    }
}
