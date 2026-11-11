package org.shipwrights.enderkinesis.vine

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import org.joml.Vector3d
import org.shipwrights.enderkinesis.physics.physicsStepSeconds
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.chains.ChainEnd
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
import kotlin.math.sqrt

// Fake joint until VS exposes a chain-node endpoint: the joined end is free, and each physics tick equal and
// opposite impulses pull it and the target node together, so both vines carry the load.

/** Share of the position error removed in one physics tick; low enough that the correction does not overshoot. */
private const val POSITION_CORRECTION = 0.2

class VineJoint(val chain: ChainId, val node: Int) {
    fun save(): CompoundTag = CompoundTag().apply {
        putLong("chain", chain.value)
        putInt("node", node)
    }

    companion object {
        fun load(tag: CompoundTag) = VineJoint(ChainId(tag.getLong("chain")), tag.getInt("node"))
    }
}

private const val NO_CONTACTS = 0
private const val ALL_CONTACTS = -1

/** Gives the joined node of [pair] its contacts back when the joint lets go. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.restoreJointContacts(pair: JointPair) {
    shipObjectWorld.allParticles.getById(pair.follower)?.setCollisionMask(ALL_CONTACTS)
}

/** The two nodes one joint pulls together. The physics thread sets [broken]; the server thread reads it. */
class JointPair(val follower: ParticleId, val target: ParticleId) {
    @Volatile var broken = false
}

// Server thread publishes, physics thread reads; each array is replaced, never changed.
private val publishedPairs = ConcurrentHashMap<DimensionId, Array<JointPair>>()

/** Current world position of the joined node, or null when the target vine is gone. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.jointPosition(joint: VineJoint): Vector3d? {
    val target = shipObjectWorld.allChains.getById(joint.chain) ?: return null
    val status = target.status
    if (status.lifecycle != ChainLifecycle.ACTIVE) return null
    val nodes = status.nodeCount
    if (joint.node !in 0 until nodes) return null
    val data = woggakVines
    if (data.scratch.size < nodes * 3) data.scratch = DoubleArray(nodes * 3)
    if (target.copyNodePositions(data.scratch) != nodes) return null
    val k = joint.node * 3
    return Vector3d(data.scratch[k], data.scratch[k + 1], data.scratch[k + 2])
}

/**
 * Keeps the joint of [anchor] on [end] of [chain] alive. Returns false when the target vine is gone or the joint
 * broke, so the caller frees the end. A target that is only pending or unloaded keeps the joint.
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.followJoint(chain: ServerChain, end: ChainEnd, anchor: WoggakVineAnchor, joint: VineJoint): Boolean {
    anchor.pair?.let { if (it.broken) return false }
    val target = shipObjectWorld.allChains.getById(joint.chain) ?: return false
    when (target.status.lifecycle) {
        ChainLifecycle.PENDING, ChainLifecycle.SUSPENDED -> return true
        ChainLifecycle.REMOVED, ChainLifecycle.FAILED -> return false
        ChainLifecycle.ACTIVE -> Unit
    }
    if (anchor.pair == null) {
        // Node ids are fixed for the life of a chain, so one lookup holds until the vine is split or removed.
        val own = chain.nodeIds
        val other = target.nodeIds
        if (own.isEmpty() || joint.node !in other.indices) return false
        val follower = if (end == ChainEnd.START) own.first() else own.last()
        anchor.pair = JointPair(follower, other[joint.node])
        // The joined node sits on the target node, and a contact would push them apart. Mask 0 stops its contacts,
        // as vs-core's ParticleChainApiTest does for a node and its anchor body; the native collideConnected is not public.
        shipObjectWorld.allParticles.getById(follower)?.setCollisionMask(NO_CONTACTS)
        woggakVines.jointsDirty = true
    }
    return true
}

/** Sends the live joint pairs of this level to the physics thread when they changed. */
internal fun ServerLevel.publishJointPairs(data: WoggakVineData) {
    if (!data.jointsDirty) return
    data.jointsDirty = false
    val pairs = ArrayList<JointPair>()
    for (vine in data.vines) {
        if (vine.start.isJoined) vine.start.pair?.let(pairs::add)
        if (vine.end.isJoined) vine.end.pair?.let(pairs::add)
    }
    if (pairs.isEmpty()) publishedPairs.remove(dimensionId) else publishedPairs[dimensionId] = pairs.toTypedArray()
}

@OptIn(VsBeta::class, PhysTickOnly::class)
fun registerVineJoints() {
    vsApi.physTickEvent.on { event, _ -> solveJoints(event.world, physicsStepSeconds(event.delta)) }
}

fun clearVineJoints() = publishedPairs.clear()

@OptIn(PhysTickOnly::class)
private fun solveJoints(physLevel: PhysLevel, dt: Double) {
    val pairs = publishedPairs[physLevel.dimension] ?: return
    val maxImpulse = VINE_BREAK_FORCE * dt
    for (pair in pairs) {
        if (pair.broken) continue
        val a = physLevel.getParticleById(pair.follower) ?: continue
        val b = physLevel.getParticleById(pair.target) ?: continue
        val pa = a.position; val pb = b.position
        val va = a.velocity; val vb = b.velocity
        val reduced = a.mass * b.mass / (a.mass + b.mass)
        if (!reduced.isFinite() || reduced <= 0.0) continue
        // Soft constraint: remove the relative velocity and a share of the gap in this tick.
        val k = POSITION_CORRECTION / dt
        val jx = -reduced * ((va.x() - vb.x()) + k * (pa.x() - pb.x()))
        val jy = -reduced * ((va.y() - vb.y()) + k * (pa.y() - pb.y()))
        val jz = -reduced * ((va.z() - vb.z()) + k * (pa.z() - pb.z()))
        val size = sqrt(jx * jx + jy * jy + jz * jz)
        if (!size.isFinite() || size <= 0.0) continue
        // The same strength as a vine link; past it the joint lets go, as a link would.
        if (size > maxImpulse) { pair.broken = true; continue }
        if (a.isSleeping) a.wake()
        if (b.isSleeping) b.wake()
        a.applyWorldImpulse(Vector3d(jx, jy, jz))
        b.applyWorldImpulse(Vector3d(-jx, -jy, -jz))
    }
}
