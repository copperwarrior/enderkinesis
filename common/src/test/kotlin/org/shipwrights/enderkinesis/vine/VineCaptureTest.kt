package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import org.joml.Vector3d
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.round

class VineCaptureTest {

    @Test
    fun `cross-links join ring neighbours, the last vine with the first`() {
        val plan = weavePlan(IntArray(8) { 40 })
        plan.map { setOf(it.strandA, it.strandB) }.toSet() shouldBe (0 until 8).map { setOf(it, (it + 1) % 8) }.toSet()
    }

    @Test
    fun `cross-links zig-zag every four nodes from three nodes under the tips`() {
        val plan = weavePlan(intArrayOf(30, 30, -1)).filter { setOf(it.strandA, it.strandB) == setOf(0, 1) }
        // k even leaves vine 0, k odd leaves vine 1; each reaches the other vine four nodes lower.
        plan.map { listOf(it.strandA, it.nodeA, it.strandB, it.nodeB) } shouldBe listOf(
            listOf(0, 27, 1, 23),
            listOf(1, 23, 0, 19),
            listOf(0, 19, 1, 15),
            listOf(1, 15, 0, 11),
            listOf(0, 11, 1, 7),
            listOf(1, 7, 0, 3),
        )
        plan.map { it.step } shouldBe (0..5).toList()
    }

    @Test
    fun `a cross-link never ties on the anchor node or the node next to it`() {
        for (links in 2..60) {
            for (link in weavePlan(intArrayOf(links, links + 3, links - 1))) {
                (link.nodeA >= 2) shouldBe true
                (link.nodeB >= 2) shouldBe true
            }
        }
    }

    @Test
    fun `a vine that does not hold the ship gets no cross-links`() {
        val plan = weavePlan(intArrayOf(30, -1, 30, 30))
        plan.none { it.strandA == 1 || it.strandB == 1 } shouldBe true
        plan.any { setOf(it.strandA, it.strandB) == setOf(2, 3) } shouldBe true
        plan.any { setOf(it.strandA, it.strandB) == setOf(3, 0) } shouldBe true
    }

    @Test
    fun `two vines make one pair, and short vines make no cross-links`() {
        weavePlan(intArrayOf(30, 30)).map { setOf(it.strandA, it.strandB) }.toSet() shouldBe setOf(setOf(0, 1))
        weavePlan(intArrayOf(8, 8, 8)).isEmpty() shouldBe true
        weavePlan(intArrayOf(30)).isEmpty() shouldBe true
    }

    @Test
    fun `each pair has a hard cap of cross-links`() {
        val plan = weavePlan(intArrayOf(10_000, 10_000))
        plan.size shouldBe MAX_CROSS_PER_PAIR
    }

    @Test
    fun `the wave goes from the tips down`() {
        val steps = weavePlan(IntArray(8) { 40 }).map { it.step }
        steps shouldBe steps.sorted()
    }

    @Test
    fun `partners pair up two by two`() {
        (0 until 8).map { partnerOf(it, 8) } shouldBe listOf(1, 0, 3, 2, 5, 4, 7, 6)
        partnerOf(6, 7) shouldBe 6
    }

    @Test
    fun `the pay-out target climbs 24 nodes a second from its base in whole spacings and stops at the full length`() {
        val spacing = 0.39
        capturePayTarget(0, 2 * spacing, spacing, 100.0) shouldBe (2 * spacing plusOrMinus 1e-12)
        capturePayTarget(20, 2 * spacing, spacing, 100.0) shouldBe (26 * spacing plusOrMinus 1e-9)
        capturePayTarget(200, 2 * spacing, spacing, 10 * spacing) shouldBe (10 * spacing plusOrMinus 1e-12)
        for (t in 0..100) {
            val target = capturePayTarget(t, 2 * spacing, spacing, 1000.0)
            (abs(target / spacing - round(target / spacing)) < 1e-7) shouldBe true
        }
    }

    @Test
    fun `the hold target is whole spacings, never under the span, never over the rest`() {
        val spacing = 0.4
        captureHoldTarget(10.1, spacing, 20.0) shouldBe (10.4 plusOrMinus 1e-9)
        captureHoldTarget(10.0, spacing, 20.0) shouldBe (10.0 plusOrMinus 1e-9)
        captureHoldTarget(30.0, spacing, 20.0) shouldBe 20.0
        captureHoldTarget(0.1, spacing, 20.0) shouldBe (0.8 plusOrMinus 1e-12)
    }

    @Test
    fun `the guide starts at the anchor and ends on the hull`() {
        val a = Vector3d(4.0, 0.5, 0.0)
        val t = Vector3d(1.0, 12.0, 0.5)
        val pa = Vector3d(3.0, 0.5, 3.0)
        val pt = Vector3d(0.5, 12.0, 1.0)
        val out = Vector3d()
        captureGuide(a, t, pa, pt, 2.0, 1.0, 0.0, out).distance(a) shouldBe (0.0 plusOrMinus 1e-9)
        captureGuide(a, t, pa, pt, 2.0, 1.0, 1.0, out).distance(t) shouldBe (0.0 plusOrMinus 1e-9)
        (captureGuideLength(a, t, pa, pt, 2.0, 1.0, out) >= a.distance(t)) shouldBe true
    }

