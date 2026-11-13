package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VineCarryFlingTest {

    @Test
    fun `a lift with no link motion gives no fling`() {
        flingPart(0.0, 0.25) shouldBe 0.0
        flingPart(0.0, 0.375) shouldBe 0.0
    }

    @Test
    fun `the fling is the link motion, cut to the move the entity made`() {
        flingPart(0.05, 0.3) shouldBe 0.05
        flingPart(0.3, 0.05) shouldBe 0.05
        flingPart(-0.05, -0.3) shouldBe -0.05
        flingPart(-0.3, -0.05) shouldBe -0.05
    }

    @Test
    fun `a move against the link motion gives no fling`() {
        flingPart(0.1, -0.2) shouldBe 0.0
        flingPart(-0.1, 0.2) shouldBe 0.0
    }
}
