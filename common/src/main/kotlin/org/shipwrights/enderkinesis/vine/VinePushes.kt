package org.shipwrights.enderkinesis.vine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import dev.architectury.networking.NetworkManager
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.physics.physicsStepSeconds
import org.shipwrights.enderkinesis.util.gravity
import org.shipwrights.enderkinesis.util.hitboxMass
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.chains.Chain
import org.valkyrienskies.core.api.particles.ParticleId
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TICKS_PER_SECOND = 20.0
internal const val SECONDS_PER_TICK = 1.0 / TICKS_PER_SECOND
/** Blocked movement under this, in blocks per tick, is rounding in the collision code, not a contact. */
internal const val MIN_BLOCKED = 1e-4
/** Bounds the queue if the physics thread falls behind. */
private const val MAX_QUEUED = 4096
/** Reach past the entity's box for the links it touches, in blocks. */
private const val CONTACT_MARGIN = 0.05
/** Vanilla moves a resting entity down by (0.08 gravity) * 0.98 drag each tick, so this much blocked fall is no landing. */
internal const val REST_FALL = 0.0784
/** Links in one player push packet. */
private const val MAX_PUSH_LINKS = 8
/** Past terminal fall speed (3.92 blocks per tick): a larger blocked move in a packet is not real. */
private const val MAX_BLOCKED = 4.0
/** The most collision cubes of one link that can touch. */
private const val MAX_PUSH_WEIGHT = MAX_CUBES_PER_LINK + 1f
/** How far past the player's box, in blocks, a link in its push packet may be on the server. */
private const val PUSH_REACH = 1.0

/** An impulse delivered as a [force] over one game tick of physics steps, so no single step takes a spike. */
private class QueuedPush(val dimension: DimensionId, val id: ParticleId, val force: Vector3d) : AtomicBoolean() {
    var remaining = SECONDS_PER_TICK

    /** True for the one caller that settles this push; only that caller decrements [queuedCount]. */
    fun settle(): Boolean = compareAndSet(false, true)
}

// Server thread adds, physics thread drains.
private val pushes = ConcurrentLinkedQueue<QueuedPush>()
private val queuedCount = AtomicInteger()
// Each dimension's list is touched only from that dimension's physics tick.
private val activePushes = ConcurrentHashMap<DimensionId, ArrayList<QueuedPush>>()

/** C2S: the vine push of the local player's last move; the server checks it and queues the same impulses. */
val WOGGAK_VINE_PUSH: ResourceLocation = EnderkinesisMod.id("woggak_vine/push")

/**
 * After an entity's move, gives the vine the reaction to what the vine did to the entity: the entity's weight
 * or landing momentum when the vine held it up, and the entity's lost momentum when the vine stopped it.
 */
@OptIn(GameTickOnly::class, VsBeta::class)
fun Entity.pushVinesAfterCollide(movement: Vec3, actual: Vec3, base: List<VoxelShape>?, withVines: List<VoxelShape>?) {
    // The mixin passes the shape lists it computed; withVines is base itself when no vine is in the move.
    if (base == null || withVines == null || withVines === base) return
    val level = level()
    if (level is ServerLevel) {
        // A player's move arrives already collided by its client, so the client sends the push.
        if (this is Player) return
        val data = level.loadedWoggakVines ?: return
        if (data.collider.isEmpty) return
        val blocked = vineBlock(movement, actual, base, data.collider, data.contacts) ?: return
        level.queueVinePush(hitboxMass(), blocked.x, blocked.y, blocked.z, data.contacts, riderCarriesWeight = false)
    } else if (this is Player && isLocalPlayer && !clientVineCollider.isEmpty) {
        val contacts = clientVinePushOutbox.contacts
        val blocked = vineBlock(movement, actual, base, clientVineCollider, contacts, vinePass()) ?: return
        clientVinePushOutbox.add(blocked, contacts)
    }
}

/**
 * The vine's share of the stop of [movement], with the links it touches in [contacts]; null when the vines took none.
 * [actual] is the collide result with the vines and with vanilla's step-up.
 */
