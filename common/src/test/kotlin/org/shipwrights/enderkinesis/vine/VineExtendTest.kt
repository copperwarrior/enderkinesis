package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VineExtendTest {

    @Test
    fun `a pay-out never gives more than the item paid, and the rest stays as credit`() {
        val (links, credit) = extendLinks(0.0, 0.4)
        links shouldBe 2
        credit shouldBe (0.2 plusOrMinus 1e-9)
    }

    @Test
    fun `items paid in, length out and the refund agree over many pay-outs`() {
        for (spacing in listOf(0.4, 0.39, 0.37, 0.35, 0.3)) {
            var credit = 0.0
            var length = 0.0
            for (items in 1..40) {
                val (links, next) = extendLinks(credit, spacing)
                length += links * spacing
                credit = next
                (credit < BLOCKS_PER_ITEM) shouldBe true
                (length + credit) shouldBe (items.toDouble() plusOrMinus 1e-6)
                woggakVineRefund(length + credit) shouldBe items
            }
        }
    }

    @Test
    fun `a creative pay-out adds the whole links nearest one block`() {
        creativeExtendLinks(0.4) shouldBe 3
        creativeExtendLinks(0.35) shouldBe 3
        creativeExtendLinks(0.3) shouldBe 3
    }

    @Test
    fun `a node keeps its place along the vine when a block goes on`() {
        // 10 links of 0.4 plus one block: VS lays 5 blocks out as 13 links.
        extendedNode(0, 10, 4.0, 1.0, false) shouldBe 0
        extendedNode(10, 10, 4.0, 1.0, false) shouldBe 10
        extendedNode(0, 10, 4.0, 1.0, true) shouldBe 3
        extendedNode(10, 10, 4.0, 1.0, true) shouldBe 13
    }
}
