package org.shipwrights.enderkinesis.vine

import java.util.concurrent.ThreadLocalRandom
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBic
import org.valkyrienskies.core.api.chains.ChainEnd
import org.valkyrienskies.core.api.chains.ChainEndpoint
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ChainReelControl
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Twice the speed of the first capture preview. */
internal const val CAPTURE_NODES_PER_SECOND = 24.0
/** Cross-links start this many nodes under each tip, then one every [CROSS_STEP] nodes (1.6 blocks). */
internal const val CROSS_FIRST = 3
internal const val CROSS_STEP = 4
internal const val MAX_CROSS_PER_PAIR = 16
private const val MIN_CROSS_GAP = 0.8
/** Over one particle diameter, so a new cross-link end does not overlap the strand node it ties to. */
private const val CROSS_END_GAP = 0.25
/** A cross-link of each pair for every [CROSS_STEP] nodes that a vine pays out, so the weave spreads at the vine speed. */
private val CROSS_INTERVAL_TICKS = (CROSS_STEP / CAPTURE_NODES_PER_SECOND / SECONDS_PER_TICK).roundToInt().coerceAtLeast(1)
private const val TIGHTEN_NODES_PER_SECOND = 4.0
/** Newtons: the reel takes in slack, but cannot pull down a ship that pulls away. */
private const val TIGHTEN_FORCE = 2000.0
/** Blocks: a vine launches when the vine before it in the ring has paid out this much. */
internal const val LAUNCH_LEAD = 4.0
/**
 * Newtons on the node next to the anchor while a strand pays out. Under about 1400 N the 1.95 kg node needs three
 * physics ticks to clear one particle diameter, and VS then pays out 20 nodes a second, not [CAPTURE_NODES_PER_SECOND].
 */
private const val CAPTURE_PAYOUT_FORCE = 2000.0
/** Share of the ship's half width, across the side that faces the circle, where the strands aim. */
private val HULL_AIM_FILLS = doubleArrayOf(0.7, 0.4, 0.15)
/** Two tip nodes nearer than this would push on each other while their pins hold them; VS then blows up. */
internal const val MIN_HULL_GAP = 0.6
/** Rope the tip pin leaves slack, so the pin never stretches the rope while the published length is a tick old. */
internal const val TIP_SLACK = 0.05
private const val MAX_TIGHTEN_TICKS = 400
private const val MAX_PENDING_TICKS = 40
/** Partner vines cross a block over the middle of their four ends. */
private const val CROSS_LIFT = 1.0
private const val WRITHE_MIN = 1.2
private const val WRITHE_SPREAD = 1.6
/** Partners pass this far apart at their crossing, so their nodes do not meet head on. */
private const val CROSS_SIDE_GAP = 0.2
private const val GUIDE_STEPS = 12
/** A node closer than this to a ship block grips it. */
private const val GRIP_REACH = PARTICLE_RADIUS + 0.05
private const val MAX_GRIPS = 64
private const val GRIP_CHECKS_PER_TICK = 8
private const val LENGTH_EPS = 1e-9

internal enum class CapturePhase { GROW, TIGHTEN, WEAVE, HOLD }

/** One vine from an anchor candle to its point on the hull. */
internal class CaptureStrand(
    val vineId: Long, val anchor: VinePoint, hull: VinePoint, var spacing: Double, val writhe: Double, val side: Double,
) {
    /** A latch aims again when the hull block is gone. */
    var hull = hull
    var target = 0.0
    var latched = false
    /** True when the vine stopped without a latch, or is gone. */
    var lost = false
    /** Pay-out base (whole spacings) and ticks since it; a load starts them again from the rebuilt chain. Not saved. */
    var payBase = 0.0
    var payTicks = 0
    /** Paid length as of the last grow step. Not saved: each grow step sets it again from the chain. */
    var paid = 0.0
    val anchorWorld = Vector3d()
    val hullWorld = Vector3d()

    fun save(): CompoundTag = CompoundTag().apply {
        putLong("vine", vineId)
        put("anchor", anchor.save())
        put("hull", hull.save())
        putDouble("spacing", spacing)
        putDouble("writhe", writhe)
        putDouble("side", side)
        putDouble("target", target)
        putBoolean("latched", latched)
        putBoolean("lost", lost)
    }

    companion object {
        fun load(tag: CompoundTag) = CaptureStrand(
            tag.getLong("vine"), VinePoint.load(tag.getCompound("anchor")), VinePoint.load(tag.getCompound("hull")),
            tag.getDouble("spacing"), tag.getDouble("writhe"), tag.getDouble("side"),
        ).apply {
            target = tag.getDouble("target")
            latched = tag.getBoolean("latched")
            lost = tag.getBoolean("lost") || !(spacing > 0.0 && spacing.isFinite())
        }
    }
}

/** One cross-link: node [nodeA] of strand [strandA] to node [nodeB] of strand [strandB]; [step] orders the wave from the tips down. */
internal data class CrossLinkPlan(val strandA: Int, val nodeA: Int, val strandB: Int, val nodeB: Int, val step: Int)

internal class VineCapture(val shipId: Long, val strands: List<CaptureStrand>) {
    var phase = CapturePhase.GROW
    var phaseTicks = 0
    var plan: List<CrossLinkPlan> = emptyList()
    var planned = false
    var planCursor = 0
    /** Cross-link vine ids. */
    val links = ArrayList<Long>()
    var gripCursor = 0
    var gripCount = 0
    /** Set by a load: the next steps wait for the rebuilt chains and start the reels again. Not saved. */
    var resync = false
    var resyncTicks = 0
    val scratch = Vector3d()

