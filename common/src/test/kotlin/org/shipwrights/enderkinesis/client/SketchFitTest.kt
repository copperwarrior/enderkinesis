package org.shipwrights.enderkinesis.client

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.joml.Matrix4f
import org.joml.Vector3f
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SketchFitTest {

    @Test
    fun `nothing drawn keeps the first view`() {
        val fit = SketchFit(2f, 100)
        fit.measured(null)
        fit.done shouldBe true
        fit.view.extent shouldBe 2f
    }

    @Test
    fun `a render that touches the edge widens the view`() {
        val fit = SketchFit(2f, 100)
        fit.measured(intArrayOf(0, 10, 50, 50))
        fit.done shouldBe false
        fit.view.extent shouldBe 6f
    }

    @Test
    fun `a render that fills most of the frame fits once`() {
        val fit = SketchFit(2f, 100)
        fit.measured(intArrayOf(10, 10, 89, 89))
        fit.done shouldBe true
        fit.view.centreX shouldBe (0f plusOrMinus 0.001f)
        fit.view.extent shouldBe ((1.58f + 0.02f) * 1.08f plusOrMinus 0.001f)
    }

    @Test
    fun `a small render is measured again, at most twice`() {
        val fit = SketchFit(2f, 100)
        repeat(2) {
            fit.measured(intArrayOf(45, 45, 54, 54))
            fit.done shouldBe false
        }
        fit.measured(intArrayOf(45, 45, 54, 54))
        fit.done shouldBe true
    }

    @Test
    fun `the cursor splits the parts into batches`() {
        val cursor = PassCursor(5, 2)
        val draws = mutableListOf<Pair<Int, Int>>()
        while (!cursor.done) {
            draws += cursor.from to cursor.to
            cursor.advance()
        }
        draws shouldBe listOf(0 to 2, 2 to 4, 4 to 5)
    }

    @Test
    fun `a scene with no parts still gives one draw`() {
        val cursor = PassCursor(0, 1)
        cursor.first shouldBe true
        cursor.done shouldBe false
        cursor.advance()
        cursor.done shouldBe true
    }

    @Test
    fun `a box view holds every corner of the turned box`() {
        val min = Vector3f(-3f, 0f, -5f)
        val max = Vector3f(7f, 4f, 2f)
        val view = boxView(min, max, 30f, 227f)
        val turn = Matrix4f().rotateX(Math.toRadians(30.0).toFloat()).rotateY(Math.toRadians(227.0).toFloat())
        for (i in 0 until 8) {
            val c = Vector3f(if (i and 1 == 0) min.x else max.x, if (i and 2 == 0) min.y else max.y, if (i and 4 == 0) min.z else max.z)
            turn.transformPosition(c)
            abs(c.x - view.centreX) shouldBeLessThan view.extent
            abs(c.y - view.centreY) shouldBeLessThan view.extent
        }
    }

    @Test
    fun `a fit from a first view starts there`() {
        val fit = SketchFit(SketchView(4f, -2f, 9f), 100)
        fit.view.centreX shouldBe 4f
        fit.view.centreY shouldBe -2f
        fit.view.extent shouldBe 9f
    }
}