    @Test
    fun `the next vine launches when the one before it paid out four blocks, latched, or stopped`() {
        val point = VinePoint(BlockPos.ZERO, Vector3d(), null)
        val strand = CaptureStrand(1L, point, point, 0.4, 1.5, 1.0)
        launchesNext(strand) shouldBe false
        strand.paid = LAUNCH_LEAD - 0.01
        launchesNext(strand) shouldBe false
        strand.paid = LAUNCH_LEAD
        launchesNext(strand) shouldBe true
        strand.paid = 0.0
        strand.latched = true
        launchesNext(strand) shouldBe true
        strand.latched = false
        strand.lost = true
        launchesNext(strand) shouldBe true
    }

    private fun ring(n: Int, radius: Double) = List(n) { i ->
        val a = 2 * Math.PI * i / n
        Vector3d(radius * kotlin.math.cos(a), 0.0, radius * kotlin.math.sin(a))
    }

    @Test
    fun `aims spread over the side of the ship that faces the circle, apart from each other`() {
        val com = Vector3d(10.0, 30.0, -4.0)
        val toCircle = Vector3d(0.0, -20.0, 0.0)
        val half = Vector3d(6.0, 2.0, 4.0)
        val aims = captureAims(com, toCircle, half, ring(8, 4.0), 0.7)
        aims.size shouldBe 8
        for (a in aims) {
            a.y shouldBe (30.0 plusOrMinus 1e-9)
            (abs(a.x - com.x) <= half.x * 0.7 + 1e-9) shouldBe true
            (abs(a.z - com.z) <= half.z * 0.7 + 1e-9) shouldBe true
        }
        for (i in aims.indices) for (j in aims.indices) if (i < j) (aims[i].distance(aims[j]) > MIN_HULL_GAP) shouldBe true
    }

    @Test
    fun `ring neighbours aim at neighbour points, also for a ship level with the circle`() {
        val com = Vector3d()
        for (toCircle in listOf(Vector3d(0.0, -10.0, 0.0), Vector3d(30.0, 0.0, 0.0))) {
            val aims = captureAims(com, toCircle, Vector3d(5.0, 5.0, 5.0), ring(8, 4.0), 0.7)
            for (i in aims.indices) {
                val next = aims[(i + 1) % aims.size].distance(aims[i])
                val across = aims[(i + 4) % aims.size].distance(aims[i])
                (next < across) shouldBe true
                (next > MIN_HULL_GAP) shouldBe true
            }
        }
    }

    @Test
    fun `a segment is cut to the box, and a miss gives false`() {
        val from = Vector3d(-10.0, 0.5, 0.5)
        val to = Vector3d(10.0, 0.5, 0.5)
        clipToBox(from, to, Vector3d(0.0, 0.0, 0.0), Vector3d(2.0, 1.0, 1.0)) shouldBe true
        from.x shouldBe (0.0 plusOrMinus 1e-9)
        to.x shouldBe (2.0 plusOrMinus 1e-9)
        clipToBox(Vector3d(-10.0, 5.0, 0.5), Vector3d(10.0, 5.0, 0.5), Vector3d(0.0, 0.0, 0.0), Vector3d(2.0, 1.0, 1.0)) shouldBe false
    }

    @Test
    fun `the tip never lies further from the anchor than the paid rope`() {
        val anchor = Vector3d(1.0, 2.0, 3.0)
        val tip = clampReach(anchor, Vector3d(1.0, 12.0, 3.0), 4.0)
        tip.distance(anchor) shouldBe (4.0 plusOrMinus 1e-9)
        tip.y shouldBe (6.0 plusOrMinus 1e-9)
        clampReach(anchor, Vector3d(1.0, 3.0, 3.0), 4.0).y shouldBe (3.0 plusOrMinus 1e-9)
    }

    @Test
    fun `the vine starts beside its candle on the side toward the hull, clear of the candle and the next block`() {
        val candle = Vector3d(10.5, 64.125, -2.5)
        val away = Vector3d(1.0, 0.0, 0.0)
        for (toward in listOf(Vector3d(4.0, 20.0, 0.0), Vector3d(-3.0, 9.0, 3.0), Vector3d(0.0, 1.0, -4.0), Vector3d(0.0, 30.0, 0.0))) {
            val p = besideCandle(candle, toward, away)
            val ox = p.x - candle.x
            val oz = p.z - candle.z
            // Candle shapes reach 3/16 from the centre; a node has radius 0.1.
            val gx = maxOf(0.0, abs(ox) - 0.1875)
            val gz = maxOf(0.0, abs(oz) - 0.1875)
            (kotlin.math.sqrt(gx * gx + gz * gz) > PARTICLE_RADIUS) shouldBe true
            (abs(ox) + PARTICLE_RADIUS < 0.5 && abs(oz) + PARTICLE_RADIUS < 0.5) shouldBe true
            p.y shouldBe (candle.y plusOrMinus 1e-12)
            val side = if (toward.x * toward.x + toward.z * toward.z > 0) toward else away
            (ox * side.x + oz * side.z > 0) shouldBe true
        }
    }

    @Test
    fun `the tip keeps the rope nearly taut, so the reel can add nodes, and never pins past the paid rope`() {
        val spacing = 0.39
        for (paid in listOf(0.78, 1.17, 5.0, 70.0)) {
            val reach = captureTipReach(paid, spacing)
            (reach < paid) shouldBe true
            // Slack under one particle diameter keeps the new node a diameter off the anchor.
            (paid - reach < 2 * PARTICLE_RADIUS) shouldBe true
            val anchor = Vector3d(0.0, 64.35, 0.0)
            clampReach(anchor, Vector3d(0.0, 64.35, paid + 3.0), reach).distance(anchor) shouldBe (reach plusOrMinus 1e-9)
        }
    }
}
