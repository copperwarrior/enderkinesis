package org.shipwrights.enderkinesis.vine

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.ceil
import kotlin.math.max

/** Blocks from a vine's box to the nearest observer at or within which a sleeping vine wakes. */
internal const val VINE_WAKE_DISTANCE = 48.0
/** Blocks from every observer past which a natural vine sleeps; over [VINE_WAKE_DISTANCE], so a vine at the edge does not flap. */
internal const val VINE_SLEEP_DISTANCE = 80.0
/** Live vines checked for sleep each tick, in turn. */
internal const val VINE_SLEEP_CHECKS_PER_TICK = 64
/** Chunks of waiting records offered for a wake each tick, nearest first. */
internal const val VINE_WAKE_CHUNKS_PER_TICK = 32
/** Chain nodes made by wakes each tick; a whole chunk goes at once. */
internal const val VINE_WAKE_NODES_PER_TICK = 256
/** A ship wakes the vines on the path of its next seconds of travel. */
private const val SHIP_LEAD_SECONDS = 2.0
private const val OBSERVER_STRIDE = 6

/** A world-frame box round a vine's nodes or an observer. */
internal class VineBounds(
    var minX: Double = 0.0, var minY: Double = 0.0, var minZ: Double = 0.0,
    var maxX: Double = 0.0, var maxY: Double = 0.0, var maxZ: Double = 0.0,
) {
    /** Sets the box round the first [count] nodes of [nodes] (x, y, z triples). */
    fun set(nodes: DoubleArray, count: Int): VineBounds {
        require(count >= 1 && count * 3 <= nodes.size) { "Vine bounds of $count nodes" }
        minX = nodes[0]; minY = nodes[1]; minZ = nodes[2]
        maxX = minX; maxY = minY; maxZ = minZ
        for (k in 3 until count * 3 step 3) {
            minX = minOf(minX, nodes[k]); maxX = maxOf(maxX, nodes[k])
            minY = minOf(minY, nodes[k + 1]); maxY = maxOf(maxY, nodes[k + 1])
            minZ = minOf(minZ, nodes[k + 2]); maxZ = maxOf(maxZ, nodes[k + 2])
        }
        return this
    }

    fun distanceSquared(other: VineBounds): Double =
        boxDistanceSquared(other.minX, other.minY, other.minZ, other.maxX, other.maxY, other.maxZ)

    @Suppress("LongParameterList") // One box as six coordinates, so a caller needs no box object.
    fun boxDistanceSquared(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): Double {
        val x = max(0.0, max(minX - x1, x0 - maxX))
        val y = max(0.0, max(minY - y1, y0 - maxY))
        val z = max(0.0, max(minZ - z1, z0 - maxZ))
        return x * x + y * y + z * z
    }

    companion object {
        fun of(nodes: DoubleArray): VineBounds = VineBounds().set(nodes, nodes.size / 3)
    }
}

/** Chain nodes and chains that may be made this tick; the first batch over a limit still goes when nothing went before it. */
internal class VineBuildBudget(private val limit: Int, private val chainLimit: Int = Int.MAX_VALUE) {
    private var spent = 0
    private var chains = 0

    fun reset() {
        spent = 0
        chains = 0
    }

    fun take(nodes: Int, newChains: Int = 1): Boolean {
        require(nodes > 0 && newChains > 0) { "A build of $nodes nodes in $newChains chains" }
        if (spent > 0 && (nodes > limit - spent || newChains > chainLimit - chains)) return false
        spent += nodes
        chains += newChains
        return true
    }
}

/** Chain nodes that VS gives [record] again: it lays the path out at the record's spacing. */
internal fun wakeNodes(record: VineRecord): Int = ceil(record.restLength / record.spacing).toInt() + 1

/** Server-thread state of the vine sleep of one level; not saved, because a sleeping vine is a chunk record. */
internal class VineSleep {
    var cursor = 0
    /** Players and ships of this level as boxes, [OBSERVER_STRIDE] doubles each; filled each tick. */
    private var observers = DoubleArray(OBSERVER_STRIDE * 4)
    private var observerCount = 0
    /** VS chains that a player holds or rides, and vines that a capture uses; filled each tick. */
    val busyChains = LongOpenHashSet()
    val busyVines = LongOpenHashSet()
    /** Vines that another vine is joined to, and the home chunks of those targets; filled each tick. */
    val jointTargets = LongOpenHashSet()
    val jointHomes = LongOpenHashSet()
    /** The nearest chunks to wake this tick and their squared distances, sorted near to far. */
    val wakeChunks = LongArray(VINE_WAKE_CHUNKS_PER_TICK)
    val wakeDistances = DoubleArray(VINE_WAKE_CHUNKS_PER_TICK)
    var wakeCount = 0
    val budget = VineBuildBudget(VINE_WAKE_NODES_PER_TICK)
    /** The box of the live vine that the sleep check reads. */
    val bounds = VineBounds()

    fun clearObservers() {
        observerCount = 0
    }

