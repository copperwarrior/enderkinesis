package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import org.joml.Vector3d
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.round

class VineCaptureTest {

    private fun spacings(n: Int, spacing: Double = 0.4) = DoubleArray(n) { spacing }

    @Test
    fun `each strand ties its nearest strand, and some of its next two, each pair once`() {
        val dirs = captureDirections(16, java.util.Random(5L))
        val pairs = weavePairs(dirs, java.util.Random(5L))
        pairs.toSet().size shouldBe pairs.size
        for ((a, b) in pairs) (a < b) shouldBe true
        for (i in dirs.indices) {
            val nearest = dirs.indices.filter { it != i }.minBy { dirs[i].angle(dirs[it]) }
            (minOf(i, nearest) to maxOf(i, nearest) in pairs) shouldBe true
        }
        for ((a, b) in pairs) {
            val third = dirs.indices.filter { it != a }.sortedBy { dirs[a].angle(dirs[it]) }.take(WEAVE_REACH)
            val thirdOfB = dirs.indices.filter { it != b }.sortedBy { dirs[b].angle(dirs[it]) }.take(WEAVE_REACH)
            (b in third || a in thirdOfB) shouldBe true
        }
        (pairs.size in 16..40) shouldBe true
    }

    @Test
    fun `cross-links slant down from strand to strand at irregular depths`() {
        val drops = HashSet<Int>()
        for (seed in 0L until 20L) {
            val plan = weavePlan(intArrayOf(80, 80), spacings(2), listOf(0 to 1), java.util.Random(seed))
            for (link in plan) {
                val drop = (80 - link.nodeB) - (80 - link.nodeA)
                (drop.toDouble() in CROSS_DROP_MIN / 0.4 - 1..CROSS_DROP_MAX / 0.4 + 1) shouldBe true
                (80 - link.nodeA >= round(CROSS_FIRST_MIN / 0.4).toInt()) shouldBe true
                drops += drop
            }
            plan.map { it.step } shouldBe plan.map { it.step }.sorted()
        }
        (drops.size > 3) shouldBe true
    }

    @Test
    fun `the depths count blocks, so a wide strand with longer links gets fewer nodes per step`() {
        for (seed in 0L until 20L) {
            for (link in weavePlan(intArrayOf(100, 100), doubleArrayOf(0.4, 0.6), listOf(0 to 1), java.util.Random(seed))) {
                val nodesUnder = if (link.strandB == 1) 100 - link.nodeB else 100 - link.nodeA
                (nodesUnder * 0.6 <= CROSS_FIRST_MAX + MAX_CROSS_PER_PAIR * (CROSS_DROP_MAX + CROSS_GAP_MAX) + 0.3) shouldBe true
            }
        }
    }

    @Test
    fun `a cross-link never ties on the anchor node or the node next to it`() {
        for (links in 2..120) {
            val plan = weavePlan(
                intArrayOf(links, links + 3, links - 1), doubleArrayOf(0.4, 0.4875, 0.585), listOf(0 to 1, 1 to 2, 0 to 2),
                java.util.Random(links.toLong()),
            )
            for (link in plan) {
                (link.nodeA >= 2) shouldBe true
                (link.nodeB >= 2) shouldBe true
            }
        }
    }

    @Test
    fun `a strand that does not hold the ship gets no cross-links, and short strands get none`() {
        val plans = (0L until 20L).map { weavePlan(intArrayOf(40, -1, 40, 40), spacings(4), listOf(0 to 1, 1 to 2, 2 to 3, 0 to 3), java.util.Random(it)) }
        plans.all { plan -> plan.none { it.strandA == 1 || it.strandB == 1 } } shouldBe true
        plans.any { plan -> plan.any { setOf(it.strandA, it.strandB) == setOf(2, 3) } } shouldBe true
        plans.any { plan -> plan.any { setOf(it.strandA, it.strandB) == setOf(0, 3) } } shouldBe true
        weavePlan(intArrayOf(6, 6, 6), spacings(3), listOf(0 to 1, 1 to 2), java.util.Random(1L)).isEmpty() shouldBe true
        weavePlan(intArrayOf(40), spacings(1), emptyList(), java.util.Random(1L)).isEmpty() shouldBe true
    }

