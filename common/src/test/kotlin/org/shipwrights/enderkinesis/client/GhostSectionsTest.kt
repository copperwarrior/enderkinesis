package org.shipwrights.enderkinesis.client

import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.joml.FrustumIntersection
import org.joml.Matrix4f
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GhostSectionsTest {
    // Clockwise 90 about Y, as StructureTemplate.transform: (x, z) -> (-z, x).
    private val clockwise = PlanAxes(intArrayOf(0, 0, -1, 0, 1, 0, 1, 0, 0))

    @Test
    fun `axes map local to world and back`() {
        clockwise.worldX(3, 4, 5) shouldBe -5
        clockwise.worldY(3, 4, 5) shouldBe 4
        clockwise.worldZ(3, 4, 5) shouldBe 3
        clockwise.localX(-5.0, 4.0, 3.0) shouldBe 3.0
        clockwise.localY(-5.0, 4.0, 3.0) shouldBe 4.0
        clockwise.localZ(-5.0, 4.0, 3.0) shouldBe 5.0
    }

    @Test
    fun `axes refuse a matrix that is not a signed permutation`() {
        assertThrows<IllegalArgumentException> { PlanAxes(intArrayOf(1, 1, 0, 0, 1, 0, 0, 0, 1)) }
    }

    @Test
    fun `the last section box stops at the plan size`() {
        val grid = GhostSectionGrid(20, 16, 16, PlanAxes.IDENTITY)
        grid.countX shouldBe 2
        val box = IntArray(6)
        grid.worldBox(1, 0, 0, box)
        box.toList() shouldBe listOf(16, 0, 0, 20, 16, 16)
    }

    @Test
    fun `a turned section box covers the turned cells`() {
        val grid = GhostSectionGrid(16, 16, 20, clockwise)
        val box = IntArray(6)
        // Local z 16..19 turns to world x -19..-16.
        grid.worldBox(0, 0, 1, box)
        box.toList() shouldBe listOf(-19, 0, 0, -15, 16, 16)
    }

    @Test
    fun `near sections come nearest first and stop at the radius`() {
        val grid = GhostSectionGrid(64, 16, 64, PlanAxes.IDENTITY)
        grid.collectNear(8.0, 8.0, 8.0, 16, IntArray(6))
        grid.nearCount shouldBe 4
        listOf(grid.nearX(0), grid.nearY(0), grid.nearZ(0)) shouldBe listOf(0, 0, 0)
        listOf(grid.nearX(3), grid.nearY(3), grid.nearZ(3)) shouldBe listOf(1, 0, 1)
    }

    @Test
    fun `a camera far from the plan finds no section`() {
        val grid = GhostSectionGrid(64, 16, 64, PlanAxes.IDENTITY)
        grid.collectNear(5000.0, 8.0, 8.0, 64, IntArray(6))
        grid.nearCount shouldBe 0
    }

    @Test
    fun `the near work is bounded by the radius, not the plan`() {
        val grid = GhostSectionGrid(1 shl 24, 256, 1 shl 24, clockwise)
        val radius = 64
        grid.collectNear(-1_000_000.0, 100.0, 2_000_000.0, radius, IntArray(6))
        val across = 2 * radius / 16 + 2
        grid.nearCount shouldBeLessThanOrEqual across * across * across
        for (i in 1 until grid.nearCount) {
            val box = IntArray(6)
            grid.worldBox(grid.nearX(i), grid.nearY(i), grid.nearZ(i), box)
            GhostSectionGrid.boxDistanceSq(box, -1_000_000.0, 100.0, 2_000_000.0) shouldBeLessThanOrEqual radius * radius.toDouble()
        }
    }

    @Test
    fun `the frustum keeps boxes in view and drops the rest`() {
        val frustum = FrustumIntersection(Matrix4f().setOrtho(-10f, 10f, -10f, 10f, -10f, 10f), false)
        GhostSectionGrid.inFrustum(frustum, intArrayOf(100, 100, 100, 101, 101, 101), 100.0, 100.0, 100.0) shouldBe true
        GhostSectionGrid.inFrustum(frustum, intArrayOf(200, 100, 100, 201, 101, 101), 100.0, 100.0, 100.0) shouldBe false
    }

    @Test
    fun `evictions free the least recently drawn first and never this frame's`() {
        val stamps = longArrayOf(1, 5, 3)
        val bytes = longArrayOf(10, 10, 10)
        ghostEvictions(stamps, bytes, 3, 15, now = 6).toList() shouldBe listOf(0, 2)
        ghostEvictions(stamps, bytes, 3, 15, now = 3).toList() shouldBe listOf(0)
        ghostEvictions(stamps, bytes, 3, 30, now = 6).toList() shouldBe emptyList()
    }

    @Test
    fun `face steps turn world faces into plan steps`() {
        PlanAxes.IDENTITY.localFaceSteps().toList() shouldBe GHOST_FACES.toList()
        val steps = clockwise.localFaceSteps()
        // East (+x world) is local -z under the clockwise turn, west is local +z; up stays up.
        steps.slice(15..17) shouldBe listOf(0, 0, -1)
        steps.slice(12..14) shouldBe listOf(0, 0, 1)
        steps.slice(3..5) shouldBe listOf(0, 1, 0)
    }

    @Test
    fun `hidden faces are the faces with a solid neighbour`() {
        val solid = setOf(Triple(1, 0, 0), Triple(0, 1, 0), Triple(0, 0, -1))
        val mask = ghostHiddenFaces(0, 0, 0, PlanAxes.IDENTITY.localFaceSteps()) { x, y, z -> Triple(x, y, z) in solid }
        // Up (1), north (2) and east (5), in Direction ordinal order.
        mask shouldBe ((1 shl 1) or (1 shl 2) or (1 shl 5))
        ghostHiddenFaces(0, 0, 0, PlanAxes.IDENTITY.localFaceSteps()) { _, _, _ -> false } shouldBe 0
    }

    @Test
    fun `hidden faces follow the turned plan`() {
        // A solid plan neighbour at local -z is the world east neighbour after a clockwise turn.
        val mask = ghostHiddenFaces(5, 5, 5, clockwise.localFaceSteps()) { x, y, z -> x == 5 && y == 5 && z == 4 }
        mask shouldBe (1 shl 5)
    }

    @Test
    fun `the near list leaves out filtered sections`() {
        val grid = GhostSectionGrid(48, 16, 16, PlanAxes.IDENTITY)
        grid.collectNear(8.0, 8.0, 8.0, 64, IntArray(6)) { sx, _, _ -> sx != 1 }
        grid.nearCount shouldBe 2
        (0 until grid.nearCount).map { grid.nearX(it) } shouldBe listOf(0, 2)
    }
}
