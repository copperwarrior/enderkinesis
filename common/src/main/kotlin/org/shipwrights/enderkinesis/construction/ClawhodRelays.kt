package org.shipwrights.enderkinesis.construction

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * One link of an item chain: take [item] at [supply] (the first link to a Clawhod only), or wait until a hand holds
 * it, then give it to [next], or put it in [toChest] (the last link of a chain from a miner).
 */
internal class RelayTask(
    val item: Item,
    val count: Int,
    val supply: BlockPos?,
    val next: ClawhodWorker?,
    val toChest: BlockPos?,
    /** The Clawhod the chain brings the item to; null on a chain that takes mined items to a chest. */
    val requester: ClawhodWorker?,
    /** The job whose owner's actor runs the permission checks; null for mined items (the miner profile). */
    val jobId: UUID?,
    /** The chests a link puts the item back in when the chain breaks. */
    val chests: List<BlockPos>,
    /** The items are this Clawhod's own (a miner sends them): a broken chain does not return them. */
    val own: Boolean = false,
    /** The link that passes the items to this one; a later link waits only while it still does. */
    val prev: ClawhodWorker? = null,
    /** A broken chain's items on their way back: a break returns them again, unlike a miner's own load. */
    val returning: Boolean = false,
)

/** The loaded Clawhod workers of one level, for item chains. Server thread only; not saved. */
internal class ClawhodRelays {
    private val workers = Object2ObjectOpenHashMap<UUID, ClawhodWorker>()

    fun touch(id: UUID, worker: ClawhodWorker) {
        if (workers[id] !== worker) workers[id] = worker
    }

    fun remove(id: UUID, worker: ClawhodWorker) {
        if (workers[id] === worker) workers.remove(id)
        peerTick = -1L
    }

    private val peersByJob = Object2ObjectOpenHashMap<UUID, ArrayList<ClawhodWorker>>()
    private var peerTick = -1L

    /** The workers on job [jobId], built once per [gameTime] so each worker skips a scan of all workers. */
    fun peersOf(jobId: UUID, gameTime: Long): List<ClawhodWorker> {
        if (peerTick != gameTime) {
            peersByJob.clear()
            for (w in workers.values) {
                val j = w.jobId ?: continue
                peersByJob.getOrPut(j) { ArrayList() }.add(w)
            }
            peerTick = gameTime
        }
        return peersByJob[jobId] ?: emptyList()
    }

    fun contains(worker: ClawhodWorker): Boolean = workers[worker.id] === worker

    val all: Collection<ClawhodWorker> get() = workers.values

    val lids = ContainerLids()
}

/** How many of [item] (with no tags) [slots] hold. */
internal fun slotCount(slots: List<ItemStack>, item: Item): Int {
    var n = 0
    for (slot in slots) if (slot.`is`(item) && !slot.hasTag()) n += slot.count
    return n
}

/** Takes up to [count] of [item] (with no tags) out of [slots], last slot first; returns how many. */
internal fun slotTake(slots: MutableList<ItemStack>, item: Item, count: Int): Int {
    require(count >= 0) { "Count must not be negative: $count" }
    var left = count
    for (i in slots.indices.reversed()) {
        if (left == 0) break
        val slot = slots[i]
        if (!slot.`is`(item) || slot.hasTag()) continue
        val n = minOf(left, slot.count)
        slot.shrink(n)
        if (slot.isEmpty) slots[i] = ItemStack.EMPTY
        left -= n
    }
    return count - left
}

/** Room for [item] in a link's hand: a block keeps the one-block-type rule of a hand, other items any free room. */
internal fun relayRoom(slots: List<ItemStack>, item: Item): Int {
    val stack = ItemStack(item)
    val block = clawhodBlockOf(stack)
    return if (block != null) handRoom(slots, block) else pickupRoom(slots, stack)
}

/**
 * The shortest chain of nodes, by breadth-first search, from a node that reaches the supply to a node that reaches
 * the requester; each step joins two nodes whose points ([x], [y], [z]) are within [reach]. Supply side first, or
 * null when none joins them. [queue] and [from] are scratch of at least [count] entries (Rule 1: no recursion).
 */
@Suppress("LongParameterList") // Plain arrays keep it testable without a level.
internal fun relayPath(
    x: DoubleArray, y: DoubleArray, z: DoubleArray, count: Int,
    reachesRequester: (Int) -> Boolean, reachesSupply: (Int) -> Boolean, reach: Double,
    queue: IntArray, from: IntArray,
): IntArray? {
    require(count in 0..minOf(x.size, y.size, z.size, queue.size, from.size)) { "Count $count is past the arrays" }
    val reachSq = reach * reach
    from.fill(UNSEEN, 0, count)
    var head = 0
    var tail = 0
    for (i in 0 until count) {
        if (!reachesRequester(i)) continue
        from[i] = START
        queue[tail++] = i
    }
    while (head < tail) {
        val cur = queue[head++]
        if (reachesSupply(cur)) return walkBack(cur, from)
        for (j in 0 until count) {
            if (from[j] != UNSEEN) continue
            val dx = x[j] - x[cur]
            val dy = y[j] - y[cur]
            val dz = z[j] - z[cur]
            if (dx * dx + dy * dy + dz * dz > reachSq) continue
            from[j] = cur
            queue[tail++] = j
        }
    }
    return null
}

// From the supply-side node back to a node at the requester: that order is the item's way.
private fun walkBack(end: Int, from: IntArray): IntArray {
    var n = 1
    var i = end
    while (from[i] != START) {
        i = from[i]
        n++
    }
    val out = IntArray(n)
    i = end
    for (k in 0 until n) {
        out[k] = i
        if (from[i] != START) i = from[i]
    }
    return out
}

private const val UNSEEN = -2
private const val START = -1
