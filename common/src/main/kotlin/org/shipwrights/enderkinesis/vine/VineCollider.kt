package org.shipwrights.enderkinesis.vine

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.valkyrienskies.core.api.chains.Chain
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Half the side of the vine's collision cubes; the same as the drawn box. */
const val VINE_HALF_WIDTH = 2.0 / 16

private const val MAX_CUBES_PER_LINK = 4

/**
 * Node positions of all vines in one level, copied once a tick, for entity collision.
 * The arrays grow and are then used again, so a tick allocates nothing in the steady state.
 */
class VineCollider {
    private var nodes = DoubleArray(0)
    /** For each node: true when the link from it to the next node is broken. */
    private var gaps = BooleanArray(0)
    private var ranges = IntArray(0)
    private var bounds = DoubleArray(0)
    private val chains = ArrayList<Chain>()
    private var vineCount = 0
    private var nodeCount = 0

    fun clear() {
        vineCount = 0
        nodeCount = 0
        chains.clear()
    }

    /** Copies [chain]'s nodes; returns false when a position is not finite, which is a blown-up chain. */
    fun add(chain: Chain): Boolean {
        val n = chain.status.nodeCount
        if (n < 2) return true
        ensure(nodeCount + n, vineCount + 1)
        if (chain.copyNodePositions(nodes, nodeCount * 3) != n) return true
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (i in 0 until n) {
            val k = (nodeCount + i) * 3
            val x = nodes[k]; val y = nodes[k + 1]; val z = nodes[k + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
            minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
            maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
            gaps[nodeCount + i] = i == n - 1
        }
        val broken = chain.status.brokenLinks
        if (broken.isNotEmpty()) for (link in broken) if (link in 0 until n) gaps[nodeCount + link] = true
        val b = vineCount * 6
        bounds[b] = minX - VINE_HALF_WIDTH; bounds[b + 1] = minY - VINE_HALF_WIDTH; bounds[b + 2] = minZ - VINE_HALF_WIDTH
        bounds[b + 3] = maxX + VINE_HALF_WIDTH; bounds[b + 4] = maxY + VINE_HALF_WIDTH; bounds[b + 5] = maxZ + VINE_HALF_WIDTH
        ranges[vineCount * 2] = nodeCount
        ranges[vineCount * 2 + 1] = nodeCount + n
        chains.add(chain)
        vineCount++
        nodeCount += n
        return true
    }

    /** True when a vine's bounds overlap [box]; a cheap test before the full lookup. */
    fun overlaps(box: AABB): Boolean {
        for (v in 0 until vineCount) if (overlapsVine(v, box)) return true
        return false
    }

    /**
     * Each unbroken link that touches [box], into [out], weighted by how many of its collision cubes touch it.
     * [VineContacts.t] is the mean place of those cubes along the link, 0 at its first node and 1 at the second.
     */
    fun contacts(box: AABB, out: VineContacts) {
        out.clear()
        val r = VINE_HALF_WIDTH
        for (v in 0 until vineCount) {
            if (!overlapsVine(v, box)) continue
            val first = ranges[v * 2]
            for (i in first until ranges[v * 2 + 1]) {
                if (gaps[i]) continue
                val k = i * 3
                val ux = nodes[k + 3] - nodes[k]; val uy = nodes[k + 4] - nodes[k + 1]; val uz = nodes[k + 5] - nodes[k + 2]
                val steps = cubeSteps(sqrt(ux * ux + uy * uy + uz * uz))
                var touching = 0
                var tSum = 0.0
                for (s in 0..steps) {
                    val t = s.toDouble() / steps
                    val x = nodes[k] + ux * t; val y = nodes[k + 1] + uy * t; val z = nodes[k + 2] + uz * t
                    if (x + r < box.minX || x - r > box.maxX || y + r < box.minY || y - r > box.maxY ||
                        z + r < box.minZ || z - r > box.maxZ) continue
                    touching++
                    tSum += t
                }
                if (touching > 0 && !out.add(chains[v], i - first, tSum / touching, touching.toDouble())) return
            }
        }
    }

    private fun overlapsVine(v: Int, box: AABB): Boolean {
        val b = v * 6
        return !(box.maxX < bounds[b] || box.minX > bounds[b + 3] || box.maxY < bounds[b + 1] ||
            box.minY > bounds[b + 4] || box.maxZ < bounds[b + 2] || box.minZ > bounds[b + 5])
    }

    /** [base] with cubes for each vine link in [box]; [base] itself when no link is there. */
    fun withShapes(box: AABB, base: List<VoxelShape>): List<VoxelShape> {
        var out: MutableList<VoxelShape>? = null
        for (v in 0 until vineCount) {
            if (!overlapsVine(v, box)) continue
            for (i in ranges[v * 2] until ranges[v * 2 + 1]) {
                if (gaps[i]) continue
                out = addLink(i * 3, box, base, out)
            }
        }
        return out ?: base
    }

    private fun addLink(k: Int, box: AABB, base: List<VoxelShape>, current: MutableList<VoxelShape>?): MutableList<VoxelShape>? {
        val ax = nodes[k]; val ay = nodes[k + 1]; val az = nodes[k + 2]
        val bx = nodes[k + 3]; val by = nodes[k + 4]; val bz = nodes[k + 5]
        val r = VINE_HALF_WIDTH
        if (box.maxX < min(ax, bx) - r || box.minX > max(ax, bx) + r || box.maxY < min(ay, by) - r ||
            box.minY > max(ay, by) + r || box.maxZ < min(az, bz) - r || box.minZ > max(az, bz) + r) return current
        val length = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))
        val steps = cubeSteps(length)
        val out = current ?: ArrayList(base)
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            val x = ax + (bx - ax) * t; val y = ay + (by - ay) * t; val z = az + (bz - az) * t
            out.add(Shapes.box(x - r, y - r, z - r, x + r, y + r, z + r))
        }
        return out
    }

    // Cube centres no farther apart than one half width, so the cubes overlap into a solid rope.
    private fun cubeSteps(length: Double): Int = ceil(length / VINE_HALF_WIDTH).toInt().coerceIn(1, MAX_CUBES_PER_LINK)

    private fun ensure(nodesNeeded: Int, vinesNeeded: Int) {
        if (nodes.size < nodesNeeded * 3) {
            nodes = nodes.copyOf(max(nodesNeeded * 3, nodes.size * 2))
            gaps = gaps.copyOf(nodes.size / 3)
        }
        if (ranges.size < vinesNeeded * 2) {
            ranges = ranges.copyOf(max(vinesNeeded * 2, ranges.size * 2))
            bounds = bounds.copyOf(ranges.size * 3)
        }
    }
}

