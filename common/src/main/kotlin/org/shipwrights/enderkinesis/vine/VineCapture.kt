package org.shipwrights.enderkinesis.vine

import java.util.Random
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4dc
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBic
import org.valkyrienskies.core.api.chains.ChainEnd
import org.valkyrienskies.core.api.chains.ChainEndpoint
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ChainReelControl
import org.valkyrienskies.core.api.chains.ChainStatus
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Twice the speed of the first capture preview, for a vine of [FIRST_LINK] spacing; wider vines pay out the same blocks a second. */
internal const val CAPTURE_NODES_PER_SECOND = 24.0
private const val TIGHTEN_NODES_PER_SECOND = 4.0
/** Mean drop of one cross-link down the pair: twice the first capture weave's 1.6 blocks, for a softer weave. */
internal const val CROSS_STEP_BLOCKS = 3.2
internal const val MAX_CROSS_PER_PAIR = 4
/** Random ranges (blocks) so the weave reads grown, not a ladder: first tie under the tip, drop along a link, gap to the next. */
internal const val CROSS_FIRST_MIN = 0.6
internal const val CROSS_FIRST_MAX = 2.4
internal const val CROSS_DROP_MIN = 1.6
internal const val CROSS_DROP_MAX = 4.8
internal const val CROSS_GAP_MAX = 1.0
internal const val CROSS_SKIP_CHANCE = 0.15
/** Chance that the next link leaves the same strand again, so the links do not always zig-zag. */
internal const val CROSS_SAME_SIDE_CHANCE = 0.3
/** Sag as a fraction of the span; 0.10 to 0.24 gives about 3 % to 15 % more rope than the straight line. */
internal const val CROSS_SAG_MIN = 0.10
internal const val CROSS_SAG_MAX = 0.24
/** Cross-link chains and their nodes that all captures together may make in one tick; the rest wait for the next tick. */
internal const val CAPTURE_LINKS_PER_TICK = 8
internal const val CAPTURE_LINK_NODES_PER_TICK = 256
/** Each strand weaves to some of this many strands nearest it by direction; see [weaveTieChance]. */
internal const val WEAVE_REACH = 3
private const val MIN_CROSS_GAP = 0.8
/** Over one particle diameter, so a new cross-link end does not overlap the strand node it ties to. */
private const val CROSS_END_GAP = 0.25
/** A cross-link of each pair for every [CROSS_STEP_BLOCKS] that a strand pays out, so the weave spreads at the vine speed. */
private val CROSS_INTERVAL_TICKS =
    (CROSS_STEP_BLOCKS / (CAPTURE_NODES_PER_SECOND * FIRST_LINK) / SECONDS_PER_TICK).roundToInt().coerceAtLeast(1)
/** Blocks: a vine launches when the vine before it in the launch order has paid out this much. */
internal const val LAUNCH_LEAD = 4.0
/**
 * Newtons on the node next to the anchor while a standard strand pays out. Under about 1400 N the 1.95 kg node needs
 * three physics ticks to clear one particle diameter, and VS then pays out 20 nodes a second, not [CAPTURE_NODES_PER_SECOND].
 */
private const val CAPTURE_PAYOUT_FORCE = 2000.0
internal const val STRANDS_PER_ANCHOR = 2
/** Strand widths in whole texture pixels, so each rope face shows its texture column at a whole-pixel size. */
internal const val MIN_STRAND_PX = 3
internal const val MAX_STRAND_PX = 6
/** The ellipsoid round a box of half sizes h with semi-axes h * this holds the box's corners. */
private val WRAP_SCALE = sqrt(3.0)
/** Blocks of air between the wrap ellipsoid and the hull box's corners. */
private const val WRAP_CLEARANCE = 1.0
/** Blocks between the hull box and a strand's target point. */
private const val TARGET_CLEARANCE = 1.5
private const val ARC_STEPS = 24
/** The anchor, the arc start, the arc points, the target and the hull point. */
private const val ROUTE_POINTS = ARC_STEPS + 4
/** Turns of a strand's direction to try when the line in from its target meets no free hull block. */
private const val MAX_NUDGES = 6
private const val NUDGE_STEP = 10.0 * PI / 180
/** Cells the nearest-hull-block search may look at; a hard cap for a very large ship. */
private const val MAX_SCAN_STEPS = 1 shl 20
private val GOLDEN_ANGLE = PI * (3.0 - sqrt(5.0))
/** The two strands of one candle leave it this far to each side of the line to the hull. */
private const val ANCHOR_SPLAY = PI / 4
/** Two tip nodes nearer than this would push on each other while their pins hold them; VS then blows up. */
internal const val MIN_HULL_GAP = 0.6
/** Rope the tip pin leaves slack, so the pin never stretches the rope while the published length is a tick old. */
internal const val TIP_SLACK = 0.05
private const val MAX_TIGHTEN_TICKS = 400
private const val MAX_PENDING_TICKS = 40
private const val WRITHE_MIN = 1.2
private const val WRITHE_SPREAD = 1.6
/** Ticks a reel must take in under [REEL_STALL_DROP] spacings a tick before its strand counts as snug on the hull. */
private const val REEL_STALL_TICKS = 10
private const val REEL_STALL_DROP = 0.02
private const val LENGTH_EPS = 1e-9

/** The weave ties the strands while they hang at their targets, before LATCH takes the tips in to the hull. */
internal enum class CapturePhase { GROW, WEAVE, LATCH, TIGHTEN, HOLD }

internal val CapturePhase.following: CapturePhase get() = CapturePhase.entries[min(ordinal + 1, CapturePhase.entries.size - 1)]

/**
 * Where a strand goes, in shipyard space: round the ellipsoid of semi-axes [radii] about [centre] that holds the hull
 * box, out to its target [centre] + [dir] * [reach], then in along -[dir] to the hull.
 */
internal class CaptureAim(val centre: Vector3dc, val radii: Vector3dc, val dir: Vector3dc, val reach: Double) {
    fun target(dest: Vector3d): Vector3d = dest.set(dir).mul(reach).add(centre)

    fun save(): CompoundTag = CompoundTag().apply {
        putVector("c", centre); putVector("r", radii); putVector("d", dir); putDouble("reach", reach)
    }

    companion object {
        /** Null when a value is not finite, a radius or the reach is not positive, or the direction is not a unit vector. */
        fun load(tag: CompoundTag): CaptureAim? {
            val aim = CaptureAim(tag.getVector("c"), tag.getVector("r"), tag.getVector("d"), tag.getDouble("reach"))
            val r = aim.radii
            val ok = aim.centre.isFinite && r.isFinite && r.x() > 0 && r.y() > 0 && r.z() > 0 &&
                abs(aim.dir.length() - 1.0) < 1e-6 && aim.reach > 0 && aim.reach.isFinite()
            return aim.takeIf { ok }
        }
    }
}

/** The aim along unit [dir] round a hull box of [half] sizes about [centre]; its target clears the box by [TARGET_CLEARANCE]. */
internal fun captureAim(centre: Vector3dc, half: Vector3dc, dir: Vector3dc): CaptureAim {
    val radii = Vector3d(half).mul(WRAP_SCALE).add(WRAP_CLEARANCE, WRAP_CLEARANCE, WRAP_CLEARANCE)
    return CaptureAim(Vector3d(centre), radii, Vector3d(dir), extent(dir, half) + TARGET_CLEARANCE)
}

/** The line a tip pulls in on: from the target of [aim] along -dir, through the centre and on to the far side. */
internal fun inwardLine(aim: CaptureAim, from: Vector3d, to: Vector3d) {
    aim.target(from)
    to.set(aim.dir).mul(-aim.reach).add(aim.centre)
}

/**
 * A strand's route in shipyard space, built again each tick as the anchor moves: from the anchor on the tangent to the
 * aim's ellipsoid, round it on a great arc to the point over the target, to the target, then in to the hull point. The
 * ellipsoid holds the hull box, so the route goes round the hull for any direction.
 */
