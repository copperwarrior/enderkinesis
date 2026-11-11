package org.shipwrights.enderkinesis.vine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.physics.physicsStepSeconds
import org.shipwrights.enderkinesis.util.gravity
import org.shipwrights.enderkinesis.util.hitboxMass
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.particles.ParticleId
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.dimensionId

private const val TICKS_PER_SECOND = 20.0
private const val SECONDS_PER_TICK = 1.0 / TICKS_PER_SECOND
/** Blocked movement under this, in blocks per tick, is rounding in the collision code, not a contact. */
private const val MIN_BLOCKED = 1e-4
/** Bounds the queue if the physics thread falls behind. */
private const val MAX_QUEUED = 4096
/** Reach past the entity's box for the links it touches, in blocks. */
private const val CONTACT_MARGIN = 0.05

/** An impulse delivered as a [force] over one game tick of physics steps, so no single step takes a spike. */
private class QueuedPush(val dimension: DimensionId, val id: ParticleId, val force: Vector3d) {
    var remaining = SECONDS_PER_TICK
}

// Server thread adds, physics thread drains.
private val pushes = ConcurrentLinkedQueue<QueuedPush>()
private val queuedCount = AtomicInteger()
// Each dimension's list is touched only from that dimension's physics tick.
private val activePushes = ConcurrentHashMap<DimensionId, ArrayList<QueuedPush>>()

/**
 * After an entity's move, gives the vine the reaction to what the vine did to the entity: the entity's weight
 * when the vine held it up, and the entity's lost momentum when the vine stopped it. Server thread.
 */
@OptIn(GameTickOnly::class, VsBeta::class)
fun pushVinesAfterCollide(entity: Entity, movement: Vec3) {
    val level = entity.level() as? ServerLevel ?: return
    val data = level.woggakVines
    val collider = data.collider
    val box = entity.boundingBox
    val swept = box.expandTowards(movement)
    if (!collider.overlaps(swept)) return

    // The vine's share of the stop: the move without vines minus the move with them.
    val base = level.getEntityCollisions(entity, swept)
    val withVines = collider.withShapes(swept, base)
    if (withVines === base) return
    val free = Entity.collideBoundingBox(entity, movement, box, level, base)
    val held = Entity.collideBoundingBox(entity, movement, box, level, withVines)
    val bx = free.x - held.x
    val by = free.y - held.y
    val bz = free.z - held.z
    if (bx * bx + by * by + bz * bz < MIN_BLOCKED * MIN_BLOCKED) return

    val mass = entity.hitboxMass()
    // Held up: the weight for one tick, as an impulse. Else the momentum the vine took from the entity.
    val iy = if (by < -MIN_BLOCKED) -mass * level.gravity() * SECONDS_PER_TICK else mass * by * TICKS_PER_SECOND
    val ix = mass * bx * TICKS_PER_SECOND
    val iz = mass * bz * TICKS_PER_SECOND

    // Spread over the links the entity touches after its move: under its feet when held up, else around it.
    val moved = box.move(held)
    val contactBox = if (by < -MIN_BLOCKED) {
        AABB(moved.minX, moved.minY - 2 * VINE_HALF_WIDTH - CONTACT_MARGIN, moved.minZ, moved.maxX, moved.minY, moved.maxZ)
    } else {
        moved.inflate(CONTACT_MARGIN)
    }
    val contacts = data.contacts
    collider.contacts(contactBox, contacts)
    if (contacts.size == 0 || contacts.totalWeight <= 0.0) return
    val dimension = level.dimensionId
    for (c in 0 until contacts.size) {
        val chain = contacts.chain[c] ?: continue
        val ids = chain.nodeIds
        val node = contacts.node[c]
        if (node + 1 >= ids.size) continue
        val share = contacts.weight[c] / contacts.totalWeight
        val t = contacts.t[c]
        // Split between the link's two nodes by where the contact is along the link.
        queue(dimension, ids[node], Vector3d(ix, iy, iz).mul(share * (1.0 - t) * TICKS_PER_SECOND))
        queue(dimension, ids[node + 1], Vector3d(ix, iy, iz).mul(share * t * TICKS_PER_SECOND))
    }
    contacts.clear()
}

/** Queues [force] on node [id] for one game tick. */
private fun queue(dimension: DimensionId, id: ParticleId, force: Vector3d) {
    if (queuedCount.get() >= MAX_QUEUED) return
    pushes.add(QueuedPush(dimension, id, force))
    queuedCount.incrementAndGet()
}

@OptIn(VsBeta::class, PhysTickOnly::class)
fun registerVinePushes() {
    vsApi.physTickEvent.on { event, _ -> applyVinePushes(event.world, physicsStepSeconds(event.delta)) }
}

fun clearVinePushes() {
    pushes.clear()
    activePushes.clear()
    queuedCount.set(0)
}

// The event fires once per dimension; pushes for another dimension stay for its own event.
@OptIn(PhysTickOnly::class)
private fun applyVinePushes(physLevel: PhysLevel, dt: Double) {
    val dimension = physLevel.dimension
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
        val particle = physLevel.getParticleById(push.id)
        val step = minOf(dt, push.remaining)
        push.remaining -= step
        if (particle != null) {
            if (particle.isSleeping) particle.wake()
            particle.applyWorldImpulse(Vector3d(push.force).mul(step))
        }
        if (particle == null || push.remaining <= 0.0) {
            active[i] = active[active.size - 1]
            active.removeAt(active.size - 1)
            queuedCount.decrementAndGet()
        } else {
            i++
        }
    }
}