    fun save(): CompoundTag = CompoundTag().apply {
        putLong("ship", shipId)
        put("strands", ListTag().apply { strands.forEach { add(it.save()) } })
        putString("phase", phase.name)
        putInt("ticks", phaseTicks)
        putBoolean("planned", planned)
        putIntArray("plan", IntArray(plan.size * PLAN_INTS) { i ->
            val link = plan[i / PLAN_INTS]
            when (i % PLAN_INTS) { 0 -> link.strandA; 1 -> link.nodeA; 2 -> link.strandB; 3 -> link.nodeB; else -> link.step }
        })
        putInt("planCursor", planCursor)
        putLongArray("links", links.toLongArray())
        putInt("gripCursor", gripCursor)
        putInt("grips", gripCount)
    }

    companion object {
        private const val PLAN_INTS = 5

        /** Null when the tag holds no strand or a bad plan. */
        fun load(tag: CompoundTag): VineCapture? {
            val list = tag.getList("strands", Tag.TAG_COMPOUND.toInt())
            val strands = List(list.size) { CaptureStrand.load(list.getCompound(it)) }
            if (strands.isEmpty()) return null
            val flat = tag.getIntArray("plan")
            if (flat.size % PLAN_INTS != 0) return null
            val plan = List(flat.size / PLAN_INTS) { i ->
                val k = i * PLAN_INTS
                CrossLinkPlan(flat[k], flat[k + 1], flat[k + 2], flat[k + 3], flat[k + 4])
            }
            if (plan.any { it.strandA !in strands.indices || it.strandB !in strands.indices }) return null
            return VineCapture(tag.getLong("ship"), strands).apply {
                phase = CapturePhase.entries.firstOrNull { it.name == tag.getString("phase") } ?: CapturePhase.HOLD
                phaseTicks = tag.getInt("ticks")
                planned = tag.getBoolean("planned")
                this.plan = plan
                planCursor = tag.getInt("planCursor").coerceIn(0, plan.size)
                tag.getLongArray("links").forEach(links::add)
                gripCursor = tag.getInt("gripCursor")
                gripCount = tag.getInt("grips")
                resync = true
            }
        }
    }
}

/**
 * Cross-links between ring neighbours i and i + 1, the last with the first: link k leaves one vine [CROSS_FIRST] +
 * k * [CROSS_STEP] nodes under its tip and reaches the other [CROSS_STEP] nodes lower, the two vines in turn, so the
 * links zig-zag. [links] holds each vine's link count, or -1 for a vine that does not hold the ship. Nodes count from
 * the start (the anchor); the tip is node `links`.
 */
internal fun weavePlan(links: IntArray): List<CrossLinkPlan> {
    val n = links.size
    if (n < 2) return emptyList()
    val out = ArrayList<CrossLinkPlan>()
    val pairs = if (n == 2) 1 else n
    for (a in 0 until pairs) {
        val b = (a + 1) % n
        if (links[a] < 0 || links[b] < 0) continue
        for (k in 0 until MAX_CROSS_PER_PAIR) {
            val t0 = CROSS_FIRST + k * CROSS_STEP
            val t1 = t0 + CROSS_STEP
            val upper = if (k % 2 == 0) a else b
            val lower = if (k % 2 == 0) b else a
            if (links[upper] < t0 + 2 || links[lower] < t1 + 2) break
            out += CrossLinkPlan(upper, links[upper] - t0, lower, links[lower] - t1, k)
        }
    }
    return out.sortedBy { it.step }
}

/**
 * The guide point at [s] in 0..1 from anchor [a] to hull point [t]: a quadratic curve through the crossing point of
 * this vine and its partner ([pa], [pt]), with a sideways [writhe] that is zero at both ends and at the crossing.
 */
@Suppress("LongParameterList") // Two vines' ends and the vine's own shape.
internal fun captureGuide(
    a: Vector3dc, t: Vector3dc, pa: Vector3dc, pt: Vector3dc, writhe: Double, side: Double, s: Double, out: Vector3d,
): Vector3d {
    val kx = (a.x() + pa.x() + t.x() + pt.x()) * 0.25
    val ky = (a.y() + pa.y() + t.y() + pt.y()) * 0.25 + CROSS_LIFT
    val kz = (a.z() + pa.z() + t.z() + pt.z()) * 0.25
    val cx = 2 * kx - 0.5 * (a.x() + t.x())
    val cy = 2 * ky - 0.5 * (a.y() + t.y())
    val cz = 2 * kz - 0.5 * (a.z() + t.z())
    val u = 1 - s
    out.set(
        u * u * a.x() + 2 * u * s * cx + s * s * t.x(),
        u * u * a.y() + 2 * u * s * cy + s * s * t.y(),
        u * u * a.z() + 2 * u * s * cz + s * s * t.z(),
    )
    val dx = t.x() - a.x()
    val dz = t.z() - a.z()
    val l = sqrt(dx * dx + dz * dz)
    if (l > LENGTH_EPS) {
        val w = sin(2 * PI * s) * writhe + sin(PI * s) * side * CROSS_SIDE_GAP
        out.x += -dz / l * w
        out.z += dx / l * w
    }
    return out
}