    @Test
    fun `each pair has a hard cap of four cross-links, and some are skipped`() {
        val sizes = (0L until 40L).map { weavePlan(intArrayOf(10_000, 10_000), spacings(2), listOf(0 to 1), java.util.Random(it)).size }
        sizes.all { it <= MAX_CROSS_PER_PAIR } shouldBe true
        sizes.any { it < MAX_CROSS_PER_PAIR } shouldBe true
        MAX_CROSS_PER_PAIR shouldBe 4
    }

    @Test
    fun `the wave goes from the tips down`() {
        val dirs = captureDirections(16, java.util.Random(2L))
        val random = java.util.Random(2L)
        val steps = weavePlan(IntArray(16) { 120 }, spacings(16), weavePairs(dirs, random), random).map { it.step }
        steps shouldBe steps.sorted()
    }

    @Test
    fun `cross-link width stays in whole pixels from the thinnest strand up to the thinner tied strand, and sag stays in range`() {
        for (i in 0 until 200) {
            val hash = crossLinkHash(i.toLong(), CrossLinkPlan(i % 16, 20 + i, (i + 3) % 16, 30 + i, i % 4))
            for (px in MIN_STRAND_PX..MAX_STRAND_PX) {
                val width = crossLinkWidth(hash, px / 16.0)
                (width * 16 in MIN_STRAND_PX.toDouble()..px.toDouble()) shouldBe true
                abs(width * 16 - round(width * 16)) shouldBe (0.0 plusOrMinus 1e-9)
            }
            (crossLinkSag(hash) in CROSS_SAG_MIN..CROSS_SAG_MAX) shouldBe true
        }
    }

    @Test
    fun `a strand at the grow cap pays out more only when its rope is short of the nearest hull block`() {
        capLatchReelTarget(10.0, 24.2, 0.4) shouldBe null
        capLatchReelTarget(24.2 - 2 * TIP_SLACK, 24.2, 0.4) shouldBe null
        capLatchReelTarget(24.2, 24.2, 0.4) shouldBe (24.6 plusOrMinus 1e-9)
        capLatchReelTarget(80.0, 24.2, 0.4) shouldBe (80.4 plusOrMinus 1e-9)
    }

    @Test
    fun `the cap latch takes the free hull block nearest the tip, not one another strand holds`() {
        val hull = (0..9).flatMap { x -> (0..3).map { z -> BlockPos(x, 0, z) } }.toSet()
        val tip = Vector3d(4.5, 7.0, 1.5)
        nearestSolidCell(BlockPos(0, 0, 0), BlockPos(9, 3, 3), tip, { it in hull }) shouldBe BlockPos(4, 0, 1)
        val held = setOf(BlockPos(4, 0, 1))
        val next = nearestSolidCell(BlockPos(0, 0, 0), BlockPos(9, 3, 3), tip, { it in hull }) { it !in held }
        (next != null && next != BlockPos(4, 0, 1) && next.distSqr(BlockPos(4, 0, 1)) <= 1.0) shouldBe true
    }

    @Test
    fun `strand widths are whole pixels from 3 to 6, and the same rite gives the same widths`() {
        val widths = List(200) { captureStrandWidth(java.util.Random(7L)) }
        widths.toSet().size shouldBe 1
        val spread = java.util.Random(11L).let { r -> List(400) { captureStrandWidth(r) } }
        spread.toSet() shouldBe setOf(3.0 / 16, 4.0 / 16, 5.0 / 16, 6.0 / 16)
        for (w in spread) validVineWidth(w) shouldBe true
    }