    @Suppress("LongParameterList") // One box as six coordinates, so a caller needs no box object.
    fun addObserver(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) {
        if (observers.size < (observerCount + 1) * OBSERVER_STRIDE) observers = observers.copyOf(observers.size * 2)
        val k = observerCount++ * OBSERVER_STRIDE
        observers[k] = x0; observers[k + 1] = y0; observers[k + 2] = z0
        observers[k + 3] = x1; observers[k + 4] = y1; observers[k + 5] = z1
    }

    /** Squared blocks from [box] to the nearest observer; [Double.POSITIVE_INFINITY] with none. */
    fun nearestSquared(box: VineBounds): Double {
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until observerCount) {
            val k = i * OBSERVER_STRIDE
            val d = box.boxDistanceSquared(observers[k], observers[k + 1], observers[k + 2], observers[k + 3], observers[k + 4], observers[k + 5])
            if (d < best) best = d
        }
        return best
    }

    @OptIn(GameTickOnly::class)
    fun collectObservers(level: ServerLevel) {
        clearObservers()
        for (player in level.players()) {
            if (player.isSpectator) continue
            addObserver(player.x, player.y, player.z, player.x, player.y, player.z)
        }
        val dimension = level.dimensionId
        for (ship in level.shipObjectWorld.allShips) {
            if (ship.chunkClaimDimension != dimension) continue
            val box = ship.worldAABB
            val lead = ship.velocity.length() * SHIP_LEAD_SECONDS
            addObserver(box.minX() - lead, box.minY() - lead, box.minZ() - lead, box.maxX() + lead, box.maxY() + lead, box.maxZ() + lead)
        }
    }

    /** Keeps [chunk] when it is among the [VINE_WAKE_CHUNKS_PER_TICK] nearest offered this tick. */
    fun offerWake(chunk: Long, distance: Double) {
        if (wakeCount == wakeChunks.size && distance >= wakeDistances[wakeCount - 1]) return
        var at = if (wakeCount < wakeChunks.size) wakeCount++ else wakeCount - 1
        while (at > 0 && wakeDistances[at - 1] > distance) {
            wakeChunks[at] = wakeChunks[at - 1]
            wakeDistances[at] = wakeDistances[at - 1]
            at--
        }
        wakeChunks[at] = chunk
        wakeDistances[at] = distance
    }

    /** Fills the busy and joint sets, and clears [WoggakVine.natural] for good on each vine that is in use. */
    fun scanVines(data: WoggakVineData) {
        busyChains.clear()
        busyVines.clear()
        jointTargets.clear()
        jointHomes.clear()
        wakeCount = 0
        budget.reset()
        for (hold in data.holds.values) busyChains.add(hold.chain.value)
        for (rider in data.riding.riders.values) busyChains.add(rider.chain.value)
        for (capture in data.captures) for (strand in capture.strands) busyVines.add(strand.vineId)
        for (vine in data.vines) {
            if (vine.joinedEnds == 0) continue
            noteJoint(vine.start)
            noteJoint(vine.end)
        }
        for (vine in data.vines) if (vine.natural && inUse(data, vine)) vine.natural = false
    }

    fun inUse(data: WoggakVineData, vine: WoggakVine): Boolean =
        vine.shipEnds != 0 || vine.joinedEnds != 0 || jointTargets.contains(vine.id) ||
            busyVines.contains(vine.id) || busyChains.contains(vine.chain.value) ||
            data.reels.containsKey(vine.id) || data.throws.containsKey(vine.id)

    private fun noteJoint(anchor: WoggakVineAnchor) {
        val joint = anchor.point?.joint ?: return
        if (!anchor.isJoined) return
        jointTargets.add(joint.vine)
        jointHomes.add(joint.home)
    }
}

/** True when [vine] may leave physics: an untouched worldgen vine that is whole, at rest in its anchors and not in use. */
internal fun VineSleep.canSleep(data: WoggakVineData, vine: WoggakVine): Boolean =
    vine.natural && vine.fadeStart < 0 && vine.seedNodes == null && !inUse(data, vine)

/** Parks the next [VINE_SLEEP_CHECKS_PER_TICK] live vines in turn that may sleep and are past [VINE_SLEEP_DISTANCE]. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.sleepFarVines(data: WoggakVineData) {
    val sleep = data.sleep
    val chains = shipObjectWorld.allChains
    var checks = minOf(VINE_SLEEP_CHECKS_PER_TICK, data.vines.size)
    while (checks-- > 0 && data.vines.isNotEmpty()) {
        if (sleep.cursor >= data.vines.size) sleep.cursor = 0
        val vine = data.vines[sleep.cursor]
        // A parked vine leaves the list, so the cursor already points at the next one.
        val parked = sleep.canSleep(data, vine) && sleep.isFar(data, chains.getById(vine.chain)) && parkVine(data, sleep.cursor)
        if (!parked) sleep.cursor++
    }
}

private fun VineSleep.isFar(data: WoggakVineData, chain: ServerChain?): Boolean {
    val nodes = chain?.status?.nodeCount ?: return false
    if (nodes < 1) return false
    if (data.scratch.size < nodes * 3) data.scratch = DoubleArray(nodes * 3)
    if (chain.copyNodePositions(data.scratch) != nodes) return false
    return nearestSquared(bounds.set(data.scratch, nodes)) > VINE_SLEEP_DISTANCE * VINE_SLEEP_DISTANCE
}
