package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max

class WohlonWindTest {
    private val seed = 12345L
    private val g = 9.81

    @Test
    fun `the wind stays in bounds and blows both ways`() {
        var east = 0; var west = 0
        for (step in 0 until 200_000) {
            val t = step * 0.37
            val w = wohlonWindSpeed(seed, t, step % 500 - 250.0, step % 333 - 160.0)
            abs(w) shouldBeLessThanOrEqual WIND_MAX
            if (w > 0.0) east++ else if (w < 0.0) west++
        }
        east shouldBeGreaterThan west
        west shouldBeGreaterThan 0
    }

    @Test
    fun `the wind changes smoothly from one tick to the next`() {
        var worst = 0.0
        var last = wohlonWindSpeed(seed, 0.0, 10.0, -4.0)
        for (tick in 1..72_000) {
            val w = wohlonWindSpeed(seed, tick * 0.05, 10.0, -4.0)
            worst = max(worst, abs(w - last))
            last = w
        }
        // Under 0.15 m/s a tick, so no gust jerks a vine.
        worst shouldBeLessThan 0.15
    }

    @Test
    fun `near vines feel almost the same wind, far vines a different one`() {
        var near = 0.0
        var far = 0.0
        for (step in 0 until 1000) {
            val t = step * 1.3
            near = max(near, abs(wohlonWindSpeed(seed, t, 0.0, 0.0) - wohlonWindSpeed(seed, t, 1.0, 1.0)))
            far = max(far, abs(wohlonWindSpeed(seed, t, 0.0, 0.0) - wohlonWindSpeed(seed, t, 60.0, 60.0)))
        }
        near shouldBeLessThan 0.8
        far shouldBeGreaterThan near
    }

    @Test
    fun `the same seed and time give the same wind`() {
        wohlonWindSpeed(seed, 1234.5, 7.0, 8.0) shouldBe wohlonWindSpeed(seed, 1234.5, 7.0, 8.0)
        wohlonWindSpeed(seed, 1234.5, 7.0, 8.0) shouldNotBe wohlonWindSpeed(seed + 99, 1234.5, 7.0, 8.0)
    }

    @Test
    fun `drag pushes only toward the wind and never past the wind speed`() {
        windDragAccel(4.0, 0.0, 1.0) shouldBe (WIND_DRAG_RATE * 4.0 plusOrMinus 1e-12)
        windDragAccel(4.0, 4.0, 1.0) shouldBe 0.0
        windDragAccel(4.0, 9.0, 1.0) shouldBe 0.0
        windDragAccel(4.0, -20.0, 1.0) shouldBe (WIND_DRAG_RATE * 4.0 plusOrMinus 1e-12)
        windDragAccel(-3.0, 0.0, 1.0) shouldBe (-WIND_DRAG_RATE * 3.0 plusOrMinus 1e-12)
        windDragAccel(-3.0, 5.0, 0.5) shouldBe (-WIND_DRAG_RATE * 1.5 plusOrMinus 1e-12)
        windDragAccel(0.0, 2.0, 1.0) shouldBe 0.0
    }

    @Test
    fun `a hanging vine leans no more than 20 degrees in the strongest wind`() {
        // Uniform side load on a uniform hanging line: every link leans atan(side / weight).
        val lean = Math.toDegrees(atan(windDragAccel(WIND_MAX, 0.0, 1.0) / g))
        lean shouldBeLessThan 20.0
        lean shouldBeGreaterThan 10.0
    }

    @Test
    fun `value noise stays in -1 to 1`() {
        for (i in 0 until 50_000) {
            val n = valueNoise(seed, i * 0.173 - 400.0, i * 0.091 - 200.0)
            abs(n) shouldBeLessThanOrEqual 1.0
        }
    }
}