@Suppress("LongParameterList")
internal fun captureGuideLength(a: Vector3dc, t: Vector3dc, pa: Vector3dc, pt: Vector3dc, writhe: Double, side: Double, scratch: Vector3d): Double {
    captureGuide(a, t, pa, pt, writhe, side, 0.0, scratch)
    var x = scratch.x; var y = scratch.y; var z = scratch.z
    var length = 0.0
    for (k in 1..GUIDE_STEPS) {
        captureGuide(a, t, pa, pt, writhe, side, k / GUIDE_STEPS.toDouble(), scratch)
        val dx = scratch.x - x; val dy = scratch.y - y; val dz = scratch.z - z
        length += sqrt(dx * dx + dy * dy + dz * dz)
        x = scratch.x; y = scratch.y; z = scratch.z
    }
    return length
}

/**
 * Ticks that a vine may pay out, counted from its launch: the time to pay out its [full] length twice. So a reel that
 * stalls stops, but a long vine to a far ship gets the time it needs.
 */
internal fun captureGrowTicks(full: Double, spacing: Double): Int =
    ceil(2 * full / spacing / (CAPTURE_NODES_PER_SECOND * SECONDS_PER_TICK)).toInt()

/** Reel target after [ticks] of pay-out: [base] plus whole spacings at [CAPTURE_NODES_PER_SECOND], never over [full]. */
internal fun capturePayTarget(ticks: Int, base: Double, spacing: Double, full: Double): Double {
    val nodes = floor(ticks * CAPTURE_NODES_PER_SECOND * SECONDS_PER_TICK + LENGTH_EPS)
    return min(full, base + nodes * spacing)
}

/** The tightened length for a straight [span]: whole spacings, not under the span (so the reel does not pull the ship), nor over [rest]. */
internal fun captureHoldTarget(span: Double, spacing: Double, rest: Double): Double =
    min(rest, max(2.0, ceil(span / spacing - LENGTH_EPS)) * spacing)

/**
 * Starts a capture: from each of [anchors], in ring order and one after another, a vine grows to the hull of ship
 * [shipId] and latches on. Each chain starts now as a short sprout. False when the ship is gone or no anchor sees its hull.
 */
@OptIn(GameTickOnly::class)
fun ServerLevel.startVineCapture(anchors: List<VinePoint>, shipId: Long): Boolean {
    val ship = shipObjectWorld.allShips.getById(shipId) ?: return false
    val candles = anchors.mapNotNull { anchor -> attachment(anchor)?.let { anchor to it.first } }
    val hulls = hullPoints(ship, candles.map { it.second })
    val middle = Vector3d()
    candles.forEach { middle.add(it.first.local) }
    if (candles.isNotEmpty()) middle.div(candles.size.toDouble())
    // Only anchors with a hull point grow a vine; partners pair along this list, as the tick does.
    val ready = candles.indices.mapNotNull { i ->
        val (candle, candleWorld) = candles[i]
        val hull = hulls[i] ?: return@mapNotNull null
        val hullWorld = ship.shipToWorld.transformPosition(Vector3d(hull.local))
        val toward = Vector3d(hullWorld).sub(candleWorld)
        candle.shipId?.let { id -> shipObjectWorld.allShips.getById(id)?.worldToShip?.transformDirection(toward) }
        val away = Vector3d(candle.local).sub(middle)
        val anchor = VinePoint(candle.block, besideCandle(candle.local, toward, away), candle.shipId)
        val (world, endpoint) = attachment(anchor) ?: return@mapNotNull null
        ReadyAnchor(anchor, world, endpoint, hull, hullWorld)
    }
    val random = ThreadLocalRandom.current()
    val strands = ArrayList<CaptureStrand>(ready.size)
    val scratch = Vector3d()
    for ((i, here) in ready.withIndex()) {
        val partner = ready[partnerOf(i, ready.size)]
        val side = if (partner === here) 0.0 else if (i % 2 == 0) 1.0 else -1.0
        val writhe = (if (random.nextBoolean()) -1.0 else 1.0) * (WRITHE_MIN + random.nextDouble() * WRITHE_SPREAD)
        val length = captureGuideLength(here.world, here.hullWorld, partner.world, partner.hullWorld, writhe, side, scratch)
        val dir = captureGuide(here.world, here.hullWorld, partner.world, partner.hullWorld, writhe, side, min(1.0, 1.0 / length), scratch)
            .sub(here.world)
        if (dir.lengthSquared() < LENGTH_EPS) dir.set(0.0, 1.0, 0.0) else dir.normalize()
        val seed = ReelSeed(here.world, dir)
        val chain = makeVineChain(seed.path, here.endpoint, ChainEndpoint.World(Vector3d(seed.path.last())), Vector3d(), seed.massPerLength)
        // A failed chain keeps its place in the list, so the partners stay as planned.
        val vine = chain?.let { WoggakVine(newVineId(), it.id, WoggakVineAnchor(here.anchor), WoggakVineAnchor(null)) }
        if (vine != null) addVine(vine, seed.path)
        strands += CaptureStrand(vine?.id ?: 0L, here.anchor, here.hull, seed.spacing, writhe, side).also {
            it.lost = vine == null
            it.target = seed.length
            it.payBase = seed.length
            it.anchorWorld.set(here.world)
            it.hullWorld.set(here.hullWorld)
        }
    }
    if (strands.all { it.lost }) return false
    woggakVines.captures += VineCapture(shipId, strands)
    return true
}

/** From the candle centre: candle shapes reach 0.19 out; a node needs 0.1 more, and must stay off the next block. */
private const val ANCHOR_OUT = 0.38

/**
 * The vine start beside a candle whose centre on the block under it is [candle]: on the side [toward] the hull, so the
 * rope leaves away from its candle, or [away] from the circle when the hull is straight above. Only x and z count.
 */