private const val MAX_CONTACTS = 64

/** Reused output of [VineCollider.contacts]: link [node] to [node] + 1 of [chain], with a [weight]. */
class VineContacts {
    val chain = arrayOfNulls<Chain>(MAX_CONTACTS)
    val node = IntArray(MAX_CONTACTS)
    val t = DoubleArray(MAX_CONTACTS)
    val weight = DoubleArray(MAX_CONTACTS)
    var size = 0
        private set
    var totalWeight = 0.0
        private set

    fun clear() {
        java.util.Arrays.fill(chain, 0, size, null)
        size = 0
        totalWeight = 0.0
    }

    /** False when full; the caller stops there. */
    fun add(chain: Chain, node: Int, t: Double, weight: Double): Boolean {
        if (size >= MAX_CONTACTS) return false
        this.chain[size] = chain; this.node[size] = node; this.t[size] = t; this.weight[size] = weight
        size++
        totalWeight += weight
        return true
    }
}

/** Client-side collider; the client tick fills it, and only the client thread reads it. */
val clientVineCollider = VineCollider()

/** For the entity collision mixin: [base] with the vine cubes of [level] inside [box]. */
fun withVineShapes(level: Level, box: AABB, base: List<VoxelShape>): List<VoxelShape> {
    val collider = when {
        level is ServerLevel -> level.woggakVines.collider
        level.isClientSide -> clientVineCollider
        else -> return base
    }
    return collider.withShapes(box, base)
}
