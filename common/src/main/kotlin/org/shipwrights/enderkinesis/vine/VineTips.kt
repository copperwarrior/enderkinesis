package org.shipwrights.enderkinesis.vine

import dev.architectury.event.events.common.LifecycleEvent
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.server.level.ServerLevel
import org.joml.Vector3d
import org.shipwrights.enderkinesis.physics.physicsStepSeconds
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.chains.ChainEnd
import org.valkyrienskies.core.api.chains.ChainEndpoint
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.particles.ParticleId
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.max
import kotlin.math.sqrt

/** Eating one link of a two-link vine leaves at most one link, so no bite deletes a whole vine. */
private const val MIN_EDIBLE_NODES = 3
/** A claim lasts this many ticks after the last pin, so a claim that no one let go of ends by itself. */
private const val CHEW_CLAIM_TICKS = 2L
/** A link at the held pair longer than this many spacings means the mouth pulls the vine taut. */
private const val MAX_CHEW_STRETCH = 2.0
/** Share of the hold's position error removed in one physics tick, as for the vine joints. */
private const val HOLD_CORRECTION = 0.2
/** Nodes one scan reads at most. */
private const val MAX_SCAN_NODES = 8192

/** Rejects a node with NaN, else gives the ground height under it. */
internal fun interface VineTipFilter {
    fun groundUnder(nodeX: Double, nodeY: Double, nodeZ: Double): Double
}

/**
 * One vine link that an animal may eat: the bitten [node] and its [partner] node, which lie across the mouth.
 * [tipEnd] is set when [node] is a free end; VS then holds it as an endpoint, else a [NodeHold] holds it.
 */
internal class VineTip {
    var vineId = 0L
        internal set
    internal var chain = ChainId(0L)
    var node = 0
        internal set
    var partner = 0
        internal set
    internal var nodeCount = 0
    var tipEnd: ChainEnd? = null
        internal set
    val isTip: Boolean get() = tipEnd != null
    var x = 0.0
        internal set
    var y = 0.0
        internal set
    var z = 0.0
        internal set
    var nextX = 0.0
        internal set
    var nextY = 0.0
        internal set
    var nextZ = 0.0
        internal set
    var groundY = 0.0
        internal set
    var spacing = VINE_TARGET_SPACING
        internal set
    internal var nodeHold: NodeHold? = null
    internal var partnerHold: NodeHold? = null
}

/**
 * Holds one chain node at a world point from the physics thread; VS has no endpoint for a node inside a chain.
 * The server thread moves the target; a torn read of it moves the node a little for one step.
 */
internal class NodeHold(val particle: ParticleId) {
    @Volatile var x = 0.0
    @Volatile var y = 0.0
    @Volatile var z = 0.0
    @Volatile var broken = false
}

// Server thread publishes, physics thread reads; each array is replaced, never changed.
private val publishedHolds = ConcurrentHashMap<DimensionId, Array<NodeHold>>()

@OptIn(VsBeta::class, PhysTickOnly::class)
internal fun registerVineTipHolds() {
    vsApi.physTickEvent.on { event, _ -> event.world.solveNodeHolds(physicsStepSeconds(event.delta)) }
    LifecycleEvent.SERVER_LEVEL_UNLOAD.register { level -> publishedHolds.remove(level.dimensionId) }
    LifecycleEvent.SERVER_STOPPED.register { publishedHolds.clear() }
}

@OptIn(PhysTickOnly::class)
private fun PhysLevel.solveNodeHolds(dt: Double) {
    val holds = publishedHolds[dimension] ?: return
    val k = HOLD_CORRECTION / dt
    val maxImpulse = VINE_BREAK_FORCE * dt
    for (hold in holds) {
        if (hold.broken) continue
        val p = getParticleById(hold.particle) ?: continue
        val pos = p.position
        val v = p.velocity
        val jx = -p.mass * (v.x() + k * (pos.x() - hold.x))
        val jy = -p.mass * (v.y() + k * (pos.y() - hold.y))
        val jz = -p.mass * (v.z() + k * (pos.z() - hold.z))
        val size = sqrt(jx * jx + jy * jy + jz * jz)
        if (!size.isFinite() || size <= 0.0) continue
        if (size > maxImpulse) { hold.broken = true; continue }
        if (p.isSleeping) p.wake()
        // VS queues the impulse vector by reference, so each one is fresh.
        p.applyWorldImpulse(Vector3d(jx, jy, jz))
    }
}