internal fun besideCandle(candle: Vector3dc, toward: Vector3dc, away: Vector3dc): Vector3d {
    var dx = toward.x(); var dz = toward.z()
    if (dx * dx + dz * dz < LENGTH_EPS) { dx = away.x(); dz = away.z() }
    val length = sqrt(dx * dx + dz * dz)
    if (length < LENGTH_EPS) { dx = 1.0; dz = 0.0 } else { dx /= length; dz /= length }
    return Vector3d(candle.x() + dx * ANCHOR_OUT, candle.y(), candle.z() + dz * ANCHOR_OUT)
}

private class ReadyAnchor(val anchor: VinePoint, val world: Vector3d, val endpoint: ChainEndpoint, val hull: VinePoint, val hullWorld: Vector3d)

/** Partners cross on the way up: 0 with 1, 2 with 3, and so on; an odd last vine has none. */
internal fun partnerOf(i: Int, n: Int): Int = if (i % 2 == 0) (if (i + 1 < n) i + 1 else i) else i - 1

/**
 * One hull point for each of [anchorsWorld], spread in ring order over the side of the ship that faces the circle, so the
 * net holds an area and no two tips meet. Computed once; null for an anchor with no free hull point.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.hullPoints(ship: ServerShip, anchorsWorld: List<Vector3dc>): List<VinePoint?> {
    val out = arrayOfNulls<VinePoint>(anchorsWorld.size)
    val box = ship.shipAABB ?: return out.toList()
    if (anchorsWorld.isEmpty()) return out.toList()
    val com = ship.transform.positionInShip
    val centre = Vector3d()
    anchorsWorld.forEach { centre.add(it) }
    ship.worldToShip.transformPosition(centre.div(anchorsWorld.size.toDouble()))
    val offsets = anchorsWorld.map { ship.worldToShip.transformPosition(Vector3d(it)).sub(centre) }
    val toCircle = Vector3d(centre).sub(com)
    val half = Vector3d((box.maxX() - box.minX() + 1) * 0.5, (box.maxY() - box.minY() + 1) * 0.5, (box.maxZ() - box.minZ() + 1) * 0.5)
    // A ship that has the circle inside its box gets the old aim: straight at its centre.
    if (toCircle.lengthSquared() < LENGTH_EPS) toCircle.set(0.0, -1.0, 0.0)
    val reach = half.length() + 1.0
    val dir = Vector3d(toCircle).normalize()
    for (fill in HULL_AIM_FILLS) {
        val aims = captureAims(com, toCircle, half, offsets, fill)
        for (i in out.indices) {
            if (out[i] != null) continue
            val aim = aims[i]
            val point = traceHull(ship, box, Vector3d(dir).mul(reach).add(aim), Vector3d(dir).mul(-reach).add(aim)) ?: continue
            if (out.any { it != null && it.local.distance(point.local) < MIN_HULL_GAP }) continue
            out[i] = point
        }
    }
    return out.toList()
}

/**
 * Aim points in the plane through [com] across [toCircle]: an ellipse at [fill] of the ship's half width there, one point
 * per anchor in ring order, turned and wound as the anchor [offsets] (from the circle centre) are seen from the ship.
 */
internal fun captureAims(com: Vector3dc, toCircle: Vector3dc, half: Vector3dc, offsets: List<Vector3dc>, fill: Double): List<Vector3d> {
    val n = offsets.size
    val d = Vector3d(toCircle).normalize()
    val u = (if (abs(d.y) < 0.9) Vector3d(0.0, 1.0, 0.0) else Vector3d(1.0, 0.0, 0.0)).cross(d).normalize()
    val v = Vector3d(d).cross(u)
    fun extent(a: Vector3d) = abs(a.x) * half.x() + abs(a.y) * half.y() + abs(a.z) * half.z()
    val eu = extent(u) * fill
    val ev = extent(v) * fill
    // Signed area of the anchors as the ship sees them: its sign keeps the winding, so neighbours stay neighbours.
    var area = 0.0
    for (i in 0 until n) {
        val a = offsets[i]; val b = offsets[(i + 1) % n]
        area += a.dot(u) * b.dot(v) - a.dot(v) * b.dot(u)
    }
    val wind = if (area < 0) -1.0 else 1.0
    val first = offsets.first()
    val start = if (abs(first.dot(u)) + abs(first.dot(v)) > LENGTH_EPS) atan2(first.dot(v), first.dot(u)) else 0.0
    return List(n) { i ->
        val phi = start + wind * 2 * PI * i / n
        Vector3d(com).add(u.x * eu * cos(phi) + v.x * ev * sin(phi), u.y * eu * cos(phi) + v.y * ev * sin(phi), u.z * eu * cos(phi) + v.z * ev * sin(phi))
    }
}

/**
 * Cuts the segment [from]..[to] to the box [min]..[max] in place; false when it misses the box. Keeps a hull trace in
 * the ship's own chunks, as a trace through empty shipyard loads chunks.
 */
internal fun clipToBox(from: Vector3d, to: Vector3d, boxMin: Vector3dc, boxMax: Vector3dc): Boolean {
    var t0 = 0.0
    var t1 = 1.0
    for (axis in 0..2) {
        val a = from.get(axis); val b = to.get(axis)
        val lo = boxMin.get(axis); val hi = boxMax.get(axis)
        val delta = b - a
        if (abs(delta) < LENGTH_EPS) {
            if (a < lo || a > hi) return false
            continue
        }
        var e0 = (lo - a) / delta
        var e1 = (hi - a) / delta
        if (e0 > e1) { val t = e0; e0 = e1; e1 = t }
        t0 = max(t0, e0)
        t1 = min(t1, e1)
        if (t0 > t1) return false
    }
    val sx = from.x; val sy = from.y; val sz = from.z
    from.set(sx + (to.x - sx) * t0, sy + (to.y - sy) * t0, sz + (to.z - sz) * t0)
    to.set(sx + (to.x - sx) * t1, sy + (to.y - sy) * t1, sz + (to.z - sz) * t1)
    return true
}

