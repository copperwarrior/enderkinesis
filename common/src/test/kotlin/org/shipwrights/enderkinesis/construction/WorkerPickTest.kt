package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WorkerPickTest {

    @Test
    fun `every section must end a pass before the search counts as complete`() {
        val pick = WorkerPick(BuildPattern.SCAN)
        repeat(3) { pick.passEnded(3) shouldBe false }
        pick.searchedAll shouldBe false
        pick.passEnded(3) shouldBe true
        pick.searchedAll shouldBe true
    }

    @Test
    fun `pass ends add up across pick calls`() {
        val pick = WorkerPick(BuildPattern.SCAN)
        pick.passEnded(2) shouldBe false
        pick.passEnded(2) shouldBe false
        // A second pick call: the scan budget ran out between the two, but the count stays.
        pick.passEnded(2) shouldBe true
    }

    @Test
    fun `a find starts the count again`() {
        val pick = WorkerPick(BuildPattern.SCAN)
        pick.passEnded(1) shouldBe false
        pick.found() shouldBe true
        pick.passEnded(1) shouldBe false
        pick.passEnded(1) shouldBe true
    }

    @Test
    fun `once complete every further pass end stays complete`() {
        val pick = WorkerPick(BuildPattern.SCAN)
        repeat(2) { pick.passEnded(1) }
        repeat(5) { pick.passEnded(1) shouldBe true }
    }
}
