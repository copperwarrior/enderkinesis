package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WaterlineTest {

    @Test
    fun `a dry cell is never a waterline`() {
        waterlineScore(wetSides = 0, distanceSq = 0) shouldBe NOT_WATERLINE
        waterlineScore(wetSides = -1, distanceSq = 0) shouldBe NOT_WATERLINE
    }

    @Test
    fun `any wet cell beats a dry one, even far away`() {
        (waterlineScore(wetSides = 1, distanceSq = 6 * 6 * 3) > NOT_WATERLINE).shouldBeTrue()
    }

    @Test
    fun `at the same distance more wet sides score higher`() {
        for (sides in 1..3) (waterlineScore(sides + 1, 9) > waterlineScore(sides, 9)).shouldBeTrue()
    }

    @Test
    fun `at the same wetness the nearer cell scores higher`() {
        (waterlineScore(2, 1) > waterlineScore(2, 4)).shouldBeTrue()
    }

    @Test
    fun `one more wet side is worth a short walk but not a long one`() {
        (waterlineScore(2, 9) > waterlineScore(1, 0)).shouldBeTrue()
        (waterlineScore(2, 25) < waterlineScore(1, 0)).shouldBeTrue()
    }
}
