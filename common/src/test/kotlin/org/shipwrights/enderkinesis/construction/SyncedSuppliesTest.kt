package org.shipwrights.enderkinesis.construction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SyncedSuppliesTest {
    @Test
    fun `unlinked supplies go and new links follow the kept ones`() {
        syncedSupplies(listOf("old", "kept"), listOf("new", "kept"), 8) shouldBe listOf("kept", "new")
    }

    @Test
    fun `the same links keep their order`() {
        syncedSupplies(listOf("a", "b", "c"), listOf("c", "a", "b"), 8) shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `no links clear the list`() {
        syncedSupplies(listOf("a", "b"), emptyList(), 8) shouldBe emptyList()
    }

    @Test
    fun `a repeated link counts once`() {
        syncedSupplies(emptyList(), listOf("a", "a", "b"), 8) shouldBe listOf("a", "b")
    }

    @Test
    fun `the cap keeps the kept supplies first`() {
        syncedSupplies(listOf("b"), listOf("a", "b", "c"), 2) shouldBe listOf("b", "a")
    }

    @Test
    fun `a negative cap is refused`() {
        shouldThrow<IllegalArgumentException> { syncedSupplies(listOf("a"), listOf("a"), -1) }
    }
}
