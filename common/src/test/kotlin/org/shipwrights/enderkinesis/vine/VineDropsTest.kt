package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VineDropsTest {

    @Test
    fun `a vine shorter than one block gives nothing`() {
        woggakVineRefund(0.4) shouldBe 0
        woggakVineRefund(0.99) shouldBe 0
    }

    @Test
    fun `each full block gives one item, rounded down`() {
        woggakVineRefund(1.0) shouldBe 1
        woggakVineRefund(1.9) shouldBe 1
        woggakVineRefund(2.0) shouldBe 2
        woggakVineRefund(17.5) shouldBe 17
    }

    @Test
    fun `a bad length gives nothing`() {
        woggakVineRefund(0.0) shouldBe 0
        woggakVineRefund(-5.0) shouldBe 0
        woggakVineRefund(Double.NaN) shouldBe 0
        woggakVineRefund(Double.POSITIVE_INFINITY) shouldBe 0
    }
}