/** The first hull block from [from] to [to] (shipyard), as a ship-held vine point. Unloaded chunks are skipped, not loaded. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.traceHull(ship: ServerShip, box: AABBic, from: Vector3d, to: Vector3d): VinePoint? {
    val boxMin = Vector3d(box.minX() - 1.0, box.minY() - 1.0, box.minZ() - 1.0)
    val boxMax = Vector3d(box.maxX() + 2.0, box.maxY() + 2.0, box.maxZ() + 2.0)
    if (!clipToBox(from, to, boxMin, boxMax)) return null
    val fromV = Vec3(from.x, from.y, from.z)
    val toV = Vec3(to.x, to.y, to.z)
    // Shipyard frame only, so terrain on the way does not stop the ray.
    val hit: BlockHitResult = BlockGetter.traverseBlocks<BlockHitResult?, ServerLevel>(fromV, toV, this, { level, pos ->
        if (!level.isLoaded(pos)) null else level.getBlockState(pos).getCollisionShape(level, pos).clip(fromV, toV, pos)
    }, { null }) ?: return null
    // The traversal hands out one mutable pos.
    val pos = hit.blockPos.immutable()
    val snapped = snapToShapeAnchor(pos, hit.direction, hit.location)
    return VinePoint(pos, Vector3d(snapped.x, snapped.y, snapped.z), ship.id)
}

/** [strand]'s hull point again, on the line from the circle side, when its block is gone; false when none is left. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.reaimHull(ship: ServerShip, strand: CaptureStrand): Boolean {
    val block = strand.hull.block
    if (!isLoaded(block) || !getBlockState(block).getCollisionShape(this, block).isEmpty) return true
    val box = ship.shipAABB ?: return false
    val dir = ship.worldToShip.transformPosition(Vector3d(strand.anchorWorld)).sub(strand.hull.local)
    if (dir.lengthSquared() < LENGTH_EPS) return false
    dir.normalize()
    val reach = (box.maxX() - box.minX() + box.maxY() - box.minY() + box.maxZ() - box.minZ()) + 3.0
    val point = traceHull(ship, box, Vector3d(dir).mul(2.0).add(strand.hull.local), Vector3d(dir).mul(-reach).add(strand.hull.local))
        ?: return false
    strand.hull = point
    return true
}

@OptIn(GameTickOnly::class)
internal fun ServerLevel.tickVineCaptures(data: WoggakVineData) {
    if (data.captures.isEmpty()) return
    // The captures are saved with the level's vine data.
    data.setDirty()
    val iterator = data.captures.iterator()
    while (iterator.hasNext()) {
        val capture = iterator.next()
        if (stepCapture(data, capture)) continue
        LOG.warn("Woggak Vine capture of ship {} ended in phase {}: the ship or every vine on it is gone", capture.shipId, capture.phase)
        iterator.remove()
        data.jointsDirty = true
    }
}

/** False when the capture ends: the ship is gone, or no vine of it still holds the ship. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.stepCapture(data: WoggakVineData, capture: VineCapture): Boolean {
    val ship = shipObjectWorld.allShips.getById(capture.shipId)
    if (ship == null) {
        for (strand in capture.strands) if (!strand.latched) stopStrand(data, strand)
        return false
    }
    if (capture.resync && !resyncCapture(data, capture)) return true
    capture.phaseTicks++
    when (capture.phase) {
        CapturePhase.GROW -> if (growStrands(data, capture, ship)) capture.next(CapturePhase.TIGHTEN, startTighten(data, capture))
        CapturePhase.TIGHTEN -> capture.next(CapturePhase.WEAVE, tightened(data, capture))
        CapturePhase.WEAVE -> if (spawnLinks(data, capture)) capture.next(CapturePhase.HOLD, true)
        CapturePhase.HOLD -> Unit
    }
    if (capture.phase == CapturePhase.WEAVE || capture.phase == CapturePhase.HOLD) gripHull(data, capture, ship)
    for (strand in capture.strands) {
        if (strand.lost || !strand.latched || data.vine(strand.vineId)?.end?.isShipHeld == true) continue
        val status = data.vine(strand.vineId)?.let { shipObjectWorld.allChains.getById(it.chain)?.status }
        LOG.warn("Woggak Vine capture strand {} let go of the ship: end {}, broken links {}", strand.vineId, status?.endAttachment, status?.brokenLinks)
        strand.lost = true
    }
    return capture.strands.any { !it.lost }
}

/**
 * After a load: waits until each strand's chain is back and active (or the anchors are loaded and the wait cap ran
 * out), then takes the spacing from the rebuilt chain, as VS lays it out again, and starts the reels again from the
 * paid length. True when the capture can step.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.resyncCapture(data: WoggakVineData, capture: VineCapture): Boolean {
    var waiting = false
    var anchorsLoaded = true
    for (strand in capture.strands) {
        if (strand.lost) continue
        if (!isLoaded(strand.anchor.block)) anchorsLoaded = false
        val chain = data.vine(strand.vineId)?.let { shipObjectWorld.allChains.getById(it.chain) }
        if (chain?.status?.lifecycle != ChainLifecycle.ACTIVE) waiting = true
    }
    if (anchorsLoaded) capture.resyncTicks++
    if (waiting && capture.resyncTicks < MAX_PENDING_TICKS) return false
    for (strand in capture.strands) {
        val chain = strandChain(data, strand) ?: continue
        val status = chain.status
        val rest = status.currentRestLength
        if (status.lifecycle != ChainLifecycle.ACTIVE || status.nodeCount < 2 || !rest.isFinite() || rest <= 0.0) {
            stopStrand(data, strand)
            continue
        }
        strand.spacing = rest / (status.nodeCount - 1)
        strand.target = rest
        strand.payBase = rest
        strand.payTicks = 0
    }
    if (capture.phase == CapturePhase.TIGHTEN) startTighten(data, capture)
    // The time caps count from the restart.
    if (capture.phase == CapturePhase.GROW || capture.phase == CapturePhase.TIGHTEN) capture.phaseTicks = 0
    capture.resync = false
    return true
}

private fun VineCapture.next(phase: CapturePhase, go: Boolean) {
    if (!go) return
    this.phase = phase
    phaseTicks = 0
}

/** The live chain of [strand], or null when it is gone; a gone vine marks the strand lost. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.strandChain(data: WoggakVineData, strand: CaptureStrand): ServerChain? {
    if (strand.lost) return null
    val vine = data.vine(strand.vineId)
    val chain = if (vine == null || vine.fadeStart >= 0) null else shipObjectWorld.allChains.getById(vine.chain)
    if (chain == null) strand.lost = true
    return chain
}

/**
 * Pays out each growing strand one tick and moves its tip along its guide; true when none grows any more. The strands
 * launch in ring order, each one after [launchesNext] of the strand before it.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.growStrands(data: WoggakVineData, capture: VineCapture, ship: ServerShip): Boolean {
    val strands = capture.strands
    for (strand in strands) {
        // A lost partner keeps its last anchor point, so the guide does not jump.
        if (!strand.lost && !worldPoint(strand.anchor, strand.anchorWorld)) stopStrand(data, strand, "its anchor ship is gone")
        if (!strand.lost && !strand.latched && !reaimHull(ship, strand)) stopStrand(data, strand, "no hull block is left on its line")
        ship.shipToWorld.transformPosition(strand.hull.local, strand.hullWorld)
    }
    var growing = false
    for ((i, strand) in strands.withIndex()) {
        if (strand.latched || strand.lost) continue
        val chain = strandChain(data, strand) ?: continue
        val status = chain.status
        if (status.lifecycle == ChainLifecycle.PENDING) {
            if (capture.phaseTicks < MAX_PENDING_TICKS) growing = true else stopStrand(data, strand, "its chain did not start")
            continue
        }
        if (status.lifecycle != ChainLifecycle.ACTIVE || status.failure != null) {
            stopStrand(data, strand, status.failure ?: "its chain is ${status.lifecycle}")
            continue
        }
        val partner = strands[partnerOf(i, strands.size)]
        val length = captureGuideLength(
            strand.anchorWorld, strand.hullWorld, partner.anchorWorld, partner.hullWorld, strand.writhe, strand.side, capture.scratch,
        )
        val full = fullReelTarget(length + strand.spacing, strand.spacing)
        val paid = status.currentRestLength
        strand.paid = paid
        val stop = growStop(strand, paid, full)
        if (stop != null) {
            stopStrand(data, strand, stop)
            continue
        }
        if (paid >= full - strand.spacing * 1e-6) {
            latch(data, strand, chain)
            continue
        }
        growing = true
        // The strand before this one is stepped first in the loop, so its share is from this tick.
        if (i > 0 && !launchesNext(strands[i - 1])) continue
        val target = max(strand.target, capturePayTarget(++strand.payTicks, strand.payBase, strand.spacing, full))
        if (target > strand.target) {
            chain.setReelTarget(ChainReelControl(ChainEnd.START, target, throwReelRate(0.0, strand.spacing), CAPTURE_PAYOUT_FORCE))
            strand.target = target
        }
        val s = (paid / length).coerceIn(0.0, 1.0)
        val tip = captureGuide(strand.anchorWorld, strand.hullWorld, partner.anchorWorld, partner.hullWorld, strand.writhe, strand.side, s, Vector3d())
        clampReach(strand.anchorWorld, tip, captureTipReach(paid, strand.spacing))
        if (!isLoaded(BlockPos.containing(tip.x, tip.y, tip.z))) {
            stopStrand(data, strand, "its tip is not loaded")
            continue
        }
        chain.setEndpoint(ChainEnd.END, ChainEndpoint.World(tip))
    }
    return !growing
}

/** Why a growing [strand] must stop, or null: VS simulates only loaded chunks, and a stalled reel has a time cap. */
private fun ServerLevel.growStop(strand: CaptureStrand, paid: Double, full: Double): String? = when {
    !isLoaded(BlockPos.containing(strand.hullWorld.x, strand.hullWorld.y, strand.hullWorld.z)) -> "its hull point is not loaded"
    strand.payTicks >= captureGrowTicks(full, strand.spacing) ->
        "it paid out %.1f of %.1f blocks in %d ticks".format(paid, full, strand.payTicks)
    else -> null
}

