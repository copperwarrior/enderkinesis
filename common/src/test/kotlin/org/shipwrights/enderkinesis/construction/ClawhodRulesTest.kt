package org.shipwrights.enderkinesis.construction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ClawhodRulesTest {

    private val ballast = Any()
    private val stone = Any()

    @Test
    fun `a refused item waits while nothing changes`() {
        val r = FetchRefusal()
        r.refuse(ballast, epoch = 3, held = null, count = 0)
        r.refuses(ballast, epoch = 3, held = null, count = 0) shouldBe true
        r.refuses(stone, epoch = 3, held = null, count = 0) shouldBe false
    }

    @Test
    fun `a supply change ends the wait`() {
        val r = FetchRefusal()
        r.refuse(ballast, epoch = 3, held = null, count = 0)
        r.refuses(ballast, epoch = 4, held = null, count = 0) shouldBe false
    }

    @Test
    fun `a hand change ends the wait`() {
        val r = FetchRefusal()
        r.refuse(ballast, epoch = 3, held = stone, count = 5)
        r.refuses(ballast, epoch = 3, held = stone, count = 4) shouldBe false
        r.refuses(ballast, epoch = 3, held = null, count = 0) shouldBe false
    }

    @Test
    fun `a cleared refusal refuses nothing`() {
        val r = FetchRefusal()
        r.refuse(ballast, epoch = 0, held = null, count = 0)
        r.clear()
        r.refuses(ballast, epoch = 0, held = null, count = 0) shouldBe false
    }

    @Test
    fun `a negative held count is refused`() {
        shouldThrow<IllegalArgumentException> { FetchRefusal().refuse(ballast, epoch = 0, held = null, count = -1) }
    }
}
