package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.shipwrights.enderkinesis.dimension.TreeSegment
import org.shipwrights.enderkinesis.dimension.TreeTip

class VineGenPlanTest {

    private val water = FloorAt { _, _ -> 64 }

    /** Level limbs at y 100 on a 4-block grid, 120 blocks wide, with leaf blobs on a ring 100 blocks out. */
    private fun syntheticTree(): Pair<List<TreeSegment>, List<TreeTip>> {
        val segments = ArrayList<TreeSegment>()
        var z = -60.0
        while (z <= 60.0) {
            segments += TreeSegment(-60.0, 100.0, z, 60.0, 101.0, z, 1.5, 1.2)
            z += 4.0
        }
        segments += TreeSegment(0.0, 74.0, 0.0, 0.0, 99.0, 0.0, 6.0, 4.0)
        val tips = (0 until 24).map { k ->
            val a = k * Math.PI * 2 / 24
            TreeTip((Math.cos(a) * 100).toInt(), 200, (Math.sin(a) * 100).toInt())
        }
        return segments to tips
    }

    private fun motherPlan(seed: Long): Pair<TreeVineSite, List<PlannedVine>> {
        val (segments, tips) = syntheticTree()
        val budget = treeVineBudget(VineGenRules.MOTHER_CROWN_RADIUS, 74.0, 252.0)
        val site = skeletonVineSite(segments, tips, 12, 7, 0.0, 0.0, budget.rimDistance)
        return site to planTreeVines(site, budget, water, seed)
    }