@Suppress("LongParameterList") // The collide inputs and the pass of the mover.
private fun Entity.vineBlock(
    movement: Vec3, actual: Vec3, base: List<VoxelShape>, collider: VineCollider, contacts: VineContacts, pass: VinePass? = null,
): Vec3? {
    val level = level()
    val box = boundingBox
    val free = Entity.collideBoundingBox(this, movement, box, level, base)
    // A step up onto a higher link cube is not a stop: it keeps the walk, and its rise is no landing.
    val held = Vec3(actual.x, if (free.y < 0.0) min(actual.y, 0.0) else actual.y, actual.z)
    val blocked = free.subtract(held)
    if (blocked.lengthSqr() < MIN_BLOCKED * MIN_BLOCKED) return null

    // Spread over the links the entity touches after its move: under its feet when held up, else around it.
    val moved = box.move(held)
    val contactBox = if (blocked.y < -MIN_BLOCKED) {
        AABB(moved.minX, moved.minY - 2 * VINE_HALF_WIDTH - CONTACT_MARGIN, moved.minZ, moved.maxX, moved.minY, moved.maxZ)
    } else {
        moved.inflate(CONTACT_MARGIN)
    }
    collider.contacts(contactBox, contacts, pass)
    if (contacts.size == 0 || contacts.totalWeight <= 0.0) {
        contacts.clear()
        return null
    }
    return blocked
}

/**
 * Queues on the links in [contacts] the impulse of an entity of [mass] that the vines blocked by (bx, by, bz) blocks.
 * [riderCarriesWeight] when a rider particle already gives the vine the entity's weight and landing.
 */
@OptIn(GameTickOnly::class, VsBeta::class)
private fun ServerLevel.queueVinePush(
    mass: Double, bx: Double, by: Double, bz: Double, contacts: VineContacts, riderCarriesWeight: Boolean,
) {
    val hitMass = reducedMass(mass, contactMass(contacts))
    val impulse = vinePushImpulse(mass, hitMass, gravity(), bx, by, bz, riderCarriesWeight, Vector3d())
    if (impulse.lengthSquared() == 0.0) {
        contacts.clear()
        return
    }
    val dimension = dimensionId
    var chain: Chain? = null
    var ids: List<ParticleId> = emptyList()
    for (c in 0 until contacts.size) {
        val next = contacts.chain[c] ?: continue
        if (next !== chain) { chain = next; ids = next.nodeIds }
        val node = contacts.node[c]
        if (node + 1 >= ids.size) continue
        val share = contacts.weight[c] / contacts.totalWeight
        val t = contacts.t[c]
        // Split between the link's two nodes by where the contact is along the link.
        queueVineForce(dimension, ids[node], Vector3d(impulse).mul(share * (1.0 - t) * TICKS_PER_SECOND))
        queueVineForce(dimension, ids[node + 1], Vector3d(impulse).mul(share * t * TICKS_PER_SECOND))
    }
    contacts.clear()
}

/** The VS2 mass of the nodes of the links in [contacts]; a node that two links share counts once. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.contactMass(contacts: VineContacts): Double {
    val particles = shipObjectWorld.allParticles
    var total = 0.0
    var chain: Chain? = null
    var ids: List<ParticleId> = emptyList()
    var counted = -1
    for (c in 0 until contacts.size) {
        val next = contacts.chain[c] ?: continue
        if (next !== chain) { chain = next; ids = next.nodeIds; counted = -1 }
        val node = contacts.node[c]
        if (node < 0 || node + 1 >= ids.size) continue
        // Contacts come in link order, so the highest counted node finds the shared ones.
        if (node > counted) total += particles.getById(ids[node])?.mass ?: 0.0
        if (node + 1 > counted) total += particles.getById(ids[node + 1])?.mass ?: 0.0
        counted = max(counted, node + 1)
    }
    return total
}

/** The mass that a perfectly inelastic hit between [a] and [b] moves: a·b / (a + b); 0 when one is not a mass. */
internal fun reducedMass(a: Double, b: Double): Double = if (a <= 0.0 || b <= 0.0) 0.0 else a * b / (a + b)

