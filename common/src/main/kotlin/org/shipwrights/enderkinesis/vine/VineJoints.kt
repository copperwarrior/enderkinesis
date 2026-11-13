package org.shipwrights.enderkinesis.vine

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.physics.physicsStepSeconds
import org.shipwrights.enderkinesis.util.pointVelocity
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.chains.ChainEnd
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
import kotlin.math.sqrt

// Fake joint until VS exposes a chain-node endpoint: the joined end is free, and each physics tick equal and
// opposite impulses pull it and the target node together, so both vines carry the load.

/** Share of the position error removed in one physics tick; low enough that the correction does not overshoot. */
private const val POSITION_CORRECTION = 0.2

/** A joint to node [node] of vine [vine] (a WoggakVine id); [home] is that vine's chunk, where it waits while unloaded. */
class VineJoint(vine: Long, node: Int, home: Long) {
    /** A split moves the joint to the piece that holds its node. */
    var vine = vine
        internal set
    var home = home
        internal set
    /** A shorten at the target's start moves its nodes down by one. */
    var node = node
        internal set

    fun save(): CompoundTag = CompoundTag().apply {
        putLong("vine", vine)
        putInt("node", node)
        putLong("home", home)
    }

    companion object {
        fun load(tag: CompoundTag) = VineJoint(tag.getLong("vine"), tag.getInt("node"), tag.getLong("home"))
    }
}

internal const val NO_CONTACTS = 0
private const val ALL_CONTACTS = -1

/** Gives the joined node of this pair its contacts back when the joint lets go. */
@OptIn(GameTickOnly::class)
internal fun JointPair.restoreContacts(level: ServerLevel) {
    level.shipObjectWorld.allParticles.getById(follower)?.setCollisionMask(ALL_CONTACTS)
}

/**
 * Pulls [follower] to a point on the link from [target] to [second], at [weight] from [target]. The physics thread
 * sets [broken]; the server thread reads it and moves [weight] while a player climbs.
 */
class JointPair(val follower: ParticleId, val target: ParticleId, val second: ParticleId? = null) {
    @Volatile var broken = false
    @Volatile var weight = 0.0
}

/**
 * Holds [particle], node [node] of its vine, at [local], a shipyard point of ship [shipId]. The physics thread sets
 * [broken] and alone uses [point] and [pointVelocity] as scratch.
 */
class HullGrip(val particle: ParticleId, val shipId: Long, local: Vector3dc, val node: Int) {
    val local: Vector3dc = Vector3d(local)
    @Volatile var broken = false
    internal val point = Vector3d()
    internal val pointVelocity = Vector3d()
}

// Server thread publishes, physics thread reads; each array is replaced, never changed.
private val publishedPairs = ConcurrentHashMap<DimensionId, Array<JointPair>>()
private val publishedGrips = ConcurrentHashMap<DimensionId, Array<HullGrip>>()

/** Current world position of the joined node, or null when the target vine is gone or not loaded. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.jointPosition(joint: VineJoint): Vector3d? {
    val data = woggakVines
    val vine = data.vine(joint.vine) ?: return null
    val target = shipObjectWorld.allChains.getById(vine.chain) ?: return null
    val status = target.status
    if (status.lifecycle != ChainLifecycle.ACTIVE) return null
    val nodes = status.nodeCount
    if (nodes < 1) return null
    if (data.scratch.size < nodes * 3) data.scratch = DoubleArray(nodes * 3)
    if (target.copyNodePositions(data.scratch) != nodes) return null
    val k = jointNode(joint, nodes) * 3
    return Vector3d(data.scratch[k], data.scratch[k + 1], data.scratch[k + 2])
}

/**
 * Keeps the joint of [anchor] on [end] of [chain] alive. Returns false when the target vine is gone or the joint
 * broke, so the caller frees the end. A target that is only pending, suspended or in an unloaded chunk keeps the joint.
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.followJoint(chain: ServerChain, end: ChainEnd, anchor: WoggakVineAnchor, joint: VineJoint): Boolean {
    anchor.pair?.let { if (it.broken) return false }
    val data = woggakVines
    val vine = data.vine(joint.vine) ?: return isJointWaiting(data, joint)
    val target = shipObjectWorld.allChains.getById(vine.chain) ?: return false
    when (target.status.lifecycle) {
        ChainLifecycle.PENDING, ChainLifecycle.SUSPENDED -> return true
        ChainLifecycle.REMOVED, ChainLifecycle.FAILED -> return false
        ChainLifecycle.ACTIVE -> Unit
    }
    if (anchor.pair == null) {
        // Node ids are fixed for the life of a chain, so one lookup holds until the vine is split or removed.
        val own = chain.nodeIds
        val other = target.nodeIds
        if (own.isEmpty() || other.isEmpty()) return false
        val follower = if (end == ChainEnd.START) own.first() else own.last()
        anchor.pair = JointPair(follower, other[jointNode(joint, other.size)])
        // The joined node sits on the target node, and a contact would push them apart. Mask 0 stops its contacts,
        // as vs-core's ParticleChainApiTest does for a node and its anchor body; the native collideConnected is not public.
        shipObjectWorld.allParticles.getById(follower)?.setCollisionMask(NO_CONTACTS)
        data.jointsDirty = true
    }
    return true
}

/** VS lays a rebuilt target out again, so its node count can change by one; the joint then holds its last node. */
internal fun jointNode(joint: VineJoint, nodes: Int): Int = joint.node.coerceIn(0, nodes - 1)

