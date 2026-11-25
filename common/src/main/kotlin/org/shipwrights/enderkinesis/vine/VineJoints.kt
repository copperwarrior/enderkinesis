package org.shipwrights.enderkinesis.vine

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.physics.physicsStepSeconds
import org.shipwrights.enderkinesis.registry.EKSounds
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
import kotlin.math.abs
import kotlin.math.sqrt

// Fake joint until VS exposes a chain-node endpoint: the joined end is free, and each physics tick equal and
// opposite impulses pull it and the target node together, so both vines carry the load.

/** Share of the position error removed in one physics tick; low enough that the correction does not overshoot. */
private const val POSITION_CORRECTION = 0.2
/** Share of the break impulse where a joined end starts to creak; the volume goes from this share to 1 at the break. */
private const val STRAIN_SOUND_SHARE = 0.6
private const val STRAIN_SOUND_GAP_TICKS = 60L
private const val STRAIN_SOUND_SPREAD_TICKS = 20

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
internal const val ALL_CONTACTS = -1

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
    @Volatile private var rate = 0.0
    @Volatile private var goal = 0.0
    @Volatile private var start = 0.0
    // Physics thread only; NaN until the first physics tick takes the start.
    private var shown = Double.NaN
    // The physics thread writes the place before the volatile strain, so a read of strain sees that place or a newer one.
    private var strainX = 0.0
    private var strainY = 0.0
    private var strainZ = 0.0
    /** Highest share of the break impulse since the server last took it; 0 when none passed [STRAIN_SOUND_SHARE]. */
    @Volatile private var strain = 0.0

    /** Physics thread: keeps the highest [share] since the last [takeStrain], at the joint point. */
    internal fun recordStrain(share: Double, x: Double, y: Double, z: Double) {
        if (share <= strain) return
        strainX = x; strainY = y; strainZ = z
        strain = share
    }

    /** Server: plays the creak of the strain that the physics thread kept, unless [vine] creaked a short time ago. */
    internal fun takeStrain(level: ServerLevel, vine: WoggakVine) {
        val share = strain
        if (share <= 0.0) return
        // A share that the physics thread writes between these two lines is lost; the next one creaks.
        strain = 0.0
        if (level.gameTime < vine.nextStrainTick) return
        vine.nextStrainTick = level.gameTime + STRAIN_SOUND_GAP_TICKS + level.random.nextInt(STRAIN_SOUND_SPREAD_TICKS)
        level.playSound(
            null, strainX, strainY, strainZ, EKSounds.WOGGAK_VINE_STRAIN.get(), SoundSource.BLOCKS,
            share.coerceIn(STRAIN_SOUND_SHARE, 1.0).toFloat(), 1f,
        )
    }

    /** Server: puts the point at [value] at once. */
    var weight: Double
        get() = goal
        set(value) {
            rate = 0.0
            start = value
            goal = value
        }

    /**
     * Server: moves the point to [value] over one game tick. A step of the point once per game tick shakes a taut
     * vine under the heavy hold particle until it blows up, so the physics thread moves it a little each physics tick.
     */
    fun glideTo(value: Double) {
        rate = abs(value - goal) / SECONDS_PER_TICK
        goal = value
    }

    /** Physics thread: the point for a step of [dt] seconds. */
    internal fun stepWeight(dt: Double): Double {
        val target = goal
        val step = rate * dt
        val from = if (shown.isNaN()) start else shown
        shown = if (step <= 0.0) target else from + (target - from).coerceIn(-step, step)
        return shown
    }
}

// Server thread publishes, physics thread reads; each array is replaced, never changed.
private val publishedPairs = ConcurrentHashMap<DimensionId, Array<JointPair>>()

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

/**
 * Creaks the joined ends of [vine] near their break. Player holds and riders are not read: a player is about 78 kg,
 * far below the share, so only a ship or other heavy load on a vine joint creaks.
 */
internal fun ServerLevel.creakJoinedEnds(vine: WoggakVine) {
    if (vine.start.isJoined) vine.start.pair?.takeStrain(this, vine)
    if (vine.end.isJoined) vine.end.pair?.takeStrain(this, vine)
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
    for (hold in data.holds.values) pairs.add(hold.pair)
    for (rider in data.riding.riders.values) pairs.add(rider.pair)
    if (pairs.isEmpty()) publishedPairs.remove(dimensionId) else publishedPairs[dimensionId] = pairs.toTypedArray()
}

@OptIn(VsBeta::class, PhysTickOnly::class)
fun registerVineJoints() {
    vsApi.physTickEvent.on { event, _ -> event.world.solveJoints(physicsStepSeconds(event.delta)) }
}

fun clearVineJoints() {
    publishedPairs.clear()
}

@OptIn(PhysTickOnly::class)
private fun PhysLevel.solveJoints(dt: Double) {
    val pairs = publishedPairs[dimension] ?: return
    val maxImpulse = VINE_BREAK_FORCE * dt
    for (pair in pairs) {
        if (pair.broken) continue
        val a = getParticleById(pair.follower) ?: continue
        val b = getParticleById(pair.target) ?: continue
        val secondId = pair.second
        val c = if (secondId == null) null else getParticleById(secondId) ?: continue
        val wc = if (c == null) 0.0 else pair.stepWeight(dt).coerceIn(0.0, 1.0)
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
        if (size >= maxImpulse * STRAIN_SOUND_SHARE) pair.recordStrain(size / maxImpulse, pa.x(), pa.y(), pa.z())
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