/**
 * The impulse in N·s on the vine from an entity of [mass] that it blocked by (bx, by, bz) blocks in one tick, into [out].
 * Held up, the weight for one tick, plus the landing past the resting fall step; else the stop. A hit moves only
 * [hitMass], the reduced mass of entity and links: the links are light and the entity stops with them.
 */
@Suppress("LongParameterList") // A mass pair, gravity, the blocked move and the output.
internal fun vinePushImpulse(
    mass: Double, hitMass: Double, gravity: Double, bx: Double, by: Double, bz: Double,
    riderCarriesWeight: Boolean, out: Vector3d,
): Vector3d {
    val iy = when {
        by >= -MIN_BLOCKED -> hitMass * by * TICKS_PER_SECOND
        riderCarriesWeight -> 0.0
        else -> -mass * gravity * SECONDS_PER_TICK - hitMass * max(0.0, -by - REST_FALL) * TICKS_PER_SECOND
    }
    return out.set(hitMass * bx * TICKS_PER_SECOND, iy, hitMass * bz * TICKS_PER_SECOND)
}

/** The local player's vine push since the last client tick. Client thread only. */
class VinePushOutbox {
    internal val contacts = VineContacts()
    private var bx = 0.0
    private var by = 0.0
    private var bz = 0.0
    private val chainIds = LongArray(MAX_PUSH_LINKS)
    private val links = IntArray(MAX_PUSH_LINKS)
    private val ts = FloatArray(MAX_PUSH_LINKS)
    private val weights = FloatArray(MAX_PUSH_LINKS)
    private var size = 0

    val isEmpty: Boolean get() = size == 0

    internal fun add(blocked: Vec3, from: VineContacts) {
        bx += blocked.x; by += blocked.y; bz += blocked.z
        for (c in 0 until from.size) {
            if (size >= MAX_PUSH_LINKS) break
            val chain = from.chain[c] ?: continue
            chainIds[size] = chain.id.value
            links[size] = from.node[c]
            ts[size] = from.t[c].toFloat()
            weights[size] = from.weight[c].toFloat()
            size++
        }
        from.clear()
    }

    /** Writes the push to [buf] and empties the outbox. */
    fun drainTo(buf: FriendlyByteBuf) {
        buf.writeFloat(bx.toFloat()); buf.writeFloat(by.toFloat()); buf.writeFloat(bz.toFloat())
        buf.writeVarInt(size)
        for (i in 0 until size) {
            buf.writeLong(chainIds[i]); buf.writeVarInt(links[i]); buf.writeFloat(ts[i]); buf.writeFloat(weights[i])
        }
        bx = 0.0; by = 0.0; bz = 0.0
        size = 0
    }
}

/** Filled by the local player's moves; the client tick sends it. */
val clientVinePushOutbox = VinePushOutbox()

private class VinePushPacket(
    val bx: Double, val by: Double, val bz: Double,
    val chainIds: LongArray, val links: IntArray, val ts: FloatArray, val weights: FloatArray,
)

private fun FriendlyByteBuf.readVinePush(): VinePushPacket {
    val bx = readFloat().toDouble(); val by = readFloat().toDouble(); val bz = readFloat().toDouble()
    require(abs(bx) <= MAX_BLOCKED && abs(by) <= MAX_BLOCKED && abs(bz) <= MAX_BLOCKED) { "Bad vine push $bx $by $bz" }
    val count = readVarInt()
    require(count in 1..MAX_PUSH_LINKS) { "Bad vine push link count $count" }
    val chainIds = LongArray(count); val links = IntArray(count); val ts = FloatArray(count); val weights = FloatArray(count)
    for (i in 0 until count) {
        chainIds[i] = readLong(); links[i] = readVarInt(); ts[i] = readFloat(); weights[i] = readFloat()
        require(ts[i] in 0f..1f && weights[i] > 0f && weights[i] <= MAX_PUSH_WEIGHT) { "Bad vine push link ${ts[i]} ${weights[i]}" }
    }
    return VinePushPacket(bx, by, bz, chainIds, links, ts, weights)
}

