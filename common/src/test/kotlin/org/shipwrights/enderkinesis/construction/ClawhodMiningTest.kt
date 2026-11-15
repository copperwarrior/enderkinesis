package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ClawhodMiningTest {

    @Test
    fun `only cells on the facing side count`() {
        inFront(0.0, 2.0, 0.0, 0.0, 1.0, 0.0) shouldBe true
        inFront(3.0, 0.0, 0.0, 0.0, 1.0, 0.0) shouldBe false
        inFront(0.0, -1.0, 0.0, 0.0, 1.0, 0.0) shouldBe false
    }

    @Test
    fun `a sweep starts at the centre and never moves away faster than nearest first`() {
        val c = MineCursor(2)
        c.restart(10, 20, 30)
        c.next() shouldBe true
        Triple(c.x, c.y, c.z) shouldBe Triple(10, 20, 30)
        var last = 0
        val seen = HashSet<Triple<Int, Int, Int>>()
        seen.add(Triple(c.x, c.y, c.z))
        while (c.next()) {
            val d = (c.x - 10) * (c.x - 10) + (c.y - 20) * (c.y - 20) + (c.z - 30) * (c.z - 30)
            (d >= last) shouldBe true
            last = d
            seen.add(Triple(c.x, c.y, c.z))
        }
        seen.size shouldBe shellOffsets(2).size / 3
        c.wrapped shouldBe true
    }

    @Test
    fun `a rewind looks again from the centre`() {
        val c = MineCursor(1)
        c.restart(0, 0, 0)
        repeat(5) { c.next() }
        c.rewind()
        c.next() shouldBe true
        Triple(c.x, c.y, c.z) shouldBe Triple(0, 0, 0)
    }

    @Test
    fun `the shell keeps every cell within the radius plus one`() {
        // Radius 1: the whole 3x3x3 cube lies within distance sqrt(3) < 2.
        shellOffsets(1).size / 3 shouldBe 27
    }
}