    @Test
    fun `the Mother budget is the Medium preset`() {
        val b = treeVineBudget(140.0, 74.0, 252.0)
        b.total shouldBe 140
        b.loops shouldBe 28
        b.reach shouldBe 7
        b.rim shouldBe 47
        b.branch shouldBe 58
        b.rimDistance shouldBe (95.0 plusOrMinus 1e-9)
        b.reachMaxY shouldBe (175.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a smaller crown keeps the density for each block of crown area`() {
        val b = treeVineBudget(70.0, 64.0, 120.0)
        b.total shouldBe 35
        b.loops shouldBe 7
        b.rimDistance shouldBe (47.5 plusOrMinus 1e-9)
    }

    @Test
    fun `a strand hangs straight down at the vine spacing`() {
        val nodes = strandNodes(3.5, 100.0, -2.5, 10.0, VINE_TARGET_SPACING)
        val n = nodes.size / 3
        nodes[1] shouldBe (100.0 plusOrMinus 1e-9)
        nodes[(n - 1) * 3 + 1] shouldBe (90.0 plusOrMinus 1e-9)
        for (i in 0 until n) {
            nodes[i * 3] shouldBe (3.5 plusOrMinus 1e-9)
            nodes[i * 3 + 2] shouldBe (-2.5 plusOrMinus 1e-9)
        }
        assertTrue(10.0 / (n - 1) <= VINE_TARGET_SPACING + 1e-9)
        polylineLength(nodes) shouldBe (10.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a loop is a catenary of its length between its two anchors`() {
        val nodes = catenaryNodes(0.5, 120.0, 0.5, 12.5, 117.0, 5.5, 18.0, VINE_TARGET_SPACING)
        val n = nodes.size / 3
        nodes[0] shouldBe (0.5 plusOrMinus 1e-9); nodes[1] shouldBe (120.0 plusOrMinus 1e-9); nodes[2] shouldBe (0.5 plusOrMinus 1e-9)
        nodes[(n - 1) * 3] shouldBe (12.5 plusOrMinus 1e-9)
        nodes[(n - 1) * 3 + 1] shouldBe (117.0 plusOrMinus 1e-9)
        nodes[(n - 1) * 3 + 2] shouldBe (5.5 plusOrMinus 1e-9)
        polylineLength(nodes) shouldBe (18.0 plusOrMinus 0.05)
        // It hangs under the chord, and its low point is under both anchors.
        for (i in 1 until n - 1) {
            val t = hypot(nodes[i * 3] - 0.5, nodes[i * 3 + 2] - 0.5) / 13.0
            assertTrue(nodes[i * 3 + 1] < 120.0 + (117.0 - 120.0) * t)
        }
        assertTrue((0 until n).minOf { nodes[it * 3 + 1] } < 117.0)
    }

    @Test
    fun `the plan of a tree keeps the counts, lengths and loop rules`() {
        val (site, plans) = motherPlan(42L)
        val budget = treeVineBudget(140.0, 74.0, 252.0)
        val loops = plans.filter { it.kind == PlannedKind.LOOP }
        val drops = plans.filter { it.kind == PlannedKind.DROP }
        val strands = plans.filter { it.kind == PlannedKind.STRAND }
        assertTrue(plans.size <= budget.total)
        assertTrue(strands.size <= budget.rim + budget.branch)
        assertTrue(drops.size <= budget.reach)
        assertTrue(loops.size <= budget.loops)
        // The synthetic tree has room for every vine of the budget.
        strands.size shouldBe budget.rim + budget.branch
        drops.size shouldBe budget.reach
        for (p in strands) {
            val room = dropRoom(site.field, water, p.ax, p.ay, p.az) - VineGenRules.FLOOR_GAP
            assertTrue(p.length >= VineGenRules.MIN_STRAND && p.length <= VineGenRules.STRAND_MAX)
            assertTrue(p.length <= room + 1e-9)
            assertTrue(p.length >= VineGenRules.STRAND_MIN || abs(p.length - room) < 1e-9)
        }
        for (p in drops) {
            assertTrue(p.ay <= budget.reachMaxY)
            assertTrue(p.length >= VineGenRules.REACH_MIN_ROOM)
        }
        for (p in loops) {
            val gapXZ = hypot((p.bx - p.ax).toDouble(), (p.bz - p.az).toDouble())
            val gap = sqrt(gapXZ * gapXZ + (p.by - p.ay).toDouble() * (p.by - p.ay))
            assertTrue(gapXZ in VineGenRules.LOOP_GAP_MIN..VineGenRules.LOOP_GAP_MAX)
            assertTrue(abs(p.by - p.ay) <= VineGenRules.LOOP_RISE_MAX)
            assertTrue(p.length / gap in VineGenRules.LOOP_SLACK..VineGenRules.LOOP_SLACK + VineGenRules.LOOP_SLACK_SPREAD)
            val nodes = catenaryNodes(p.ax + 0.5, p.ay.toDouble(), p.az + 0.5, p.bx + 0.5, p.by.toDouble(), p.bz + 0.5, p.length, VINE_TARGET_SPACING)
            assertTrue(ropeClear(nodes, site.field, water))
        }
        // Rim strands hang from leaves at the crown edge.
        val rimAnchors = site.rim.toList().chunked(3).map { it[0] to it[2] }.toSet()
        assertTrue(rimAnchors.all { (x, z) -> hypot(x + 0.5, z + 0.5) >= 95.0 })
    }

    @Test
    fun `a plan is the same for the same seed`() {
        val a = motherPlan(7L).second.map { listOf(it.kind, it.ax, it.ay, it.az, it.length, it.bx, it.by, it.bz, it.id) }
        val b = motherPlan(7L).second.map { listOf(it.kind, it.ax, it.ay, it.az, it.length, it.bx, it.by, it.bz, it.id) }
        val c = motherPlan(8L).second.map { listOf(it.kind, it.ax, it.ay, it.az, it.length, it.bx, it.by, it.bz, it.id) }
        a shouldBe b
        assertTrue(a != c)
        assertTrue(a.map { it.last() }.toSet().size == a.size)
    }

    /** A root path along x that lifts off the floor at 64 into an arch of [rise] blocks over [span] blocks. */
    private fun archPath(span: Int, rise: Double): Pair<IntArray, DoubleArray> {
        val count = span + 11
        val points = IntArray(count * 3)
        val radii = DoubleArray(count) { 1.5 }
        for (i in 0 until count) {
            val t = ((i - 5).toDouble() / span).coerceIn(0.0, 1.0)
            points[i * 3] = i
            points[i * 3 + 1] = 64 + (rise * Math.sin(Math.PI * t)).toInt()
            points[i * 3 + 2] = 0
        }
        return points to radii
    }

    @Test
    fun `arch strands hang only where the arch is 2 or more blocks above the floor`() {
        val (points, radii) = archPath(24, 9.0)
        val plans = planArchVines(points, radii, water, 99L)
        val strands = plans.filter { it.kind != PlannedKind.LOOP }
        assertTrue(strands.isNotEmpty())
        assertTrue(strands.size <= VineGenRules.ARCH_STRANDS)
        assertTrue(strands.map { it.ax to it.az }.toSet().size == strands.size)
        for (p in strands) {
            assertTrue(p.ay - 64 >= VineGenRules.ARCH_CLEARANCE)
            assertTrue(p.length <= p.ay - 64 - VineGenRules.FLOOR_GAP + 1e-9)
            assertTrue(p.length >= VineGenRules.ARCH_MIN_LENGTH)
        }
        assertTrue(plans.count { it.kind == PlannedKind.LOOP } <= 1)
    }

    @Test
    fun `a low arch gets no vines, and a short arch gets no loop`() {
        val (lowPoints, lowRadii) = archPath(24, 2.0)
        planArchVines(lowPoints, lowRadii, water, 1L).size shouldBe 0
        val (shortPoints, shortRadii) = archPath(10, 8.0)
        planArchVines(shortPoints, shortRadii, water, 1L).count { it.kind == PlannedKind.LOOP } shouldBe 0
    }
}