private fun registerVinePushReceiver() {
    NetworkManager.registerReceiver(NetworkManager.Side.C2S, WOGGAK_VINE_PUSH) { buf, ctx ->
        val packet = buf.readVinePush()
        ctx.queue {
            val player = ctx.player as? ServerPlayer ?: return@queue
            val level = player.serverLevel()
            val data = level.loadedWoggakVines ?: return@queue
            if (!data.riding.takePushBudget(player.uuid, level.gameTime)) return@queue
            // Only links near the player on the server count, so a client cannot push a vine far away.
            val reach = player.boundingBox.inflate(PUSH_REACH)
            val contacts = data.contacts
            contacts.clear()
            for (i in packet.chainIds.indices) {
                if (data.isThrowing(player.uuid, packet.chainIds[i])) continue
                val chain = data.collider.touchingLink(packet.chainIds[i], packet.links[i], reach) ?: continue
                if (!contacts.add(chain, packet.links[i], packet.ts[i].toDouble(), packet.weights[i].toDouble())) break
            }
            if (contacts.size == 0) return@queue
            val ridden = packet.by < -MIN_BLOCKED && level.rideVine(player, data, contacts, packet.by)
            level.queueVinePush(player.hitboxMass(), packet.bx, packet.by, packet.bz, contacts, ridden)
        }
    }
}

/** Queues [force] on node [id] for one game tick. */
internal fun queueVineForce(dimension: DimensionId, id: ParticleId, force: Vector3d) {
    if (queuedCount.get() >= MAX_QUEUED) return
    pushes.add(QueuedPush(dimension, id, force))
    queuedCount.incrementAndGet()
}

@OptIn(VsBeta::class, PhysTickOnly::class)
fun registerVinePushes() {
    vsApi.physTickEvent.on { event, _ -> event.world.applyVinePushes(physicsStepSeconds(event.delta)) }
    registerVinePushReceiver()
}

fun clearVinePushes() {
    for (push in pushes) push.settle()
    for (active in activePushes.values) settleAll(active)
    pushes.clear()
    activePushes.clear()
    queuedCount.set(0)
}

/** Drops the queued pushes of [dimension], so an unloaded dimension frees its share of the cap. */
fun clearVinePushes(dimension: DimensionId) {
    var dropped = 0
    pushes.removeIf { push -> push.dimension == dimension && push.settle().also { if (it) dropped++ } }
    activePushes.remove(dimension)?.let { dropped += settleAll(it) }
    queuedCount.addAndGet(-dropped)
}

// The physics thread can still change the list: toArray never throws, and settle() stops a double count.
private fun settleAll(active: ArrayList<QueuedPush>): Int =
    active.toArray().count { it is QueuedPush && it.settle() }

// The event fires once per dimension; pushes for another dimension stay for its own event.
@OptIn(PhysTickOnly::class)
private fun PhysLevel.applyVinePushes(dt: Double) {
    val dimension = this.dimension
    val active = activePushes.getOrPut(dimension) { ArrayList() }
    if (pushes.isNotEmpty()) {
        val iterator = pushes.iterator()
        while (iterator.hasNext()) {
            val push = iterator.next()
            if (push.dimension != dimension) continue
            iterator.remove()
            active.add(push)
        }
    }
    var i = 0
    while (i < active.size) {
        val push = active[i]
        val particle = getParticleById(push.id)
        val step = minOf(dt, push.remaining)
        push.remaining -= step
        if (particle != null) {
            if (particle.isSleeping) particle.wake()
            particle.applyWorldImpulse(Vector3d(push.force).mul(step))
        }
        if (particle == null || push.remaining <= 0.0) {
            active[i] = active[active.size - 1]
            active.removeAt(active.size - 1)
            if (push.settle()) queuedCount.decrementAndGet()
        } else {
            i++
        }
    }
}
