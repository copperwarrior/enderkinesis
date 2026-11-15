package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class StallReasonsTest {

    private fun counts(vararg pairs: Pair<WhyNot, Int>): IntArray {
        val c = IntArray(WhyNot.entries.size)
        for ((why, n) in pairs) c[why.ordinal] = n
        return c
    }

    @Test
    fun `the mask changes with the set of reasons, not with their counts`() {
        whyMask(counts(WhyNot.NO_SUPPORT to 3)) shouldBe whyMask(counts(WhyNot.NO_SUPPORT to 40))
        whyMask(counts(WhyNot.NO_SUPPORT to 3)) shouldNotBe whyMask(counts(WhyNot.NO_SUPPORT to 3, WhyNot.OUT_OF_REACH to 1))
    }

    @Test
    fun `a stall with no counted reason is still not the no-stall mask`() {
        whyMask(counts()) shouldNotBe 0
    }

    @Test
    fun `the summary lists the largest reason first`() {
        whySummary(counts(WhyNot.NO_SUPPORT to 2, WhyNot.OUT_OF_REACH to 9)) shouldBe "OUT_OF_REACH=9, NO_SUPPORT=2"
        whySummary(counts()) shouldBe "none"
    }
}