    @Test
    fun `wider vines have a larger radius, more mass and strength, and spacing over four radii`() {
        vineSpacing(VINE_WIDTH) shouldBe (VINE_TARGET_SPACING plusOrMinus 1e-12)
        vineParticleRadius(VINE_WIDTH) shouldBe (PARTICLE_RADIUS plusOrMinus 1e-12)
        for (px in MIN_STRAND_PX..MAX_STRAND_PX) {
            val w = px / 16.0
            val ratio = w / VINE_WIDTH
            vineParticleRadius(w) shouldBe (PARTICLE_RADIUS * ratio plusOrMinus 1e-12)
            vineMassPerLength(w) shouldBe (MASS_PER_LENGTH * ratio * ratio plusOrMinus 1e-9)
            vineBreakForce(w) shouldBe (VINE_BREAK_FORCE * ratio * ratio plusOrMinus 1e-3)
            (vineSpacing(w) >= 4 * vineParticleRadius(w) - 1e-12) shouldBe true
            // The first seed link of a capture strand must not be under one particle diameter.
            (FIRST_LINK * vineSpacing(w) / VINE_TARGET_SPACING >= 2 * vineParticleRadius(w)) shouldBe true
            (reelFloor(10, w) / 10 > 2 * vineParticleRadius(w)) shouldBe true
        }
    }

    @Test
    fun `wide strands pay out the same blocks a second with no less force`() {
        capturePayoutForce(VINE_WIDTH, FIRST_LINK) shouldBe (2000.0 plusOrMinus 1e-9)
        for (px in MIN_STRAND_PX..MAX_STRAND_PX) {
            val w = px / 16.0
            val spacing = FIRST_LINK * vineSpacing(w) / VINE_TARGET_SPACING
            captureNodeRate(spacing) * spacing shouldBe (CAPTURE_NODES_PER_SECOND * FIRST_LINK plusOrMinus 1e-9)
            (capturePayoutForce(w, spacing) >= 2000.0) shouldBe true
        }
        (capturePayoutForce(6.0 / 16, 0.585) > capturePayoutForce(5.0 / 16, 0.4875)) shouldBe true
    }