private fun ServerLevel.publishHold(hold: NodeHold) {
    publishedHolds.compute(dimensionId) { _, old -> if (old == null) arrayOf(hold) else old + hold }
}

private fun ServerLevel.unpublishHold(hold: NodeHold) {
    publishedHolds.computeIfPresent(dimensionId) { _, old -> old.filter { it !== hold }.toTypedArray().takeIf { it.isNotEmpty() } }
}

/**
 * The nearest edible vine node within [radius] blocks across and [height] blocks up or down of ([x], [y], [z])
 * that [filter] accepts, into [out]; false when there is none. Skips vine [skipVine].
 */
@Suppress("LongParameterList") // A point, two bounds, a filter and one skip: plain values keep the scan free of allocation.
@OptIn(GameTickOnly::class)
internal fun ServerLevel.findEdibleVineTip(
    out: VineTip, x: Double, y: Double, z: Double, radius: Double, height: Double, filter: VineTipFilter, skipVine: Long,
): Boolean {
    val data = loadedWoggakVines ?: return false
    val chains = shipObjectWorld.allChains
    var best = radius * radius
    var found: WoggakVine? = null
    var read = 0
    for (i in 0 until data.vines.size) {
        val vine = data.vines[i]
        if (vine.id == skipVine || isClaimed(data, vine.id)) continue
        val chain = chains.getById(vine.chain) ?: continue
        if (inedibleReason(data, vine, chain) != null) continue
        val nodes = chain.status.nodeCount
        if (read + nodes > MAX_SCAN_NODES) break
        read += nodes
        if (data.scratch.size < nodes * 3) data.scratch = DoubleArray(nodes * 3)
        if (chain.copyNodePositions(data.scratch) != nodes) continue
        val p = data.scratch
        for (n in 0 until nodes) {
            val k = n * 3
            val dx = p[k] - x
            val dy = p[k + 1] - y
            val dz = p[k + 2] - z
            val distSqr = dx * dx + dz * dz
            if (dy < -height || dy > height || distSqr >= best) continue
            val partner = vine.partnerOf(n, nodes)
            if (partner < 0) continue
            val ground = filter.groundUnder(p[k], p[k + 1], p[k + 2])
            if (ground.isNaN()) continue
            best = distSqr
            found = vine
            out.node = n
            out.partner = partner
            out.nodeCount = nodes
            out.groundY = ground
        }
    }
    val vine = found ?: return false
    out.vineId = vine.id
    out.chain = vine.chain
    out.spacing = vine.spacing
    out.tipEnd = when (out.node) {
        0 -> ChainEnd.START
        out.nodeCount - 1 -> ChainEnd.END
        else -> null
    }
    val chain = chains.getById(vine.chain) ?: return false
    return readPair(chain, out)
}