internal class CaptureRoute {
    private val points = Array(ROUTE_POINTS) { Vector3d() }
    private var count = 0
    /** Normal of the route plane; a writhe along it moves a point away from the ellipsoid, never into it. */
    private val across = Vector3d()
    private val a = Vector3d()
    private val u = Vector3d()
    private val w = Vector3d()
    private val p = Vector3d()
    var length = 0.0
        private set
    private var lengthToTarget = 0.0
    private var lengthToArcEnd = 0.0
    /** The share of [length] from the anchor to the target. */
    val targetShare get() = if (length > LENGTH_EPS) lengthToTarget / length else 1.0

    fun build(anchor: Vector3dc, hull: Vector3dc, aim: CaptureAim): CaptureRoute {
        val c = aim.centre
        val r = aim.radii
        // In the ellipsoid's own scale it is the unit sphere: tangents and great arcs map back to tangents and arcs on it.
        a.set(anchor).sub(c).div(r.x(), r.y(), r.z())
        u.set(aim.dir).div(r.x(), r.y(), r.z()).normalize()
        val dist = a.length()
        if (dist > LENGTH_EPS) a.div(dist) else a.set(u)
        val cosTheta = a.dot(u).coerceIn(-1.0, 1.0)
        val theta = acos(cosTheta)
        w.set(u).fma(-cosTheta, a)
        if (w.lengthSquared() < LENGTH_EPS) perpendicular(a, w)
        w.normalize()
        across.set(a).cross(w).mul(r).normalize()
        count = 0
        add(anchor)
        // An anchor inside the ellipsoid goes straight out from the centre first.
        val start = if (dist > 1.0) acos(1.0 / dist) else 0.0
        if (theta > start) {
            for (k in 0..ARC_STEPS) {
                val phi = start + (theta - start) * k / ARC_STEPS
                if (k == ARC_STEPS) p.set(u) else p.set(a).mul(cos(phi)).fma(sin(phi), w)
                add(p.mul(r).add(c))
            }
        } else {
            add(p.set(u).mul(r).add(c))
        }
        val arcEnd = count - 1
        add(aim.target(p))
        add(hull)
        length = 0.0
        for (k in 1 until count) {
            length += points[k].distance(points[k - 1])
            if (k == arcEnd) lengthToArcEnd = length
            if (k == arcEnd + 1) lengthToTarget = length
        }
        return this
    }

    private fun add(v: Vector3dc) {
        points[count++].set(v)
    }

    /**
     * The guide point [s] (0..1) along the route, with a [writhe] across its plane that is zero at the anchor and from
     * the end of the arc on, so the tip comes in to the hull on its straight line.
     */
    fun guide(s: Double, writhe: Double, out: Vector3d): Vector3d {
        val along = s.coerceIn(0.0, 1.0) * length
        var left = along
        out.set(points[count - 1])
        for (k in 1 until count) {
            val from = points[k - 1]
            val segment = from.distance(points[k])
            if (left <= segment) {
                from.lerp(points[k], if (segment > LENGTH_EPS) left / segment else 1.0, out)
                break
            }
            left -= segment
        }
        if (along < lengthToArcEnd) out.fma(sin(2 * PI * along / lengthToArcEnd) * writhe, across)
        return out
    }
}

/** A unit vector at right angles to the unit [v], into [dest]. */
private fun perpendicular(v: Vector3dc, dest: Vector3d): Vector3d =
    (if (abs(v.y()) < 0.9) dest.set(0.0, 1.0, 0.0) else dest.set(1.0, 0.0, 0.0)).cross(v).normalize()

/**
 * [n] unit directions in ship space, one for each strand: a Fibonacci lattice turned by a random rotation and given
 * out in a random order, so each is random but together they cover the sphere evenly.
 */
internal fun captureDirections(n: Int, random: Random): MutableList<Vector3d> {
    require(n >= 0) { "n $n" }
    val turn = Quaterniond(random.nextGaussian(), random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize()
    val dirs = MutableList(n) { i ->
        val z = 1.0 - 2.0 * (i + 0.5) / n
        val r = sqrt(max(0.0, 1.0 - z * z))
        val phi = i * GOLDEN_ANGLE
        turn.transform(Vector3d(r * cos(phi), r * sin(phi), z)).normalize()
    }
    java.util.Collections.shuffle(dirs, random)
    return dirs
}

/** [dir] turned [angle] radians about a random axis at right angles to it. */
internal fun nudgedDirection(dir: Vector3dc, angle: Double, random: Random): Vector3d {
    val axis = Vector3d(random.nextGaussian(), random.nextGaussian(), random.nextGaussian())
    axis.fma(-axis.dot(dir), dir)
    if (axis.lengthSquared() < LENGTH_EPS) perpendicular(dir, axis) else axis.normalize()
    return Vector3d(dir).mul(cos(angle)).fma(sin(angle), axis).normalize()
}

/**
 * The solid cell of the box [boxMin]..[boxMax] (both inclusive) nearest [target] that [accept] takes, or null. It looks
 * in cube shells round the box cell nearest [target], at most [MAX_SCAN_STEPS] cells.
 */
internal fun nearestSolidCell(
    boxMin: BlockPos, boxMax: BlockPos, target: Vector3dc, solid: (BlockPos) -> Boolean, accept: (BlockPos) -> Boolean = { true },
): BlockPos? {
    val sx = floor(target.x()).toInt().coerceIn(boxMin.x, boxMax.x)
    val sy = floor(target.y()).toInt().coerceIn(boxMin.y, boxMax.y)
    val sz = floor(target.z()).toInt().coerceIn(boxMin.z, boxMax.z)
    val offset = sqrt(sq(target.x() - sx - 0.5) + sq(target.y() - sy - 0.5) + sq(target.z() - sz - 0.5))
    val maxShell = max(max(boxMax.x - boxMin.x, boxMax.y - boxMin.y), boxMax.z - boxMin.z)
    val pos = BlockPos.MutableBlockPos()
    var best: BlockPos? = null
    var bestSq = Double.MAX_VALUE
    var steps = 0
    for (shell in 0..maxShell) {
        // Each cell of this shell is at least shell - offset from the target.
        if (best != null && shell - offset > sqrt(bestSq)) break
        for (dx in max(-shell, boxMin.x - sx)..min(shell, boxMax.x - sx)) {
            for (dy in max(-shell, boxMin.y - sy)..min(shell, boxMax.y - sy)) {
                val face = abs(dx) == shell || abs(dy) == shell
                var dz = -shell
                while (dz <= shell) {
                    if (++steps > MAX_SCAN_STEPS) return best
                    val z = sz + dz
                    dz += if (face || shell == 0) 1 else 2 * shell
                    if (z !in boxMin.z..boxMax.z || !solid(pos.set(sx + dx, sy + dy, z))) continue
                    val d = sq(sx + dx + 0.5 - target.x()) + sq(sy + dy + 0.5 - target.y()) + sq(z + 0.5 - target.z())
                    if (d < bestSq && accept(pos)) {
                        best = pos.immutable()
                        bestSq = d
                    }
                }
            }
        }
    }
    return best
}

private fun CompoundTag.putVector(key: String, v: Vector3dc) {
    putDouble("${key}x", v.x()); putDouble("${key}y", v.y()); putDouble("${key}z", v.z())
}

private fun CompoundTag.getVector(key: String) = Vector3d(getDouble("${key}x"), getDouble("${key}y"), getDouble("${key}z"))

/** One vine from an anchor candle round the hull to its latch point. */
internal class CaptureStrand(
    val vineId: Long, val anchor: VinePoint, hull: VinePoint, var spacing: Double, val writhe: Double,
    val width: Double, val aim: CaptureAim,
) {
    /** A latch aims again when the hull block is gone. */
    var hull = hull
    var target = 0.0
    var latched = false
    /** True when the vine stopped without a latch, or is gone. */
    var lost = false
    /** The pay-out stopped: the tip hangs at [hold] until LATCH. [capped]: it stopped at the grow cap, not at its target. */
    var parked = false
    var capped = false
    /** Shipyard point where the tip is pinned. */
    val hold = Vector3d()
    /** Chain id and node count at the last [payoutShift] count. Not saved: a load counts again. */
    var countedChain = 0L
    var countedNodes = 0
    /** Hangs free of the hull, so the weave may tie it. */
    val hangs get() = parked && !latched && !lost
    /** Pay-out base (whole spacings) and ticks since it; a load starts them again from the rebuilt chain. Not saved. */
    var payBase = 0.0
    var payTicks = 0
    /** Paid length as of the last grow step. Not saved: each grow step sets it again from the chain. */
    var paid = 0.0
    /** Rest length at the last tighten step and the steps since it dropped. Not saved: a load starts the reels again. */
    var lastRest = Double.NaN
    var stillTicks = 0
    val anchorWorld = Vector3d()
    val hullWorld = Vector3d()

    fun save(): CompoundTag = CompoundTag().apply {
        putLong("vine", vineId)
        put("anchor", anchor.save())
        put("hull", hull.save())
        putDouble("spacing", spacing)
        putDouble("writhe", writhe)
        putDouble("width", width)
        put("aim", aim.save())
        putDouble("target", target)
        putBoolean("latched", latched)
        putBoolean("lost", lost)
        putBoolean("parked", parked)
        putBoolean("capped", capped)
        putVector("hold", hold)
    }

    companion object {
        /** Null when the tag holds no valid width or aim. */
        fun load(tag: CompoundTag): CaptureStrand? {
            val width = tag.getDouble("width")
            val aim = CaptureAim.load(tag.getCompound("aim"))
            if (!validVineWidth(width) || aim == null) return null
            return CaptureStrand(
                tag.getLong("vine"), VinePoint.load(tag.getCompound("anchor")), VinePoint.load(tag.getCompound("hull")),
                tag.getDouble("spacing"), tag.getDouble("writhe"), width, aim,
            ).apply {
                target = tag.getDouble("target")
                latched = tag.getBoolean("latched")
                lost = tag.getBoolean("lost") || !(spacing > 0.0 && spacing.isFinite())
                parked = tag.getBoolean("parked")
                capped = tag.getBoolean("capped")
                hold.set(tag.getVector("hold"))
                if (!hold.isFinite) lost = true
            }
        }
    }
}

/** One cross-link: node [nodeA] of strand [strandA] to node [nodeB] of strand [strandB]; [step] orders the wave from the tips down. */
internal data class CrossLinkPlan(val strandA: Int, val nodeA: Int, val strandB: Int, val nodeB: Int, val step: Int)

/** [strands] are in launch order. */
internal class VineCapture(val shipId: Long, val strands: List<CaptureStrand>) {
    var phase = CapturePhase.GROW
    var phaseTicks = 0
    var plan: List<CrossLinkPlan> = emptyList()
    var planned = false
    var planCursor = 0
    /** Cross-link vine ids. */
    val links = ArrayList<Long>()
    /** Set by a load: the next steps wait for the rebuilt chains and start the reels again. Not saved. */
    var resync = false
    var resyncTicks = 0
    /** Ticks LATCH waited for the cap-latch reels after every strand latched. Not saved. */
    var settleTicks = 0
    val route = CaptureRoute()
    val anchorShip = Vector3d()
    val nodes = StrandNodes(strands.size)

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
    }

    companion object {
        private const val PLAN_INTS = 5

        /** Null when the tag holds no valid strand or a bad plan. */
        fun load(tag: CompoundTag): VineCapture? {
            val list = tag.getList("strands", Tag.TAG_COMPOUND.toInt())
            val strands = List(list.size) { CaptureStrand.load(list.getCompound(it)) ?: return null }
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
                resync = true
            }
        }
    }
}