    @Test
    fun `the 16 strand directions are unit vectors spread all round, the same for the same rite`() {
        val dirs = captureDirections(16, java.util.Random(5L))
        dirs.size shouldBe 16
        for (d in dirs) d.length() shouldBe (1.0 plusOrMinus 1e-9)
        captureDirections(16, java.util.Random(5L)).zip(dirs).forEach { (a, b) -> a.distance(b) shouldBe (0.0 plusOrMinus 1e-12) }
        (captureDirections(16, java.util.Random(6L)).first().distance(dirs.first()) > 1e-6) shouldBe true
        // Even spread: no two close together, no side of the ship without a strand near it.
        for (i in dirs.indices) for (j in i + 1 until dirs.size) (dirs[i].angle(dirs[j]) > Math.toRadians(30.0)) shouldBe true
        val sides = listOf(
            Vector3d(1.0, 0.0, 0.0), Vector3d(-1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0),
            Vector3d(0.0, -1.0, 0.0), Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 0.0, -1.0),
        )
        for (side in sides) (dirs.minOf { it.angle(side) } < Math.toRadians(55.0)) shouldBe true
        val mean = Vector3d()
        dirs.forEach { mean.add(it) }
        (mean.length() / dirs.size < 0.1) shouldBe true
    }

    private val half = Vector3d(6.0, 2.0, 4.0)

    /** True when [p] is inside the box of half sizes 6, 2, 4 round the origin, past a small margin. */
    private fun inBox(p: Vector3d) = abs(p.x) < 6.0 - 1e-6 && abs(p.y) < 2.0 - 1e-6 && abs(p.z) < 4.0 - 1e-6

    /** Where the line in from the target of [aim] first meets the solid box. */
    private fun hullHit(aim: CaptureAim): Vector3d {
        val from = Vector3d(); val to = Vector3d()
        inwardLine(aim, from, to)
        clipToBox(from, to, Vector3d(half).negate(), half) shouldBe true
        return from
    }

    @Test
    fun `the route goes round the hull, never through it, for front, back, up, down and every side`() {
        val dirs = listOf(
            Vector3d(1.0, 0.0, 0.0), Vector3d(-1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, -1.0, 0.0),
            Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 0.0, -1.0),
        ) + captureDirections(16, java.util.Random(9L))
        // Below, far to the side, under the hull inside the ellipsoid, and straight above.
        val anchors = listOf(Vector3d(0.5, -30.0, 2.0), Vector3d(40.0, -10.0, 0.0), Vector3d(0.0, -3.0, 0.0), Vector3d(0.0, 25.0, 0.0))
        val route = CaptureRoute()
        val p = Vector3d()
        for (dir in dirs) for (anchor in anchors) {
            val aim = captureAim(Vector3d(), half, dir)
            val hull = hullHit(aim)
            route.build(anchor, hull, aim)
            (route.length >= anchor.distance(hull) - 1e-9) shouldBe true
            route.guide(0.0, 2.0, p).distance(anchor) shouldBe (0.0 plusOrMinus 1e-9)
            route.guide(1.0, 2.0, p).distance(hull) shouldBe (0.0 plusOrMinus 1e-9)
            route.guide(route.targetShare, 2.0, p).distance(aim.target(Vector3d())) shouldBe (0.0 plusOrMinus 1e-6)
            for (writhe in listOf(0.0, 2.8, -2.8)) for (i in 0..1000) inBox(route.guide(i / 1000.0, writhe, p)) shouldBe false
        }
    }

    @Test
    fun `the target is out past the hull along the direction, and the line in meets the hull face on that side`() {
        for (dir in captureDirections(16, java.util.Random(3L))) {
            val aim = captureAim(Vector3d(), half, dir)
            val target = aim.target(Vector3d())
            inBox(target) shouldBe false
            // Past the box's furthest point along the direction.
            (target.dot(dir) > abs(dir.x) * 6.0 + abs(dir.y) * 2.0 + abs(dir.z) * 4.0) shouldBe true
            val hit = hullHit(aim)
            (hit.dot(dir) > 0.0) shouldBe true
            // On the line from the target through the centre.
            Vector3d(target).sub(hit).normalize().dot(dir) shouldBe (1.0 plusOrMinus 1e-9)
            val from = Vector3d(); val to = Vector3d()
            inwardLine(aim, from, to)
            to.distance(Vector3d(target).negate()) shouldBe (0.0 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `a nudge turns the direction by the angle it is given and keeps it a unit vector`() {
        val random = java.util.Random(4L)
        for (dir in captureDirections(8, random) + Vector3d(0.0, 1.0, 0.0)) {
            for (deg in listOf(10.0, 20.0, 30.0)) {
                val turned = nudgedDirection(dir, Math.toRadians(deg), random)
                turned.length() shouldBe (1.0 plusOrMinus 1e-9)
                Math.toDegrees(turned.angle(dir)) shouldBe (deg plusOrMinus 1e-6)
            }
        }
    }

    @Test
    fun `the fallback takes the solid cell nearest the target that is free, and stops on an empty ship`() {
        val solid = setOf(BlockPos(9, 0, 0), BlockPos(9, 1, 0), BlockPos(2, 2, 2))
        val min = BlockPos(0, 0, 0)
        val max = BlockPos(9, 9, 9)
        val target = Vector3d(20.0, 0.5, 0.5)
        nearestSolidCell(min, max, target, { it in solid }) shouldBe BlockPos(9, 0, 0)
        nearestSolidCell(min, max, target, { it in solid }) { it != BlockPos(9, 0, 0) } shouldBe BlockPos(9, 1, 0)
        nearestSolidCell(min, max, Vector3d(-5.0, 2.5, 2.5), { it in solid }) shouldBe BlockPos(2, 2, 2)
        nearestSolidCell(min, max, target, { false }) shouldBe null
        nearestSolidCell(BlockPos(0, 0, 0), BlockPos(4000, 4000, 4000), target, { false }) shouldBe null
    }

    @Test
    fun `an aim saves and loads, and a bad one does not load`() {
        val aim = captureAim(Vector3d(1.0, 2.0, 3.0), half, Vector3d(0.0, 0.6, 0.8))
        val back = CaptureAim.load(aim.save())!!
        back.centre.distance(aim.centre) shouldBe (0.0 plusOrMinus 1e-12)
        back.radii.distance(aim.radii) shouldBe (0.0 plusOrMinus 1e-12)
        back.dir.distance(aim.dir) shouldBe (0.0 plusOrMinus 1e-12)
        back.reach shouldBe (aim.reach plusOrMinus 1e-12)
        CaptureAim.load(net.minecraft.nbt.CompoundTag()) shouldBe null
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
    fun `a reel is stalled when it takes in under two hundredths of a spacing a tick`() {
        reelStalled(Double.NaN, 10.0, 0.4) shouldBe false
        reelStalled(10.0, 9.9, 0.4) shouldBe false
        reelStalled(10.0, 9.995, 0.4) shouldBe true
        reelStalled(10.0, 10.0, 0.4) shouldBe true
    }

    @Test
    fun `the next vine launches when the one before it paid out four blocks, latched, or stopped`() {
        val point = VinePoint(BlockPos.ZERO, Vector3d(), null)
        val strand = CaptureStrand(1L, point, point, 0.4, 1.5, VINE_WIDTH, captureAim(Vector3d(), half, Vector3d(1.0, 0.0, 0.0)))
        launchesNext(strand) shouldBe false
        strand.paid = LAUNCH_LEAD - 0.01
        launchesNext(strand) shouldBe false
        strand.paid = LAUNCH_LEAD
        launchesNext(strand) shouldBe true
        strand.paid = 0.0
        strand.latched = true
        launchesNext(strand) shouldBe true
        strand.latched = false
        strand.parked = true
        launchesNext(strand) shouldBe true
        strand.parked = false
        strand.lost = true
        launchesNext(strand) shouldBe true
    }

    private fun strandAlong(dir: Vector3d): CaptureStrand {
        val point = VinePoint(BlockPos.ZERO, Vector3d(), null)
        return CaptureStrand(1L, point, point, 0.4, 1.5, VINE_WIDTH, captureAim(Vector3d(), half, dir))
    }

    @Test
    fun `the weave comes before the latch - grow, weave, latch, tighten, hold`() {
        CapturePhase.entries shouldBe listOf(CapturePhase.GROW, CapturePhase.WEAVE, CapturePhase.LATCH, CapturePhase.TIGHTEN, CapturePhase.HOLD)
        CapturePhase.GROW.following shouldBe CapturePhase.WEAVE
        CapturePhase.WEAVE.following shouldBe CapturePhase.LATCH
        CapturePhase.LATCH.following shouldBe CapturePhase.TIGHTEN
        CapturePhase.TIGHTEN.following shouldBe CapturePhase.HOLD
        CapturePhase.HOLD.following shouldBe CapturePhase.HOLD
    }

    @Test
    fun `grow stops the tip at its target, and the latch takes it from there in to the hull`() {
        val route = CaptureRoute()
        val p = Vector3d()
        for (dir in captureDirections(16, java.util.Random(12L))) {
            val aim = captureAim(Vector3d(), half, dir)
            val target = aim.target(Vector3d())
            val hull = hullHit(aim)
            route.build(Vector3d(0.5, -30.0, 2.0), hull, aim)
            val toTarget = route.length * route.targetShare
            for (paid in listOf(toTarget, toTarget + 0.4, route.length, route.length * 3)) {
                route.guide(captureGuideShare(paid, route, toHull = false), 2.0, p).distance(target) shouldBe (0.0 plusOrMinus 1e-6)
            }
            route.guide(captureGuideShare(toTarget * 0.5, route, toHull = true), 2.0, p).distance(target) shouldBe (0.0 plusOrMinus 1e-6)
            route.guide(captureGuideShare(route.length, route, toHull = true), 2.0, p).distance(hull) shouldBe (0.0 plusOrMinus 1e-9)
            // Half way in, the tip is on the line from the target to the hull.
            val mid = route.guide(captureGuideShare((toTarget + route.length) / 2, route, toHull = true), 2.0, p)
            (mid.distance(target) + mid.distance(hull)) shouldBe (target.distance(hull) plusOrMinus 1e-6)
        }
    }

    @Test
    fun `a strand at its target hangs there while the weave ties it, and moves with the ship`() {
        val strand = strandAlong(Vector3d(0.0, 0.6, 0.8))
        strand.hold.set(1.0, 2.0, 3.0)
        strand.park(capped = false)
        strand.parked shouldBe true
        strand.hangs shouldBe true
        val target = strand.aim.target(Vector3d())
        strand.hold.distance(target) shouldBe (0.0 plusOrMinus 1e-12)
        val anchor = Vector3d(0.5, -30.0, 2.0)
        val paid = 80.0
        captureHeldTip(strand.hold, org.joml.Matrix4d(), anchor, paid, 0.4).distance(target) shouldBe (0.0 plusOrMinus 1e-12)
        val moved = org.joml.Matrix4d().translate(5.0, -2.0, 7.0).rotateY(0.7)
        captureHeldTip(strand.hold, moved, anchor, paid, 0.4).distance(moved.transformPosition(Vector3d(target))) shouldBe (0.0 plusOrMinus 1e-9)
        // Never pinned past the paid rope.
        captureHeldTip(strand.hold, org.joml.Matrix4d(), anchor, 2.0, 0.4).distance(anchor) shouldBe (captureTipReach(2.0, 0.4) plusOrMinus 1e-9)
    }

    @Test
    fun `a strand at the grow cap hangs where its tip is, not at its target`() {
        val strand = strandAlong(Vector3d(1.0, 0.0, 0.0))
        strand.hold.set(-4.0, 9.0, 1.0)
        strand.park(capped = true)
        strand.capped shouldBe true
        strand.hangs shouldBe true
        strand.hold.distance(Vector3d(-4.0, 9.0, 1.0)) shouldBe (0.0 plusOrMinus 1e-12)
    }

    @Test
    fun `the weave ties only strands that hang free of the hull, never a latched one`() {
        val strands = List(4) { strandAlong(Vector3d(1.0, 0.0, 0.0)) }
        strands[0].park(capped = false)
        strands[1].park(capped = false)
        strands[1].latched = true
        // Strand 2 still grows.
        strands[3].park(capped = true)
        hangingLinkCounts(strands) { 41 }.toList() shouldBe listOf(40, -1, -1, 40)
        strands[3].lost = true
        hangingLinkCounts(strands) { if (it == 0) 1 else 41 }.toList() shouldBe listOf(-1, -1, -1, -1)
    }

    @Test
    fun `a pay-out at the start moves the cross-link joints up by the new nodes, but a rebuilt chain only counts again`() {
        payoutShift(0L, 0, 7L, 60) shouldBe 0
        payoutShift(7L, 60, 7L, 63) shouldBe 3
        payoutShift(7L, 63, 7L, 63) shouldBe 0
        payoutShift(7L, 63, 9L, 40) shouldBe 0
        payoutShift(7L, 63, 7L, 0) shouldBe 0
    }

    @Test
    fun `a hanging strand saves and loads with its hold point`() {
        val strand = strandAlong(Vector3d(0.0, 0.0, 1.0))
        strand.hold.set(3.0, -1.0, 2.5)
        strand.park(capped = true)
        val back = CaptureStrand.load(strand.save())!!
        back.parked shouldBe true
        back.capped shouldBe true
        back.hold.distance(strand.hold) shouldBe (0.0 plusOrMinus 1e-12)
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
    fun `the two widest strands of one candle start apart, clear of the candle and the next block`() {
        val candle = Vector3d(10.5, 64.125, -2.5)
        val radius = vineParticleRadius(MAX_STRAND_PX / 16.0)
        val reach = 0.5 - radius - 0.01
        for (toward in listOf(Vector3d(4.0, 20.0, 0.0), Vector3d(-3.0, 9.0, 3.0), Vector3d(1.0, 1.0, -1.0))) {
            val left = besideCandle(candle, toward, Vector3d(1.0, 0.0, 0.0), -Math.PI / 4, reach)
            val right = besideCandle(candle, toward, Vector3d(1.0, 0.0, 0.0), Math.PI / 4, reach)
            (left.distance(right) > 2 * radius) shouldBe true
            for (p in listOf(left, right)) {
                val ox = p.x - candle.x
                val oz = p.z - candle.z
                (abs(ox) + radius < 0.5 && abs(oz) + radius < 0.5) shouldBe true
                (kotlin.math.sqrt(ox * ox + oz * oz) - radius >= 0.1875 - 1e-9) shouldBe true
            }
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

    private fun line(length: Double) = listOf(Vector3d(), Vector3d(length, 0.0, 0.0))

    @Test
    fun `the tick budget caps both the cross-link chains and their nodes`() {
        val chains = VineBuildBudget(256, chainLimit = 2)
        chains.take(crossLinkNodes(line(0.4), 0.4)) shouldBe true
        chains.take(crossLinkNodes(line(0.4), 0.4)) shouldBe true
        chains.take(crossLinkNodes(line(0.4), 0.4)) shouldBe false
        val nodes = VineBuildBudget(22, chainLimit = 8)
        crossLinkNodes(line(4.0), 0.4) shouldBe 11
        nodes.take(crossLinkNodes(line(4.0), 0.4)) shouldBe true
        nodes.take(crossLinkNodes(line(4.0), 0.4)) shouldBe true
        nodes.take(crossLinkNodes(line(0.4), 0.4)) shouldBe false
        nodes.reset()
        nodes.take(crossLinkNodes(line(0.4), 0.4)) shouldBe true
    }

    @Test
    fun `one oversized cross-link still goes first in a tick, but nothing after it`() {
        val budget = VineBuildBudget(CAPTURE_LINK_NODES_PER_TICK, CAPTURE_LINKS_PER_TICK)
        budget.take(crossLinkNodes(line(400.0), 0.4)) shouldBe true
        budget.take(crossLinkNodes(line(0.4), 0.4)) shouldBe false
    }

    @Test
    fun `a refused cross-link waits, so every planned link is made once and in plan order`() {
        val dirs = captureDirections(16, java.util.Random(2L))
        val random = java.util.Random(2L)
        val plan = weavePlan(IntArray(16) { 120 }, spacings(16), weavePairs(dirs, random), random)
        val budget = VineBuildBudget(CAPTURE_LINK_NODES_PER_TICK, CAPTURE_LINKS_PER_TICK)
        val built = ArrayList<CrossLinkPlan>()
        var cursor = 0
        var ticks = 0
        while (cursor < plan.size && ticks < 1000) {
            budget.reset()
            ticks++
            while (cursor < plan.size && budget.take(crossLinkNodes(line(3.2), 0.4))) built += plan[cursor++]
        }
        built shouldBe plan
        (ticks >= plan.size / CAPTURE_LINKS_PER_TICK) shouldBe true
    }

    @Test
    fun `strand nodes are copied once a tick into kept buffers and clamped like a joint`() {
        val nodes = StrandNodes(2)
        nodes.isRead(0) shouldBe false
        val buffer = nodes.buffer(0, 2)
        doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0).copyInto(buffer)
        nodes.stored(0, 2)
        nodes.isRead(0) shouldBe true
        nodes.point(0, 0)!!.set(99.0)
        nodes.point(0, 0) shouldBe Vector3d(1.0, 2.0, 3.0)
        nodes.point(0, 20) shouldBe Vector3d(4.0, 5.0, 6.0)
        nodes.point(0, -1) shouldBe Vector3d(1.0, 2.0, 3.0)
        nodes.stored(1, 0)
        nodes.point(1, 0) shouldBe null
        (nodes.buffer(0, 1) === buffer) shouldBe true
        nodes.newTick()
        nodes.isRead(0) shouldBe false
        nodes.isRead(1) shouldBe false
    }
}