/**
 * How far from the anchor the tip is pinned for [paid] rope: nearly all of it, so the rope stays taut and pulls each new
 * node off the anchor, as VS adds the next one only a diameter out; never past it, as two pins over the rope length blow up.
 */
internal fun captureTipReach(paid: Double, spacing: Double): Double = max(spacing * 0.5, paid - TIP_SLACK)

/**
 * Pulls [tip] toward [anchor] to at most [reach] away. The guide parameter is not arc length and the ship moves, so a
 * guide point can lie past the paid length; two pins further apart than the rope blow the solver up.
 */
internal fun clampReach(anchor: Vector3dc, tip: Vector3d, reach: Double): Vector3d {
    val d = tip.distance(anchor)
    if (d > reach && d > LENGTH_EPS) tip.sub(anchor).mul(reach / d).add(anchor)
    return tip
}

/** True when the vine after [strand] in the ring may launch: [strand] paid out [LAUNCH_LEAD], latched, or stopped. */
internal fun launchesNext(strand: CaptureStrand): Boolean = strand.lost || strand.latched || strand.paid >= LAUNCH_LEAD

/** The world point of [point]; false when its ship is gone. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.worldPoint(point: VinePoint, dest: Vector3d): Boolean {
    dest.set(point.local)
    val shipId = point.shipId ?: return true
    val ship = shipObjectWorld.allShips.getById(shipId) ?: return false
    ship.shipToWorld.transformPosition(dest)
    return true
}

@OptIn(GameTickOnly::class)
private fun ServerLevel.latch(data: WoggakVineData, strand: CaptureStrand, chain: ServerChain) {
    val vine = data.vine(strand.vineId) ?: return
    val endpoint = attachment(strand.hull)?.second
    if (endpoint == null) {
        stopStrand(data, strand, "its hull point has no ship body")
        return
    }
    chain.setEndpoint(ChainEnd.END, endpoint)
    vine.end = WoggakVineAnchor(strand.hull)
    strand.latched = true
    data.touch(vine)
    data.anchorsDirty = true
    data.jointsDirty = true
    data.setDirty()
}

/** Ends a strand's growth: the reel stops at the paid length and the tip hangs free. A [reason] goes to the log. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.stopStrand(data: WoggakVineData, strand: CaptureStrand, reason: String? = null) {
    if (reason != null && !strand.lost) LOG.warn("Woggak Vine capture strand {} stopped: {}", strand.vineId, reason)
    strand.lost = true
    val vine = data.vine(strand.vineId) ?: return
    val chain = shipObjectWorld.allChains.getById(vine.chain) ?: return
    val status = chain.status
    if (status.lifecycle != ChainLifecycle.ACTIVE) return
    // setTargetLength ends the reel, as at the end of a throw.
    if (status.currentRestLength.isFinite() && status.currentRestLength > 0) chain.setTargetLength(status.currentRestLength)
    chain.setEndpoint(ChainEnd.END, ChainEndpoint.Free)
    data.touch(vine)
    data.anchorsDirty = true
}

/** Sets each latched strand to reel in to its straight span. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.startTighten(data: WoggakVineData, capture: VineCapture): Boolean {
    for (strand in capture.strands) {
        if (!strand.latched) continue
        val chain = strandChain(data, strand) ?: continue
        val rest = chain.status.currentRestLength
        strand.target = rest
        if (!worldPoint(strand.anchor, strand.anchorWorld) || !worldPoint(strand.hull, strand.hullWorld)) continue
        val target = captureHoldTarget(strand.anchorWorld.distance(strand.hullWorld), strand.spacing, rest)
        if (target >= rest - strand.spacing * 0.5) continue
        chain.setReelTarget(ChainReelControl(ChainEnd.START, target, TIGHTEN_NODES_PER_SECOND * strand.spacing, TIGHTEN_FORCE))
        strand.target = target
    }
    return true
}

/** True when every reeling strand reached its target, or the time cap ran out; the reels then stop at the paid length. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.tightened(data: WoggakVineData, capture: VineCapture): Boolean {
    val timeUp = capture.phaseTicks >= MAX_TIGHTEN_TICKS
    var done = true
    for (strand in capture.strands) {
        if (!strand.latched) continue
        val chain = strandChain(data, strand) ?: continue
        val status = chain.status
        val rest = status.currentRestLength
        val short = rest <= strand.target + strand.spacing * 0.5
        // A ship that moved away keeps the rope taut over a longer span; the reel cannot take in more without a drag.
        val taut = worldPoint(strand.anchor, strand.anchorWorld) && worldPoint(strand.hull, strand.hullWorld) &&
            strand.anchorWorld.distance(strand.hullWorld) >= rest - strand.spacing
        if (!short && !taut && status.reel != null) done = false
    }
    if (!done && !timeUp) return false
    if (!done) LOG.warn("Woggak Vine capture of ship {}: a reel did not tighten in {} ticks; the weave starts", capture.shipId, MAX_TIGHTEN_TICKS)
    for (strand in capture.strands) {
        if (!strand.latched) continue
        val chain = strandChain(data, strand) ?: continue
        val rest = chain.status.currentRestLength
        if (chain.status.reel != null && rest.isFinite() && rest > 0) chain.setTargetLength(rest)
    }
    return true
}

/**
 * Plans the cross-links from the node counts of the held strands, once VS shows each reel ended, as the plan counts nodes
 * from the start. True when the plan is made.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.planWeave(data: WoggakVineData, capture: VineCapture): Boolean {
    val held = capture.strands.map { if (it.latched) strandChain(data, it) else null }
    if (capture.phaseTicks < MAX_PENDING_TICKS && held.any { it != null && it.status.reel != null }) return false
    val links = IntArray(held.size) { i ->
        val chain = held[i]
        if (chain == null || chain.status.lifecycle != ChainLifecycle.ACTIVE) -1 else chain.status.nodeCount - 1
    }
    capture.plan = weavePlan(links)
    val skipped = links.count { it < 0 }
    if (skipped > 0) LOG.warn("Woggak Vine capture of ship {}: {} of {} vines do not hold the ship; the weave skips them", capture.shipId, skipped, links.size)
    capture.planCursor = 0
    capture.planned = true
    capture.phaseTicks = 0
    return true
}

/** Makes the cross-links whose turn came; true when all are made. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.spawnLinks(data: WoggakVineData, capture: VineCapture): Boolean {
    if (!capture.planned && !planWeave(data, capture)) return false
    val plan = capture.plan
    while (capture.planCursor < plan.size && plan[capture.planCursor].step * CROSS_INTERVAL_TICKS <= capture.phaseTicks) {
        makeCrossLink(data, capture, plan[capture.planCursor++])
    }
    if (capture.planCursor < plan.size) return false
    if (capture.links.size < plan.size) {
        LOG.warn("Woggak Vine capture of ship {}: {} of {} cross-links could not be made", capture.shipId, plan.size - capture.links.size, plan.size)
    }
    return true
}

@OptIn(GameTickOnly::class)
private fun ServerLevel.makeCrossLink(data: WoggakVineData, capture: VineCapture, link: CrossLinkPlan) {
    val a = capture.strands[link.strandA]
    val b = capture.strands[link.strandB]
    val vineA = data.vine(a.vineId) ?: return
    val vineB = data.vine(b.vineId) ?: return
    val from = jointPosition(VineJoint(vineA.id, link.nodeA, vineA.home)) ?: return
    val to = jointPosition(VineJoint(vineB.id, link.nodeB, vineB.home)) ?: return
    if (from.distance(to) < MIN_CROSS_GAP + 2 * CROSS_END_GAP) return
    // The link's end nodes start clear of the strand nodes; on top of them, contacts push both apart until the joint masks them.
    val path = sagPath(Vector3d(to).sub(from).normalize().mul(CROSS_END_GAP).add(from), Vector3d(from).sub(to).normalize().mul(CROSS_END_GAP).add(to))
    val chain = makeVineChain(path, ChainEndpoint.Free, ChainEndpoint.Free) ?: return
    val start = VinePoint(BlockPos.containing(from.x, from.y, from.z), from, null, VineJoint(vineA.id, link.nodeA, vineA.home))
    val end = VinePoint(BlockPos.containing(to.x, to.y, to.z), to, null, VineJoint(vineB.id, link.nodeB, vineB.home))
    val vine = WoggakVine(newVineId(), chain.id, WoggakVineAnchor(start), WoggakVineAnchor(end))
    addVine(vine, path)
    capture.links += vine.id
}

/** Checks a few cross-links each tick for nodes on the hull; such a node grips the hull where it touches it, and the vine saves the grip. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.gripHull(data: WoggakVineData, capture: VineCapture, ship: ServerShip) {
    val links = capture.links
    if (links.isEmpty()) return
    val checks = min(GRIP_CHECKS_PER_TICK, links.size)
    for (c in 0 until checks) {
        if (capture.gripCount >= MAX_GRIPS) return
        capture.gripCursor = (capture.gripCursor + 1) % links.size
        val vine = data.vine(links[capture.gripCursor]) ?: continue
        if (vine.fadeStart >= 0) continue
        val chain = shipObjectWorld.allChains.getById(vine.chain) ?: continue
        val status = chain.status
        if (status.lifecycle != ChainLifecycle.ACTIVE) continue
        val nodes = status.nodeCount
        if (nodes < 3) continue
        if (data.scratch.size < nodes * 3) data.scratch = DoubleArray(nodes * 3)
        if (chain.copyNodePositions(data.scratch) != nodes) continue
        // The end nodes are tied to the strands; only inner nodes grip.
        for (k in 1 until nodes - 1) {
            if (vine.grips.any { it.node == k }) continue
            val local = capture.scratch.set(data.scratch[k * 3], data.scratch[k * 3 + 1], data.scratch[k * 3 + 2])
            ship.worldToShip.transformPosition(local)
            if (!touchesBlock(local, GRIP_REACH)) continue
            vine.grips = vine.grips + VineGrip(k, capture.shipId, Vector3d(local))
            vine.hullGrips = null
            capture.gripCount++
            data.touch(vine)
            data.setDirty()
            if (capture.gripCount >= MAX_GRIPS) return
        }
    }
}

/** True when a block collision box lies within [reach] of [p]; at most 8 blocks are read. */
private fun ServerLevel.touchesBlock(p: Vector3dc, reach: Double): Boolean {
    val cursor = BlockPos.MutableBlockPos()
    for (x in floor(p.x() - reach).toInt()..floor(p.x() + reach).toInt()) {
        for (y in floor(p.y() - reach).toInt()..floor(p.y() + reach).toInt()) {
            for (z in floor(p.z() - reach).toInt()..floor(p.z() + reach).toInt()) {
                cursor.set(x, y, z)
                val shape = getBlockState(cursor).getCollisionShape(this, cursor)
                if (shape.isEmpty) continue
                val box = shape.bounds()
                val dx = max(0.0, max(x + box.minX - p.x(), p.x() - (x + box.maxX)))
                val dy = max(0.0, max(y + box.minY - p.y(), p.y() - (y + box.maxY)))
                val dz = max(0.0, max(z + box.minZ - p.z(), p.z() - (z + box.maxZ)))
                if (dx * dx + dy * dy + dz * dz <= reach * reach) return true
            }
        }
    }
    return false
}