/** Refreshes [tip]'s places; returns why it may no longer be eaten, or null. [pinned]: this caller holds it. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.checkVineTip(tip: VineTip, pinned: Boolean): String? {
    val data = loadedWoggakVines ?: return "no vine data"
    val vine = data.vine(tip.vineId) ?: return "vine gone"
    if (vine.chain != tip.chain) return "vine rebuilt"
    val chain = shipObjectWorld.allChains.getById(vine.chain) ?: return "chain gone"
    inedibleReason(data, vine, chain)?.let { return it }
    if (chain.status.nodeCount != tip.nodeCount) return "node count changed"
    if (vine.partnerOf(tip.node, tip.nodeCount) != tip.partner) return "an end got anchored"
    if (!pinned && isClaimed(data, vine.id)) return "another animal chews it"
    if (!readPair(chain, tip)) return "node gone"
    if (tip.nodeHold?.broken == true || tip.partnerHold?.broken == true) return "mouth hold broke"
    if (pinned && pairStretch(chain, tip) > vine.spacing * MAX_CHEW_STRETCH) return "vine pulled taut"
    return null
}

/** Holds [tip]'s bitten node at ([x], [y], [z]) this tick and claims the vine. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.pinVineTip(tip: VineTip, x: Double, y: Double, z: Double): Boolean {
    val data = loadedWoggakVines ?: return false
    val vine = data.vine(tip.vineId) ?: return false
    val chain = shipObjectWorld.allChains.getById(vine.chain) ?: return false
    // A pinned worldgen vine must not sleep, and the clients get the change.
    if (!data.chewedTips.containsKey(vine.id)) data.touch(vine)
    data.chewedTips.put(vine.id, gameTime)
    val end = tip.tipEnd
    if (end != null) {
        // VS keeps the endpoint vector, so each tick gets a fresh one.
        chain.setEndpoint(end, ChainEndpoint.World(Vector3d(x, y, z)))
        return true
    }
    val hold = tip.nodeHold ?: newHold(chain, tip.node)?.also { tip.nodeHold = it } ?: return false
    hold.x = x; hold.y = y; hold.z = z
    return true
}

/** Holds [tip]'s partner node at ([x], [y], [z]); call it while the bitten node is pinned. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.holdVineTipNext(tip: VineTip, x: Double, y: Double, z: Double): Boolean {
    val hold = tip.partnerHold ?: run {
        val vine = loadedWoggakVines?.vine(tip.vineId) ?: return false
        val chain = shipObjectWorld.allChains.getById(vine.chain) ?: return false
        newHold(chain, tip.partner)?.also { tip.partnerHold = it } ?: return false
    }
    hold.x = x; hold.y = y; hold.z = z
    return true
}

/** Sets [tip]'s nodes free again and drops the claim. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.releaseVineTip(tip: VineTip) {
    tip.nodeHold?.let(::unpublishHold)
    tip.nodeHold = null
    tip.partnerHold?.let(::unpublishHold)
    tip.partnerHold = null
    val data = loadedWoggakVines ?: return
    data.chewedTips.remove(tip.vineId)
    val end = tip.tipEnd ?: return
    val vine = data.vine(tip.vineId) ?: return
    if (vine.chain != tip.chain || !vine.anchorAt(end).released) return
    val chain = shipObjectWorld.allChains.getById(vine.chain) ?: return
    if (chain.status.lifecycle == ChainLifecycle.ACTIVE) chain.setEndpoint(end, ChainEndpoint.Free)
}

/**
 * Releases [tip] and cuts the vine at its link with no drop. A middle link snaps the vine in two; the split drops a
 * piece of one node, and each other piece keeps its own anchor or free end.
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.eatVineTip(tip: VineTip): Boolean {
    releaseVineTip(tip)
    val data = loadedWoggakVines ?: return false
    val vine = data.vine(tip.vineId) ?: return false
    val chain = shipObjectWorld.allChains.getById(vine.chain) ?: return false
    if (vine.chain != tip.chain || chain.status.nodeCount != tip.nodeCount) return false
    if (!splitWoggakVine(vine, chain, false, minOf(tip.node, tip.partner))) return false
    data.remove(vine)
    data.anchorsDirty = true
    data.jointsDirty = true
    data.setDirty()
    return true
}

@OptIn(GameTickOnly::class)
private fun ServerLevel.newHold(chain: ServerChain, node: Int): NodeHold? {
    val ids = chain.nodeIds
    if (node !in ids.indices) return null
    return NodeHold(ids[node]).also(::publishHold)
}

private fun WoggakVine.anchorAt(which: ChainEnd): WoggakVineAnchor = if (which == ChainEnd.START) start else end

/** A held node must not be an anchored end: the hold would fight the anchor. */
private fun WoggakVine.isFreeNode(node: Int, nodes: Int): Boolean = when (node) {
    0 -> start.released
    nodes - 1 -> end.released
    else -> node in 1 until nodes - 1
}

