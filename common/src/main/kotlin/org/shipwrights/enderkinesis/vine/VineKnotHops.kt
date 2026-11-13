package org.shipwrights.enderkinesis.vine

import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.util.GameTickOnly
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** A knot with more ways out than this drops the rest; a knot joins few vines. */
private const val MAX_WAYS = 16
/** The current way wins a near tie, so two vines that go the same way do not swap the hold back and forth. */
internal const val KEEP_WAY_BIAS = 0.05
private const val ON_NODE = 1e-9
private const val LAST_NODE = -1

/** The ways out of one knot node: each is a chain, the node on it, and a node direction. Reused, so it does not allocate. */
internal class JunctionWays {
    var size = 0
        private set
    val chain = LongArray(MAX_WAYS)
    val node = IntArray(MAX_WAYS)
    val dir = IntArray(MAX_WAYS)
    private val tx = DoubleArray(MAX_WAYS)
    private val ty = DoubleArray(MAX_WAYS)
    private val tz = DoubleArray(MAX_WAYS)
    /** The score of the way that the last [best] chose. */
    var bestScore = 0.0
        private set

    fun clear() {
        size = 0
    }

    @Suppress("LongParameterList") // Plain doubles keep the hold tick free of allocation.
    fun add(chainId: Long, at: Int, way: Int, x: Double, y: Double, z: Double) {
        if (size >= MAX_WAYS) return
        chain[size] = chainId; node[size] = at; dir[size] = way
        tx[size] = x; ty[size] = y; tz[size] = z
        size++
    }

    /** Adds the ways along the vine [chainId] from [at]: to the next node and to the one before, where they exist. */
    fun addVineWays(chainId: Long, nodes: DoubleArray, count: Int, at: Int) {
        if (at < 0 || at >= count) return
        val k = at * 3
        if (at + 1 < count) add(chainId, at, 1, nodes[k + 3] - nodes[k], nodes[k + 4] - nodes[k + 1], nodes[k + 5] - nodes[k + 2])
        if (at > 0) add(chainId, at, -1, nodes[k - 3] - nodes[k], nodes[k - 2] - nodes[k + 1], nodes[k - 1] - nodes[k + 2])
    }

    /**
     * The index of the way that goes most along the wish ([wx], [wy], [wz]), or -1 when none reaches [minCos]. The way
     * [keepChain] in [keepDir] gets [KEEP_WAY_BIAS].
     */
    @Suppress("LongParameterList") // Plain doubles keep the hold tick free of allocation.
    fun best(wx: Double, wy: Double, wz: Double, minCos: Double, keepChain: Long, keepDir: Int): Int {
        var best = -1
        var bestRank = Double.NEGATIVE_INFINITY
        for (i in 0 until size) {
            val score = alongWish(tx[i], ty[i], tz[i], wx, wy, wz)
            if (score < minCos) continue
            val rank = if (chain[i] == keepChain && dir[i] == keepDir) score + KEEP_WAY_BIAS else score
            if (rank > bestRank) {
                bestRank = rank
                best = i
                bestScore = score
            }
        }
        return best
    }
}

/** The node that [grip] sits on, or -1 when it is between nodes. */
internal fun nodeAt(grip: Double): Int {
    val n = Math.round(grip)
    return if (abs(grip - n) <= ON_NODE) n.toInt() else -1
}

/**
 * The first node that a move from grip [from] to [to] reaches or passes, for which [isKnot] is true; -1 when none.
 * The node that the move starts on does not count.
 */
internal inline fun firstNodeCrossed(from: Double, to: Double, isKnot: (Int) -> Boolean): Int {
    if (to > from) {
        // Bounded: the move spans at most the node count of the vine.
        var n = floor(from).toInt() + 1
        while (n <= floor(to).toInt()) { if (isKnot(n)) return n; n++ }
    } else if (to < from) {
        var n = ceil(from).toInt() - 1
        while (n >= ceil(to).toInt()) { if (isKnot(n)) return n; n-- }
    }
    return -1
}

/**
 * Fills [VineHold.ways] with every way out of [node] of the held vine: both ways along it, the way along each vine
 * whose end is joined to it, and both ways along the vine that a held end at [node] is joined to. Needs the [count]
 * held nodes in [WoggakVineData.scratch]. False when no other vine is joined at [node].
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.junctionWays(data: WoggakVineData, hold: VineHold, count: Int, node: Int): Boolean {
    val ways = hold.ways
    ways.clear()
    ways.addVineWays(hold.chain.value, data.scratch, count, node)
    val own = ways.size
    var heldId = 0L
    for (vine in data.vines) if (vine.chain == hold.chain && vine.fadeStart < 0) { heldId = vine.id; break }
    if (heldId == 0L) return false
    for (vine in data.vines) {
        if (vine.fadeStart >= 0) continue
        val held = vine.chain == hold.chain
        for (side in 0..1) {
            val anchor = if (side == 0) vine.start else vine.end
            val joint = (if (anchor.isJoined) anchor.point?.joint else null) ?: continue
            if (held) {
                if (node != (if (side == 0) 0 else count - 1)) continue
                // A target that is not loaded has no chain to climb on.
                val target = data.vine(joint.vine) ?: continue
                addChainWays(hold, target.chain.value, joint.node)
            } else if (joint.vine == heldId && joint.node == node) {
                addChainWays(hold, vine.chain.value, if (side == 0) 0 else LAST_NODE)
            }
        }
    }
    return ways.size > own
}

/** Adds the ways along [chainId] from [node]; [LAST_NODE] means its last node. Uses [VineHold.otherNodes] as scratch. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.addChainWays(hold: VineHold, chainId: Long, node: Int) {
    val chain = liveChain(ChainId(chainId), 0) ?: return
    val count = chain.status.nodeCount
    if (hold.otherNodes.size < count * 3) hold.otherNodes = DoubleArray(count * 3)
    if (chain.copyNodePositions(hold.otherNodes) != count) return
    hold.ways.addVineWays(chainId, hold.otherNodes, count, if (node == LAST_NODE) count - 1 else node)
}