private fun sq(d: Double) = d * d

/** Chance that a strand ties to its nearest strand of [rank] (0 = nearest), so the neighbour pattern differs strand to strand. */
internal fun weaveTieChance(rank: Int): Double = when (rank) {
    0 -> 1.0
    1 -> 0.75
    else -> 0.2
}

/** Each strand with some of its [WEAVE_REACH] nearest strands by direction, as index pairs (lower first), each pair once. */
internal fun weavePairs(dirs: List<Vector3dc>, random: Random): List<Pair<Int, Int>> {
    val pairs = sortedSetOf(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
    for (i in dirs.indices) {
        val nearest = dirs.indices.filter { it != i }.sortedBy { dirs[i].angle(dirs[it]) }.take(WEAVE_REACH)
        for ((rank, j) in nearest.withIndex()) if (random.nextDouble() < weaveTieChance(rank)) pairs += min(i, j) to max(i, j)
    }
    return pairs.toList()
}

private fun Random.between(lo: Double, hi: Double) = lo + nextDouble() * (hi - lo)

/**
 * Cross-links for each of [pairs], at most [MAX_CROSS_PER_PAIR]: each leaves one strand at a random depth under its
 * tip and reaches the other a random drop lower, so it runs at a slant. Each next link starts a random gap under the
 * last end; some leave the same strand again, some are skipped. [links] holds each strand's link count, or -1 for a
 * strand that does not hold the ship; [spacings] its link length. Nodes count from the anchor; the tip is node `links`.
 */
internal fun weavePlan(links: IntArray, spacings: DoubleArray, pairs: List<Pair<Int, Int>>, random: Random): List<CrossLinkPlan> {
    require(links.size == spacings.size) { "links ${links.size}, spacings ${spacings.size}" }
    val out = ArrayList<CrossLinkPlan>()
    for ((a, b) in pairs) {
        if (a !in links.indices || b !in links.indices || links[a] < 0 || links[b] < 0) continue
        if (!(spacings[a] > 0.0 && spacings[b] > 0.0)) continue
        var upper = if (random.nextBoolean()) a else b
        var along = random.between(CROSS_FIRST_MIN, CROSS_FIRST_MAX)
        for (k in 0 until MAX_CROSS_PER_PAIR) {
            val lower = if (upper == a) b else a
            val drop = random.between(CROSS_DROP_MIN, CROSS_DROP_MAX)
            val skip = random.nextDouble() < CROSS_SKIP_CHANCE
            val t0 = (along / spacings[upper]).roundToInt()
            val t1 = ((along + drop) / spacings[lower]).roundToInt()
            if (links[upper] < t0 + 2 || links[lower] < t1 + 2) break
            if (!skip) out += CrossLinkPlan(upper, links[upper] - t0, lower, links[lower] - t1, k)
            along += drop + random.nextDouble() * CROSS_GAP_MAX
            if (random.nextDouble() >= CROSS_SAME_SIDE_CHANCE) upper = lower
        }
    }
    return out.sortedBy { it.step }
}

/** A mix of the capture and the link's fields: the same link gets the same width and sag after a load. */
internal fun crossLinkHash(shipId: Long, link: CrossLinkPlan): Long {
    var h = shipId * SEED_MIX
    h = (h xor link.strandA.toLong()) * SEED_MIX
    h = (h xor link.nodeA.toLong()) * SEED_MIX
    h = (h xor link.strandB.toLong()) * SEED_MIX
    h = (h xor link.nodeB.toLong()) * SEED_MIX
    return h xor (h ushr 31)
}

/** Whole pixels from [MIN_STRAND_PX] up to the thinner strand, so a tie is never heavier than what it ties. */
internal fun crossLinkWidth(hash: Long, thinner: Double): Double {
    val maxPx = max(MIN_STRAND_PX, (thinner * 16).roundToInt())
    return (MIN_STRAND_PX + Math.floorMod(hash ushr 40, maxPx - MIN_STRAND_PX + 1)) / 16.0
}

internal fun crossLinkSag(hash: Long): Double =
    CROSS_SAG_MIN + (hash ushr 11).toDouble() / (1L shl 53) * (CROSS_SAG_MAX - CROSS_SAG_MIN)

/** Pay-out nodes a second for a vine of [spacing]: the same blocks a second as at [FIRST_LINK]. */
internal fun captureNodeRate(spacing: Double): Double = CAPTURE_NODES_PER_SECOND * FIRST_LINK / spacing

/**
 * [CAPTURE_PAYOUT_FORCE], grown with the node mass, the particle diameter to clear and the square of the node rate, so
 * a wide vine clears the anchor as fast as the standard one. Never less.
 */
internal fun capturePayoutForce(width: Double, spacing: Double): Double {
    val rate = captureNodeRate(spacing) / CAPTURE_NODES_PER_SECOND
    val mass = vineMassPerLength(width) * spacing / (MASS_PER_LENGTH * FIRST_LINK)
    val clear = vineParticleRadius(width) / PARTICLE_RADIUS
    return CAPTURE_PAYOUT_FORCE * max(1.0, mass * clear * rate * rate)
}

/**
 * Ticks that a vine may pay out, counted from its launch: the time to pay out its [full] length twice. So a reel that
 * stalls stops, but a long vine to a far ship gets the time it needs.
 */
internal fun captureGrowTicks(full: Double, spacing: Double, nodesPerSecond: Double = CAPTURE_NODES_PER_SECOND): Int =
    ceil(2 * full / spacing / (nodesPerSecond * SECONDS_PER_TICK)).toInt()

/** Reel target after [ticks] of pay-out: [base] plus whole spacings at [nodesPerSecond], never over [full]. */
internal fun capturePayTarget(
    ticks: Int, base: Double, spacing: Double, full: Double, nodesPerSecond: Double = CAPTURE_NODES_PER_SECOND,
): Double {
    val nodes = floor(ticks * nodesPerSecond * SECONDS_PER_TICK + LENGTH_EPS)
    return min(full, base + nodes * spacing)
}

/** True when a reel took in under [REEL_STALL_DROP] spacings this step; the rope then lies taut on the hull. */
internal fun reelStalled(lastRest: Double, rest: Double, spacing: Double): Boolean =
    lastRest.isFinite() && lastRest - rest < REEL_STALL_DROP * spacing

/** Strand width in blocks: whole pixels from [MIN_STRAND_PX] to [MAX_STRAND_PX]. */
internal fun captureStrandWidth(random: Random): Double =
    (MIN_STRAND_PX + random.nextInt(MAX_STRAND_PX - MIN_STRAND_PX + 1)) / 16.0

/** The same rite (ship and anchors) gets the same widths, directions and launch order. */
internal fun riteSeed(shipId: Long, anchors: List<VinePoint>): Long {
    var h = shipId * SEED_MIX
    for (anchor in anchors) h = (h xor anchor.block.asLong()) * SEED_MIX
    return h
}

private const val SEED_MIX = -0x61c8864680b583ebL

private class Aimed(val anchor: Int, val aim: CaptureAim, val hull: VinePoint, val width: Double)

private class ReadyStrand(val anchor: VinePoint, val world: Vector3d, val endpoint: ChainEndpoint, val aimed: Aimed)

/**
 * Starts a capture: from each of [anchors], [STRANDS_PER_ANCHOR] vines of mixed widths grow round the hull of ship
 * [shipId], each out to its own random side, one after another in a mixed order. The weave ties them while they hang
 * there; then each pulls in to latch on the hull. Each chain starts now as a short sprout.
 * False when the ship is gone or no anchor gets a hull point.
 */
@OptIn(GameTickOnly::class)
fun ServerLevel.startVineCapture(anchors: List<VinePoint>, shipId: Long): Boolean {
    val ship = shipObjectWorld.allShips.getById(shipId) ?: return false
    val candles = anchors.mapNotNull { anchor -> attachment(anchor)?.let { anchor to it.first } }
    if (candles.isEmpty()) return false
    val random = Random(riteSeed(shipId, candles.map { it.first }))
    val ready = readyStrands(ship, candles, aimStrands(ship, candles.size * STRANDS_PER_ANCHOR, random))
    java.util.Collections.shuffle(ready, random)
    val route = CaptureRoute()
    val strands = ready.map { sproutStrand(ship, it, random, route) }
    if (strands.isEmpty() || strands.all { it.lost }) return false
    woggakVines.captures += VineCapture(shipId, strands)
    return true
}

/** The start beside its candle of each aimed strand; the two strands of a candle leave it to each side. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.readyStrands(ship: ServerShip, candles: List<Pair<VinePoint, Vector3d>>, aimed: List<Aimed>): MutableList<ReadyStrand> {
    val middle = Vector3d()
    candles.forEach { middle.add(it.first.local) }
    middle.div(candles.size.toDouble())
    val ready = ArrayList<ReadyStrand>(aimed.size)
    for ((i, candlePair) in candles.withIndex()) {
        val (candle, candleWorld) = candlePair
        val mine = aimed.filter { it.anchor == i }
        val toward = ship.shipToWorld.transformPosition(Vector3d(mine.firstOrNull()?.aim?.centre ?: continue)).sub(candleWorld)
        val candleShip = candle.shipId?.let { id -> shipObjectWorld.allShips.getById(id) }
        candleShip?.worldToShip?.transformDirection(toward)
        val away = Vector3d(candle.local).sub(middle)
        // The strand whose direction points further to one side of the line to the hull leaves the candle on that side.
        val sides = mine.map { strand ->
            val outWorld = ship.shipToWorld.transformDirection(Vector3d(strand.aim.dir))
            candleShip?.worldToShip?.transformDirection(outWorld)
            outWorld.z * toward.x - outWorld.x * toward.z
        }
        for ((k, strand) in mine.withIndex()) {
            val turn = when {
                mine.size < 2 -> 0.0
                k == sides.indexOf(sides.max()) -> ANCHOR_SPLAY
                else -> -ANCHOR_SPLAY
            }
            val reach = min(ANCHOR_OUT, BLOCK_HALF - vineParticleRadius(strand.width) - ANCHOR_EDGE_GAP)
            val anchor = VinePoint(candle.block, besideCandle(candle.local, toward, away, turn, reach), candle.shipId)
            val attached = attachment(anchor)
            if (attached == null) continue
            ready += ReadyStrand(anchor, attached.first, attached.second, strand)
        }
    }
    return ready
}

/** Makes [here]'s chain as a short sprout from its candle toward its route; a strand whose chain fails is lost. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.sproutStrand(ship: ServerShip, here: ReadyStrand, random: Random, route: CaptureRoute): CaptureStrand {
    val writhe = (if (random.nextBoolean()) -1.0 else 1.0) * (WRITHE_MIN + random.nextDouble() * WRITHE_SPREAD)
    val width = here.aimed.width
    val spacing = vineSpacing(width)
    val aim = here.aimed.aim
    val hull = here.aimed.hull
    val anchorShip = ship.worldToShip.transformPosition(Vector3d(here.world))
    route.build(anchorShip, hull.local, aim)
    val first = route.guide(min(1.0, spacing / max(route.length, LENGTH_EPS)), writhe, Vector3d())
    val dir = ship.shipToWorld.transformPosition(first).sub(here.world)
    if (dir.lengthSquared() < LENGTH_EPS) dir.set(0.0, 1.0, 0.0) else dir.normalize()
    val seed = ReelSeed(here.world, dir, FIRST_LINK * spacing / VINE_TARGET_SPACING, FIRST_LINKS, spacing)
    val chain = makeVineChain(
        seed.path, here.endpoint, ChainEndpoint.World(Vector3d(seed.path.last())), Vector3d(), seed.massPerLength, seed.targetSpacing, width,
    )
    val vine = chain?.let {
        WoggakVine(newVineId(), it.id, WoggakVineAnchor(here.anchor), WoggakVineAnchor(null), spacing = spacing, width = width)
    }
    if (vine != null) addVine(vine, seed.path)
    // A failed chain keeps its place, so the launch order stays as planned.
    return CaptureStrand(vine?.id ?: 0L, here.anchor, hull, seed.spacing, writhe, width, aim).also {
        it.lost = vine == null
        it.target = seed.length
        it.payBase = seed.length
        it.anchorWorld.set(here.world)
        ship.shipToWorld.transformPosition(hull.local, it.hullWorld)
        ship.worldToShip.transformPosition(seed.path.last(), it.hold)
    }
}

/** From the candle centre: candle shapes reach 0.19 out; a node needs its radius more, and must stay off the next block. */
private const val ANCHOR_OUT = 0.38
private const val BLOCK_HALF = 0.5
private const val ANCHOR_EDGE_GAP = 0.01

/**
 * The vine start beside a candle whose centre on the block under it is [candle]: [reach] out on the side [toward] the
 * hull turned by [turn] radians, so the rope leaves away from its candle, or [away] from the circle when the hull is
 * straight above. Only x and z count.
 */
internal fun besideCandle(candle: Vector3dc, toward: Vector3dc, away: Vector3dc, turn: Double = 0.0, reach: Double = ANCHOR_OUT): Vector3d {
    var dx = toward.x(); var dz = toward.z()
    if (dx * dx + dz * dz < LENGTH_EPS) { dx = away.x(); dz = away.z() }
    val length = sqrt(dx * dx + dz * dz)
    if (length < LENGTH_EPS) { dx = 1.0; dz = 0.0 } else { dx /= length; dz /= length }
    val c = cos(turn); val s = sin(turn)
    val rx = dx * c - dz * s
    val rz = dx * s + dz * c
    return Vector3d(candle.x() + rx * reach, candle.y(), candle.z() + rz * reach)
}

/** The aims of [count] strands round the ship's box, each along its own random direction. Computed once. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.aimStrands(ship: ServerShip, count: Int, random: Random): List<Aimed> {
    val box = ship.shipAABB ?: return emptyList()
    val out = ArrayList<Aimed>(count)
    for ((j, dir) in captureDirections(count, random).withIndex()) {
        val width = captureStrandWidth(random)
        aimStrand(ship, box, dir, random, out)?.let { out += Aimed(j / STRANDS_PER_ANCHOR, it.first, it.second, width) }
    }
    return out
}

/**
 * The aim and hull point of a strand along [dir]: the first free hull block on the line in from its target, then
 * on lines turned a little, then the free hull block nearest its target. Null only when the ship has no free block.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.aimStrand(
    ship: ServerShip, box: AABBic, dir: Vector3dc, random: Random, taken: List<Aimed>,
): Pair<CaptureAim, VinePoint>? {
    val half = Vector3d((box.maxX() - box.minX()) * 0.5, (box.maxY() - box.minY()) * 0.5, (box.maxZ() - box.minZ()) * 0.5)
    val centre = Vector3d(box.minX() + half.x, box.minY() + half.y, box.minZ() + half.z)
    val takenHulls = taken.map { it.hull }
    val from = Vector3d()
    val to = Vector3d()
    for (attempt in 0..MAX_NUDGES) {
        val angle = NUDGE_STEP * ((attempt + 1) / 2)
        val turned = if (attempt == 0) dir else nudgedDirection(dir, angle, random)
        val aim = captureAim(centre, half, turned)
        inwardLine(aim, from, to)
        val hull = traceHull(ship, box, from, to) ?: continue
        if (!freeOf(takenHulls, hull)) continue
        return aim to hull
    }
    val aim = captureAim(centre, half, dir)
    val target = aim.target(Vector3d())
    val solid = { pos: BlockPos -> isLoaded(pos) && !getBlockState(pos).getCollisionShape(this, pos).isEmpty }
    val cell = nearestSolidCell(
        BlockPos(box.minX(), box.minY(), box.minZ()), BlockPos(box.maxX() - 1, box.maxY() - 1, box.maxZ() - 1), target, solid,
    ) { pos -> freeOf(takenHulls, faceLatch(ship, pos, target, solid)) }
    val hull = cell?.let { faceLatch(ship, it, target, solid) }
    return hull?.let { aim to it }
}

/** True when [hull] is [MIN_HULL_GAP] or more from the hull point of each of [taken]. */
private fun freeOf(taken: List<VinePoint>, hull: VinePoint): Boolean = taken.none { it.local.distance(hull.local) < MIN_HULL_GAP }

private val FACES = Direction.entries.toTypedArray()

/** The latch point on the face of hull block [pos] that looks most toward [target], an open face before a covered one. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.faceLatch(ship: ServerShip, pos: BlockPos, target: Vector3dc, solid: (BlockPos) -> Boolean): VinePoint {
    val cx = pos.x + 0.5
    val cy = pos.y + 0.5
    val cz = pos.z + 0.5
    var face = Direction.UP
    var bestOpen = false
    var bestLook = Double.NEGATIVE_INFINITY
    for (f in FACES) {
        val open = !solid(pos.relative(f))
        val look = f.stepX * (target.x() - cx) + f.stepY * (target.y() - cy) + f.stepZ * (target.z() - cz)
        if (open && !bestOpen || open == bestOpen && look > bestLook) {
            face = f
            bestOpen = open
            bestLook = look
        }
    }
    val snapped = snapToShapeAnchor(pos, face, Vec3(cx + face.stepX * 0.5, cy + face.stepY * 0.5, cz + face.stepZ * 0.5))
    return VinePoint(pos, Vector3d(snapped.x, snapped.y, snapped.z), ship.id)
}

/** Half size of the box of [half] sizes along the unit [axis]. */
private fun extent(axis: Vector3dc, half: Vector3dc): Double =
    abs(axis.x()) * half.x() + abs(axis.y()) * half.y() + abs(axis.z()) * half.z()

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
    val boxMax = Vector3d(box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0)
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

/** [strand]'s hull point again, on its line in from the target, when its block is gone; false when none is left. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.reaimHull(ship: ServerShip, strand: CaptureStrand): Boolean {
    val block = strand.hull.block
    if (!isLoaded(block) || !getBlockState(block).getCollisionShape(this, block).isEmpty) return true
    val box = ship.shipAABB ?: return false
    val from = Vector3d()
    val to = Vector3d()
    inwardLine(strand.aim, from, to)
    strand.hull = traceHull(ship, box, from, to) ?: return false
    return true
}

@OptIn(GameTickOnly::class)
internal fun ServerLevel.tickVineCaptures(data: WoggakVineData) {
    if (data.captures.isEmpty()) return
    // The captures are saved with the level's vine data.
    data.setDirty()
    // One budget for all captures, so many rites at once still make few chains a tick.
    val budget = data.captureBudget
    budget.reset()
    val iterator = data.captures.iterator()
    while (iterator.hasNext()) {
        val capture = iterator.next()
        if (stepCapture(data, capture, budget)) continue
        LOG.warn("Woggak Vine capture of ship {} ended in phase {}: the ship or every vine on it is gone", capture.shipId, capture.phase)
        iterator.remove()
        data.jointsDirty = true
    }
}

/** False when the capture ends: the ship is gone, or no vine of it still holds the ship. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.stepCapture(data: WoggakVineData, capture: VineCapture, budget: VineBuildBudget): Boolean {
    val ship = shipObjectWorld.allShips.getById(capture.shipId)
    if (ship == null) {
        for (strand in capture.strands) if (!strand.latched) stopStrand(data, strand)
        return false
    }
    if (capture.resync && !resyncCapture(data, capture, ship)) return true
    capture.phaseTicks++
    when (capture.phase) {
        CapturePhase.GROW -> capture.next(payStrands(data, capture, ship, toHull = false))
        CapturePhase.WEAVE -> {
            holdStrands(data, capture, ship)
            if (spawnLinks(data, capture, ship, budget)) capture.next(startLatch(capture))
        }
        CapturePhase.LATCH -> {
            followPayouts(data, capture)
            capture.next(latchStrands(data, capture, ship) && startTighten(capture))
        }
        CapturePhase.TIGHTEN -> {
            followPayouts(data, capture)
            capture.next(tightened(data, capture))
        }
        CapturePhase.HOLD -> Unit
    }
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
private fun ServerLevel.resyncCapture(data: WoggakVineData, capture: VineCapture, ship: ServerShip): Boolean {
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
    if (capture.phase == CapturePhase.TIGHTEN) startTighten(capture)
    // The time caps count from the restart.
    if (capture.phase != CapturePhase.WEAVE && capture.phase != CapturePhase.HOLD) capture.phaseTicks = 0
    capture.settleTicks = 0
    capture.resync = false
    return true
}

private fun VineCapture.next(go: Boolean) {
    if (!go) return
    phase = phase.following
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

/** The route of [strand] from its anchor now round the hull to its hull point, in [VineCapture.route]. */
@OptIn(GameTickOnly::class)
private fun VineCapture.routeOf(ship: ServerShip, strand: CaptureStrand): CaptureRoute {
    ship.worldToShip.transformPosition(strand.anchorWorld, anchorShip)
    return route.build(anchorShip, strand.hull.local, strand.aim)
}

/**
 * GROW ([toHull] false) pays out each strand one tick, its tip on its route round the hull, and stops it at its
 * target; the strands launch in list order, each after [launchesNext] of the strand before it. LATCH pays out the
 * rest, so the tip goes in along -d, and latches it on the hull. True when no strand moves any more.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.payStrands(data: WoggakVineData, capture: VineCapture, ship: ServerShip, toHull: Boolean): Boolean {
    val strands = capture.strands
    refreshStrandPoints(data, strands, ship)
    var moving = false
    for ((i, strand) in strands.withIndex()) {
        if (strand.latched || strand.lost) continue
        // The strand before this one is stepped first in the loop, so its share is from this tick.
        val launches = toHull || i == 0 || launchesNext(strands[i - 1])
        if (payStrand(data, capture, ship, strand, toHull, launches)) moving = true
    }
    return !moving
}

/** One pay-out tick of [strand]; a strand that may not [launches] yet only waits. True while it still moves. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.payStrand(
    data: WoggakVineData, capture: VineCapture, ship: ServerShip, strand: CaptureStrand, toHull: Boolean, launches: Boolean,
): Boolean {
    val chain = strandChain(data, strand) ?: return false
    val status = chain.status
    if (status.lifecycle == ChainLifecycle.PENDING && capture.phaseTicks < MAX_PENDING_TICKS) return true
    chainStop(status)?.let { stop ->
        stopStrand(data, strand, stop)
        return false
    }
    val paid = status.currentRestLength
    strand.paid = paid
    if (strand.parked && !toHull) {
        holdTip(chain, ship, strand, paid)
        return false
    }
    growStop(strand)?.let { stop ->
        stopStrand(data, strand, stop)
        return false
    }
    val route = capture.routeOf(ship, strand)
    val full = fullReelTarget((if (toHull) route.length else route.length * route.targetShare) + strand.spacing, strand.spacing)
    val rate = captureNodeRate(strand.spacing)
    return when {
        strand.capped || strand.payTicks >= captureGrowTicks(full, strand.spacing, rate) -> {
            if (!toHull) {
                parkStrand(chain, strand, true, paid)
            } else if (!capLatch(data, capture, strand, chain, ship, paid)) {
                stopStrand(data, strand, "it paid out %.1f of %.1f blocks in %d ticks and the ship has no free block".format(paid, full, strand.payTicks))
            }
            false
        }
        paid >= full - strand.spacing * 1e-6 -> {
            if (toHull) latch(data, strand, chain) else parkStrand(chain, strand, false, paid)
            false
        }
        !launches -> true
        else -> {
            reelOut(chain, strand, full, rate)
            steerTip(data, chain, ship, strand, route, toHull)
        }
    }
}

/** Why a strand's chain cannot pay out, or null. */
private fun chainStop(status: ChainStatus): String? = when {
    status.lifecycle == ChainLifecycle.PENDING -> "its chain did not start"
    status.lifecycle != ChainLifecycle.ACTIVE -> "its chain is ${status.lifecycle}"
    else -> status.failure
}

/** Pins the tip of a paying-out [strand] at its guide point on [route]; false when the tip is not loaded. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.steerTip(
    data: WoggakVineData, chain: ServerChain, ship: ServerShip, strand: CaptureStrand, route: CaptureRoute, toHull: Boolean,
): Boolean {
    val paid = strand.paid
    // VS keeps the endpoint vector, so each tick gets a fresh one.
    val tip = route.guide(captureGuideShare(paid, route, toHull), strand.writhe, Vector3d())
    ship.shipToWorld.transformPosition(tip)
    clampReach(strand.anchorWorld, tip, captureTipReach(paid, strand.spacing))
    if (!isLoaded(BlockPos.containing(tip.x, tip.y, tip.z))) {
        stopStrand(data, strand, "its tip is not loaded")
        return false
    }
    ship.worldToShip.transformPosition(tip, strand.hold)
    chain.setEndpoint(ChainEnd.END, ChainEndpoint.World(tip))
    return true
}

/** Moves each live strand's anchor and hull points to now, and aims again a strand whose hull block is gone. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.refreshStrandPoints(data: WoggakVineData, strands: List<CaptureStrand>, ship: ServerShip) {
    for (strand in strands) {
        // A lost strand keeps its last anchor point.
        if (!strand.lost && !worldPoint(strand.anchor, strand.anchorWorld)) stopStrand(data, strand, "its anchor ship is gone")
        if (!strand.lost && !strand.latched && !reaimHull(ship, strand)) stopStrand(data, strand, "no hull block is left on its line")
        ship.shipToWorld.transformPosition(strand.hull.local, strand.hullWorld)
    }
}

/** Raises the pay-out reel target of [strand] one tick at [rate] nodes a second, never over [full]. */
@OptIn(GameTickOnly::class)
private fun reelOut(chain: ServerChain, strand: CaptureStrand, full: Double, rate: Double) {
    val target = max(strand.target, capturePayTarget(++strand.payTicks, strand.payBase, strand.spacing, full, rate))
    if (target <= strand.target) return
    chain.setReelTarget(ChainReelControl(
        ChainEnd.START, target, throwReelRate(0.0, strand.spacing), capturePayoutForce(strand.width, strand.spacing),
    ))
    strand.target = target
}

/** Ends the pay-out of [strand]; its tip hangs at its target, or where it is when [capped], until LATCH. */
@OptIn(GameTickOnly::class)
private fun parkStrand(chain: ServerChain, strand: CaptureStrand, capped: Boolean, paid: Double) {
    strand.park(capped)
    // setTargetLength ends the reel, so the weave plan does not wait for it.
    if (paid.isFinite() && paid > 0) chain.setTargetLength(paid)
}

/** Pins the tip of a hanging [strand] at its hold point, which moves with the ship. */
@OptIn(GameTickOnly::class)
private fun holdTip(chain: ServerChain, ship: ServerShip, strand: CaptureStrand, paid: Double) {
    // VS keeps the endpoint vector, so each tick gets a fresh one.
    chain.setEndpoint(ChainEnd.END, ChainEndpoint.World(captureHeldTip(strand.hold, ship.shipToWorld, strand.anchorWorld, paid, strand.spacing)))
}

/** Holds each hanging strand at its point while the weave ties them. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.holdStrands(data: WoggakVineData, capture: VineCapture, ship: ServerShip) {
    for (strand in capture.strands) {
        if (!strand.hangs) continue
        if (!worldPoint(strand.anchor, strand.anchorWorld)) {
            stopStrand(data, strand, "its anchor ship is gone")
            continue
        }
        val chain = strandChain(data, strand) ?: continue
        val status = chain.status
        if (status.lifecycle != ChainLifecycle.ACTIVE) continue
        holdTip(chain, ship, strand, status.currentRestLength)
    }
}

/** Starts the pay-out of the rest of each hanging strand from its paid length. */
private fun startLatch(capture: VineCapture): Boolean {
    for (strand in capture.strands) {
        strand.payBase = strand.target
        strand.payTicks = 0
    }
    capture.settleTicks = 0
    return true
}

/**
 * Takes each hanging strand in to the hull and latches it; true when all latched or stopped and the cap-latch reels
 * ended, or [MAX_PENDING_TICKS] after that.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.latchStrands(data: WoggakVineData, capture: VineCapture, ship: ServerShip): Boolean {
    if (!payStrands(data, capture, ship, toHull = true)) {
        capture.settleTicks = 0
        return false
    }
    if (++capture.settleTicks >= MAX_PENDING_TICKS) return true
    return capture.strands.none { it.latched && strandChain(data, it)?.status?.reelBusy() == true }
}

/**
 * VS adds pay-out nodes at a strand's start, so its node numbers go up; each joint on a strand moves with them. A
 * rebuilt chain only starts a new count, as the rebuild renumbers the joints.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.followPayouts(data: WoggakVineData, capture: VineCapture) {
    for (strand in capture.strands) {
        if (strand.lost) continue
        val vine = data.vine(strand.vineId) ?: continue
        val status = shipObjectWorld.allChains.getById(vine.chain)?.status ?: continue
        if (status.lifecycle != ChainLifecycle.ACTIVE) continue
        val shift = payoutShift(strand.countedChain, strand.countedNodes, vine.chain.value, status.nodeCount)
        strand.countedChain = vine.chain.value
        strand.countedNodes = status.nodeCount
        if (shift != 0) shiftJoints(data, strand.vineId, shift)
    }
}

/** Moves each joint to a node of vine [vineId] by [shift] nodes. */
private fun shiftJoints(data: WoggakVineData, vineId: Long, shift: Int) {
    for (other in data.vines) {
        val moved = shiftJoint(other.start, vineId, shift) or shiftJoint(other.end, vineId, shift)
        if (moved) data.touch(other)
    }
}

private fun shiftJoint(anchor: WoggakVineAnchor, vineId: Long, shift: Int): Boolean {
    val joint = anchor.point?.joint ?: return false
    if (joint.vine != vineId) return false
    joint.node += shift
    return true
}

/** Why a growing [strand] must stop, or null: VS simulates only loaded chunks. */
private fun ServerLevel.growStop(strand: CaptureStrand): String? =
    if (isLoaded(BlockPos.containing(strand.hullWorld.x, strand.hullWorld.y, strand.hullWorld.z))) null else "its hull point is not loaded"

/**
 * A strand that hit a time cap latches its tip on the free hull block nearest it, so the strand holds and does not
 * fall. When the paid rope is short of the new hull point, the reel pays out the rest. False when the ship has no free block.
 */
@OptIn(GameTickOnly::class)
@Suppress("LongParameterList") // The strand's chain, ship and pay-out state, all read here.
private fun ServerLevel.capLatch(
    data: WoggakVineData, capture: VineCapture, strand: CaptureStrand, chain: ServerChain, ship: ServerShip, paid: Double,
): Boolean {
    val box = ship.shipAABB ?: return false
    val nodes = chain.status.nodeCount
    if (nodes < 1) return false
    if (data.scratch.size < nodes * 3) data.scratch = DoubleArray(nodes * 3)
    if (chain.copyNodePositions(data.scratch) != nodes) return false
    val k = (nodes - 1) * 3
    val tip = ship.worldToShip.transformPosition(Vector3d(data.scratch[k], data.scratch[k + 1], data.scratch[k + 2]))
    val taken = capture.strands.filter { it !== strand && !it.lost }.map { it.hull }
    val solid = { pos: BlockPos -> isLoaded(pos) && !getBlockState(pos).getCollisionShape(this, pos).isEmpty }
    val cell = nearestSolidCell(
        BlockPos(box.minX(), box.minY(), box.minZ()), BlockPos(box.maxX() - 1, box.maxY() - 1, box.maxZ() - 1), tip, solid,
    ) { pos -> freeOf(taken, faceLatch(ship, pos, tip, solid)) }
    if (cell == null) return false
    strand.hull = faceLatch(ship, cell, tip, solid)
    ship.shipToWorld.transformPosition(strand.hull.local, strand.hullWorld)
    val reel = capLatchReelTarget(strand.anchorWorld.distance(strand.hullWorld), paid, strand.spacing)
    if (reel != null) {
        chain.setReelTarget(ChainReelControl(
            ChainEnd.START, reel, throwReelRate(0.0, strand.spacing), capturePayoutForce(strand.width, strand.spacing),
        ))
        strand.target = reel
    }
    latch(data, strand, chain)
    return true
}

/**
 * The reel target that lets a strand of [paid] rope reach a hull point [span] from its anchor, or null when the paid
 * rope already reaches with [TIP_SLACK] to spare.
 */
internal fun capLatchReelTarget(span: Double, paid: Double, spacing: Double): Double? =
    if (span + TIP_SLACK <= paid) null else span + spacing

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

/** True when the vine after [strand] may launch: [strand] paid out [LAUNCH_LEAD], hangs, latched, or stopped. */
internal fun launchesNext(strand: CaptureStrand): Boolean = strand.lost || strand.latched || strand.parked || strand.paid >= LAUNCH_LEAD

/** The guide share for [paid] rope on [route]: GROW ([toHull] false) stops the tip at the target; LATCH takes it from there in to the hull. */
internal fun captureGuideShare(paid: Double, route: CaptureRoute, toHull: Boolean): Double {
    val s = paid / max(route.length, LENGTH_EPS)
    return if (toHull) s.coerceIn(route.targetShare, 1.0) else s.coerceIn(0.0, route.targetShare)
}

/** Stops the pay-out of [this] strand: at its target, or where its tip is when [capped]. It hangs there until LATCH. */
internal fun CaptureStrand.park(capped: Boolean) {
    parked = true
    this.capped = capped
    if (!capped) aim.target(hold)
}

/** The world point that pins the tip of a hanging strand: its hold point, never past the [paid] rope from [anchor]. */
internal fun captureHeldTip(hold: Vector3dc, shipToWorld: Matrix4dc, anchor: Vector3dc, paid: Double, spacing: Double): Vector3d =
    clampReach(anchor, shipToWorld.transformPosition(hold, Vector3d()), captureTipReach(paid, spacing))

/** Nodes that a strand's start got since the last count; 0 for a first count or a new (rebuilt) chain. */
internal fun payoutShift(lastChain: Long, lastNodes: Int, chain: Long, nodes: Int): Int =
    if (lastChain != chain || lastNodes <= 0 || nodes <= 0) 0 else nodes - lastNodes

/** Link counts for the weave: only strands that hang free of the hull are tied; -1 for the others or a count under 1. */
internal fun hangingLinkCounts(strands: List<CaptureStrand>, nodes: (Int) -> Int): IntArray = IntArray(strands.size) { i ->
    val count = if (strands[i].hangs) nodes(i) else 0
    if (count < 2) -1 else count - 1
}

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

/**
 * Plans the cross-links from the node counts of the hanging strands, once VS shows each pay-out reel ended, as the
 * plan counts nodes from the start. True when the plan is made.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.planWeave(data: WoggakVineData, capture: VineCapture): Boolean {
    val hanging = capture.strands.map { if (it.hangs) strandChain(data, it) else null }
    if (capture.phaseTicks < MAX_PENDING_TICKS && hanging.any { it != null && it.status.reel != null }) return false
    val links = hangingLinkCounts(capture.strands) { i ->
        val status = hanging[i]?.status
        if (status == null || status.lifecycle != ChainLifecycle.ACTIVE) 0 else status.nodeCount
    }
    val spacings = DoubleArray(hanging.size) { i ->
        val status = hanging[i]?.status
        if (status == null || links[i] < 1) 0.0 else status.currentRestLength / links[i]
    }
    val random = Random(capture.strands.fold(capture.shipId * SEED_MIX) { h, s -> (h xor s.vineId) * SEED_MIX })
    capture.plan = weavePlan(links, spacings, weavePairs(capture.strands.map { it.aim.dir }, random), random)
    capture.planCursor = 0
    capture.planned = true
    capture.phaseTicks = 0
    return true
}

/** Makes the cross-links whose turn came, from the tips down, while [budget] allows; true when all are made. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.spawnLinks(data: WoggakVineData, capture: VineCapture, ship: ServerShip, budget: VineBuildBudget): Boolean {
    if (!capture.planned && !planWeave(data, capture)) return false
    val plan = capture.plan
    val box = ship.shipAABB
    capture.nodes.newTick()
    while (capture.planCursor < plan.size && plan[capture.planCursor].step * CROSS_INTERVAL_TICKS <= capture.phaseTicks) {
        // A refused link keeps its place, so the wave goes on from it next tick.
        if (!makeCrossLink(data, capture, plan[capture.planCursor], ship, box, budget)) return false
        capture.planCursor++
    }
    if (capture.planCursor < plan.size) return false
    if (capture.links.size < plan.size) {
        LOG.warn("Woggak Vine capture of ship {}: {} of {} cross-links could not be made", capture.shipId, plan.size - capture.links.size, plan.size)
    }
    return true
}

/** The world point of node [node] of strand [index], from the node copy of this tick; null when its chain is not active. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.strandNode(data: WoggakVineData, capture: VineCapture, index: Int, node: Int): Vector3d? {
    val nodes = capture.nodes
    if (!nodes.isRead(index)) {
        val chain = data.vine(capture.strands[index].vineId)?.let { shipObjectWorld.allChains.getById(it.chain) }
        var copied = 0
        if (chain != null && chain.status.lifecycle == ChainLifecycle.ACTIVE) {
            val count = chain.status.nodeCount
            if (count > 0 && chain.copyNodePositions(nodes.buffer(index, count)) == count) copied = count
        }
        nodes.stored(index, copied)
    }
    return nodes.point(index, node)
}

/**
 * A cross-link from strand to strand, with some slack; none where it would lie through the hull.
 * False only when [budget] refuses it; the link then waits.
 */
@OptIn(GameTickOnly::class)
@Suppress("LongParameterList") // The capture, its ship and the shared tick budget, all read here.
private fun ServerLevel.makeCrossLink(
    data: WoggakVineData, capture: VineCapture, link: CrossLinkPlan, ship: ServerShip, box: AABBic?, budget: VineBuildBudget,
): Boolean {
    val a = capture.strands[link.strandA]
    val b = capture.strands[link.strandB]
    if (!a.hangs || !b.hangs) return true
    val vineA = data.vine(a.vineId) ?: return true
    val vineB = data.vine(b.vineId) ?: return true
    val from = strandNode(data, capture, link.strandA, link.nodeA) ?: return true
    val to = strandNode(data, capture, link.strandB, link.nodeB) ?: return true
    if (from.distance(to) < MIN_CROSS_GAP + 2 * CROSS_END_GAP) return true
    val hash = crossLinkHash(capture.shipId, link)
    // The link's end nodes start clear of the strand nodes; on top of them, contacts push both apart until the joint masks them.
    val path = sagPath(
        Vector3d(to).sub(from).normalize().mul(CROSS_END_GAP).add(from), Vector3d(from).sub(to).normalize().mul(CROSS_END_GAP).add(to),
        crossLinkSag(hash),
    )
    if (box != null && pathHitsHull(ship, box, path)) return true
    val width = crossLinkWidth(hash, min(vineA.width, vineB.width))
    val spacing = vineSpacing(width)
    if (!budget.take(crossLinkNodes(path, spacing))) return false
    val chain = makeVineChain(path, ChainEndpoint.Free, ChainEndpoint.Free, Vector3d(), MASS_PER_LENGTH, spacing, width) ?: return true
    val start = VinePoint(BlockPos.containing(from.x, from.y, from.z), from, null, VineJoint(vineA.id, link.nodeA, vineA.home))
    val end = VinePoint(BlockPos.containing(to.x, to.y, to.z), to, null, VineJoint(vineB.id, link.nodeB, vineB.home))
    val vine = WoggakVine(newVineId(), chain.id, WoggakVineAnchor(start), WoggakVineAnchor(end), spacing = spacing, width = width)
    addVine(vine, path)
    capture.links += vine.id
    return true
}

/** Nodes that VS lays along [path] at [spacing]. */
internal fun crossLinkNodes(path: List<Vector3dc>, spacing: Double): Int {
    require(spacing > 0.0) { "spacing $spacing" }
    var length = 0.0
    for (i in 1 until path.size) length += path[i - 1].distance(path[i])
    return ceil(length / spacing - LENGTH_EPS).toInt().coerceAtLeast(1) + 1
}

/** Each strand's node positions, copied at most once a weave tick into buffers that are kept. Not saved. */
internal class StrandNodes(size: Int) {
    private val buffers = Array(size) { DoubleArray(0) }
    private val counts = IntArray(size)
    private val read = BooleanArray(size)

    fun newTick() = read.fill(false)

    fun isRead(index: Int) = read[index]

    /** A kept buffer of strand [index] for at least [nodes] nodes. */
    fun buffer(index: Int, nodes: Int): DoubleArray {
        if (buffers[index].size < nodes * 3) buffers[index] = DoubleArray(nodes * 3)
        return buffers[index]
    }

    /** Strand [index] holds [nodes] copied nodes this tick; 0 when it has none. */
    fun stored(index: Int, nodes: Int) {
        counts[index] = nodes
        read[index] = true
    }

    /** A new vector at node [node] of strand [index], clamped as [jointNode] does; null when it has no nodes. */
    fun point(index: Int, node: Int): Vector3d? {
        val n = counts[index]
        if (n < 1) return null
        val k = node.coerceIn(0, n - 1) * 3
        val p = buffers[index]
        return Vector3d(p[k], p[k + 1], p[k + 2])
    }
}

/** True when a segment of the world [path] passes through a block of [ship]. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.pathHitsHull(ship: ServerShip, box: AABBic, path: List<Vector3dc>): Boolean {
    val from = Vector3d()
    val to = Vector3d()
    for (k in 1 until path.size) {
        ship.worldToShip.transformPosition(path[k - 1], from)
        ship.worldToShip.transformPosition(path[k], to)
        if (traceHull(ship, box, from, to) != null) return true
    }
    return false
}

/** Starts the stall count of each latched strand; [tightened] shortens its links each tick. */
private fun startTighten(capture: VineCapture): Boolean {
    for (strand in capture.strands) {
        strand.lastRest = Double.NaN
        strand.stillTicks = 0
    }
    return true
}

/**
 * Shortens the links of one strand toward the reel floor, as a player reel does (the VS reel servo stalls under
 * tension). At the floor the vine is made again with fewer links; false when it cannot get shorter.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.shortenStrand(data: WoggakVineData, strand: CaptureStrand, chain: ServerChain): Boolean {
    val status = chain.status
    val links = status.nodeCount - 1
    val rest = status.currentRestLength
    val vine = data.vine(strand.vineId)
    if (vine == null || links < 1 || !rest.isFinite() || rest <= 0.0) return false
    if (atReelFloor(rest, links, vine.width)) {
        if (respacedLinks(rest, vine.spacing) >= links) return false
        return rebuildRespaced(data, vine, chain) != null
    }
    val floor = reelFloor(links, vine.width)
    if (status.targetLength != floor) chain.setTargetLength(floor, TIGHTEN_NODES_PER_SECOND * strand.spacing)
    return true
}

/** True when every strand stopped taking in rope (a stall or the shortest length), or the time cap ran out; the strands then keep their length. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.tightened(data: WoggakVineData, capture: VineCapture): Boolean {
    val timeUp = capture.phaseTicks >= MAX_TIGHTEN_TICKS
    var done = true
    for (strand in capture.strands) {
        if (!strand.latched) continue
        val chain = strandChain(data, strand) ?: continue
        val status = chain.status
        if (status.lifecycle != ChainLifecycle.ACTIVE) { done = false; continue }
        val rest = status.currentRestLength
        if (!rest.isFinite()) continue
        strand.stillTicks = if (reelStalled(strand.lastRest, rest, strand.spacing)) strand.stillTicks + 1 else 0
        strand.lastRest = rest
        if (strand.stillTicks >= REEL_STALL_TICKS) continue
        if (shortenStrand(data, strand, chain)) done = false
    }
    if (!done && !timeUp) return false
    if (!done) LOG.warn("Woggak Vine capture of ship {}: a reel did not tighten in {} ticks; the hold starts", capture.shipId, MAX_TIGHTEN_TICKS)
    for (strand in capture.strands) {
        if (!strand.latched) continue
        val chain = strandChain(data, strand) ?: continue
        val rest = chain.status.currentRestLength
        if (rest.isFinite() && rest > 0 && chain.status.targetLength < rest - LENGTH_EPS) chain.setTargetLength(rest)
    }
    return true
}