/** The node that lies across the mouth with [node]: the inner neighbour of an end, else the next free neighbour; -1 for none. */
private fun WoggakVine.partnerOf(node: Int, nodes: Int): Int {
    if (!isFreeNode(node, nodes)) return -1
    return when {
        node == 0 -> 1
        node == nodes - 1 -> nodes - 2
        isFreeNode(node + 1, nodes) -> node + 1
        isFreeNode(node - 1, nodes) -> node - 1
        else -> -1
    }
}

private fun ServerLevel.isClaimed(data: WoggakVineData, vineId: Long): Boolean =
    data.chewedTips.containsKey(vineId) && data.chewedTips.get(vineId) >= gameTime - CHEW_CLAIM_TICKS

/** Why no link of [vine] is edible, or null. The busy sets are one tick old; the next check catches a change. */
@OptIn(GameTickOnly::class)
private fun inedibleReason(data: WoggakVineData, vine: WoggakVine, chain: ServerChain): String? {
    val status = chain.status
    return when {
        vine.fadeStart >= 0 -> "vine fading"
        status.lifecycle != ChainLifecycle.ACTIVE -> "chain not active"
        status.brokenLinks.isNotEmpty() -> "link broken"
        status.nodeCount < MIN_EDIBLE_NODES -> "vine too short"
        status.reel != null || data.reels.containsKey(vine.id) -> "vine reeled"
        data.throws.containsKey(vine.id) -> "vine thrown"
        data.sleep.busyChains.contains(vine.chain.value) -> "vine held or climbed"
        data.sleep.busyVines.contains(vine.id) || isCaptureLink(data, vine.id) -> "vine captured"
        data.sleep.jointTargets.contains(vine.id) -> "another vine is joined to it"
        else -> null
    }
}

private fun isCaptureLink(data: WoggakVineData, vineId: Long): Boolean {
    for (i in 0 until data.captures.size) {
        val links = data.captures[i].links
        for (j in 0 until links.size) if (links[j] == vineId) return true
    }
    return false
}

/** Copies the world places of [tip]'s node and partner into it; false when one is not found. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.readPair(chain: ServerChain, tip: VineTip): Boolean {
    val ids = chain.nodeIds
    if (tip.node !in ids.indices || tip.partner !in ids.indices) return false
    val particles = shipObjectWorld.allParticles
    val p = particles.getById(ids[tip.node])?.position ?: return false
    val n = particles.getById(ids[tip.partner])?.position ?: return false
    if (!p.isFinite || !n.isFinite) return false
    tip.x = p.x(); tip.y = p.y(); tip.z = p.z()
    tip.nextX = n.x(); tip.nextY = n.y(); tip.nextZ = n.z()
    return true
}

/** The longest link at the held pair: the eaten link and the link on each side of it, where they exist. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.pairStretch(chain: ServerChain, tip: VineTip): Double {
    val ids = chain.nodeIds
    val dx = tip.nextX - tip.x
    val dy = tip.nextY - tip.y
    val dz = tip.nextZ - tip.z
    var longest = sqrt(dx * dx + dy * dy + dz * dz)
    val lo = minOf(tip.node, tip.partner)
    val hi = maxOf(tip.node, tip.partner)
    val loIsNode = lo == tip.node
    val particles = shipObjectWorld.allParticles
    if (lo - 1 >= 0) particles.getById(ids[lo - 1])?.position?.let {
        longest = max(longest, if (loIsNode) it.distance(tip.x, tip.y, tip.z) else it.distance(tip.nextX, tip.nextY, tip.nextZ))
    }
    if (hi + 1 < ids.size) particles.getById(ids[hi + 1])?.position?.let {
        longest = max(longest, if (loIsNode) it.distance(tip.nextX, tip.nextY, tip.nextZ) else it.distance(tip.x, tip.y, tip.z))
    }
    return longest
}