/** Sends the live joint pairs of this level to the physics thread when they changed. */
internal fun ServerLevel.publishJointPairs(data: WoggakVineData) {
    if (!data.jointsDirty) return
    data.jointsDirty = false
    val pairs = ArrayList<JointPair>()
    for (vine in data.vines) {
        if (vine.start.isJoined) vine.start.pair?.let(pairs::add)
        if (vine.end.isJoined) vine.end.pair?.let(pairs::add)
    }
    for (hold in data.holds.values) pairs.add(hold.pair)
    for (rider in data.riding.riders.values) pairs.add(rider.pair)
    if (pairs.isEmpty()) publishedPairs.remove(dimensionId) else publishedPairs[dimensionId] = pairs.toTypedArray()
    val grips = ArrayList<HullGrip>()
    for (vine in data.vines) vine.hullGrips?.let { grips.addAll(it) }
    if (grips.isEmpty()) publishedGrips.remove(dimensionId) else publishedGrips[dimensionId] = grips.toTypedArray()
}

@OptIn(VsBeta::class, PhysTickOnly::class)
fun registerVineJoints() {
    vsApi.physTickEvent.on { event, _ -> event.world.solveJoints(physicsStepSeconds(event.delta)) }
}

fun clearVineJoints() {
    publishedPairs.clear()
    publishedGrips.clear()
}

@OptIn(PhysTickOnly::class)
private fun PhysLevel.solveJoints(dt: Double) {
    solveGrips(dt)
    val pairs = publishedPairs[dimension] ?: return
    val maxImpulse = VINE_BREAK_FORCE * dt
    for (pair in pairs) {
        if (pair.broken) continue
        val a = getParticleById(pair.follower) ?: continue
        val b = getParticleById(pair.target) ?: continue
        val secondId = pair.second
        val c = if (secondId == null) null else getParticleById(secondId) ?: continue
        val wc = if (c == null) 0.0 else pair.weight.coerceIn(0.0, 1.0)
        val wb = 1.0 - wc
        val pa = a.position; val va = a.velocity
        val pb = b.position; val vb = b.velocity
        val pc = c?.position ?: pb; val vc = c?.velocity ?: vb
        // The joint point is a weighted point of two nodes, so its inverse mass is the weighted sum of theirs.
        val reduced = 1.0 / (1.0 / a.mass + wb * wb / b.mass + (if (c == null) 0.0 else wc * wc / c.mass))
        if (!reduced.isFinite() || reduced <= 0.0) continue
        // Soft constraint: remove the relative velocity and a share of the gap in this tick.
        val k = POSITION_CORRECTION / dt
        val jx = -reduced * ((va.x() - vb.x() * wb - vc.x() * wc) + k * (pa.x() - pb.x() * wb - pc.x() * wc))
        val jy = -reduced * ((va.y() - vb.y() * wb - vc.y() * wc) + k * (pa.y() - pb.y() * wb - pc.y() * wc))
        val jz = -reduced * ((va.z() - vb.z() * wb - vc.z() * wc) + k * (pa.z() - pb.z() * wb - pc.z() * wc))
        val size = sqrt(jx * jx + jy * jy + jz * jz)
        if (!size.isFinite() || size <= 0.0) continue
        // The same strength as a vine link; past it the joint lets go, as a link would.
        if (size > maxImpulse) { pair.broken = true; continue }
        if (a.isSleeping) a.wake()
        if (b.isSleeping) b.wake()
        a.applyWorldImpulse(Vector3d(jx, jy, jz))
        b.applyWorldImpulse(Vector3d(-jx * wb, -jy * wb, -jz * wb))
        if (c != null && wc > 0.0) {
            if (c.isSleeping) c.wake()
            c.applyWorldImpulse(Vector3d(-jx * wc, -jy * wc, -jz * wc))
        }
    }
}

/** As a joint pair, with the ship point as the target; the ship takes the opposite pull as a force at that point. */
@OptIn(PhysTickOnly::class, VsBeta::class)
private fun PhysLevel.solveGrips(dt: Double) {
    val grips = publishedGrips[dimension] ?: return
    val maxImpulse = VINE_BREAK_FORCE * dt
    val k = POSITION_CORRECTION / dt
    for (grip in grips) {
        if (grip.broken) continue
        val a = getParticleById(grip.particle) ?: continue
        val ship = getShipById(grip.shipId) ?: continue
        ship.transform.shipToWorld.transformPosition(grip.local, grip.point)
        ship.pointVelocity(grip.point, grip.pointVelocity)
        val shipMass = if (ship.isStatic) 0.0 else ship.mass
        val reduced = if (shipMass > 0.0) a.mass * shipMass / (a.mass + shipMass) else a.mass
        if (!reduced.isFinite() || reduced <= 0.0) continue
        val pa = a.position; val va = a.velocity
        val p = grip.point; val v = grip.pointVelocity
        val jx = -reduced * ((va.x() - v.x) + k * (pa.x() - p.x))
        val jy = -reduced * ((va.y() - v.y) + k * (pa.y() - p.y))
        val jz = -reduced * ((va.z() - v.z) + k * (pa.z() - p.z))
        val size = sqrt(jx * jx + jy * jy + jz * jz)
        if (!size.isFinite() || size <= 0.0) continue
        if (size > maxImpulse) { grip.broken = true; continue }
        if (a.isSleeping) a.wake()
        a.applyWorldImpulse(Vector3d(jx, jy, jz))
        if (shipMass > 0.0) ship.applyWorldForce(Vector3d(-jx / dt, -jy / dt, -jz / dt), Vector3d(p))
    }
}
