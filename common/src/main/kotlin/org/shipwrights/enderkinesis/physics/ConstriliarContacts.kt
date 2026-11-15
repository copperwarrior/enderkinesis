package org.shipwrights.enderkinesis.physics

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.events.CollisionEvent
import org.valkyrienskies.mod.api.vsApi
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import kotlin.math.abs
import kotlin.math.min

/** The contacts VS2 reports for walker ships, kept per ship for the game thread.
 *  Collision events fire on the physics thread: each one replaces a ship's snapshot whole,
 *  so a reader never sees half of one. */
internal object ConstriliarContacts {

    /** One frame's contact points of one ship: positions, normals, VS2 approach speeds and separations. */
    class Snapshot(val nanos: Long, val count: Int, val data: DoubleArray, val separation: FloatArray, val pairs: LongArray) {
        /** The body that point [k] touches, seen from body [self]. */
        fun other(k: Int, self: Long): Long = if (pairs[2 * k] == self) pairs[2 * k + 1] else pairs[2 * k]
    }

    /** Copy-on-write: the physics thread reads it for every touching pair each frame,
     *  without boxing; the server thread changes it only on ship add or remove. */
    @Volatile
    private var walkers = LongArray(0)
    private val latest = ConcurrentHashMap<Long, Snapshot>()
    private val merger = BiFunction<Snapshot, Snapshot, Snapshot>(::sameFrame)

    fun register() {
        @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
        vsApi.collisionStartEvent.on { event, _ -> record(event, start = true) }
        @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
        vsApi.collisionPersistEvent.on { event, _ -> record(event, start = false) }
    }

    @Synchronized
    fun track(shipId: Long) {
        if (!isWalker(walkers, shipId)) walkers = walkers + shipId
    }

    @Synchronized
    fun untrack(shipId: Long) {
        walkers = walkers.filter { it != shipId }.toLongArray()
        latest.remove(shipId)
    }

    private fun isWalker(ids: LongArray, shipId: Long): Boolean {
        for (id in ids) if (id == shipId) return true
        return false
    }

    /** True when [bodyId] is a tracked walker body. Any thread; no allocation. */
    fun isWalkerBody(bodyId: Long): Boolean = isWalker(walkers, bodyId)

    /** The newest snapshot of [shipId] no older than [maxAgeNanos], or null. */
    fun recent(shipId: Long, maxAgeNanos: Long): Snapshot? {
        val snap = latest[shipId] ?: return null
        return if (System.nanoTime() - snap.nanos <= maxAgeNanos) snap else null
    }

    @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
    private fun record(event: CollisionEvent, start: Boolean) {
        val a = event.shipIdA
        val b = event.shipIdB
        val ids = walkers
        val forA = isWalker(ids, a)
        val forB = isWalker(ids, b)
        if (!forA && !forB) return
        val points = event.contactPoints
        val count = min(points.size, MAX_POINTS)
        val data = DoubleArray(count * STRIDE)
        val separation = FloatArray(count)
        val pairs = LongArray(count * 2)
        var k = 0
        for (p in points) {
            if (k >= count) break
            val pos = p.position
            val n = p.normal
            data[k * STRIDE] = pos.x()
            data[k * STRIDE + 1] = pos.y()
            data[k * STRIDE + 2] = pos.z()
            data[k * STRIDE + 3] = n.x()
            data[k * STRIDE + 4] = n.y()
            data[k * STRIDE + 5] = n.z()
            val v = p.velocity
            // VS2 does not fix which body is A, so only the size of v·n means something.
            data[k * STRIDE + 6] = abs(v.x() * n.x() + v.y() * n.y() + v.z() * n.z())
            data[k * STRIDE + 7] = if (start) 1.0 else 0.0
            separation[k] = p.separation
            pairs[2 * k] = a
            pairs[2 * k + 1] = b
            k++
        }
        val snap = Snapshot(System.nanoTime(), count, data, separation, pairs)
        if (forA) store(a, snap)
        if (forB) store(b, snap)
    }

    // An untrack between the walkers read and the merge would leave a snapshot behind; the second read drops it.
    private fun store(shipId: Long, snap: Snapshot) {
        latest.merge(shipId, snap, merger)
        if (!isWalkerBody(shipId)) latest.remove(shipId)
    }

    /** One event per touching pair: the terrain and another body in the same frame both
     *  belong to the snapshot. An older one is replaced. */
    private fun sameFrame(old: Snapshot, new: Snapshot): Snapshot {
        if (new.nanos - old.nanos > FRAME_NANOS || old.count + new.count > MAX_POINTS) return new
        return Snapshot(
            new.nanos, old.count + new.count, old.data + new.data, old.separation + new.separation, old.pairs + new.pairs,
        )
    }

    /** Writes to [out] the in-plane unit direction that pushes the body off the contact at [i] in
     *  [data], and gives true. False for contacts that bear the body or touch it over or under
     *  its axis through [ref] (support, a ceiling): the hover owns that axis. */
    fun awayFromContact(data: DoubleArray, i: Int, ref: Vector3dc, up: Vector3dc, lateral: Vector3d, out: Vector3d): Boolean {
        lateral.set(ref.x() - data[i], ref.y() - data[i + 1], ref.z() - data[i + 2])
        lateral.fma(-lateral.dot(up), up)
        if (lateral.lengthSquared() < MIN_LATERAL * MIN_LATERAL) return false
        out.set(data[i + 3], data[i + 4], data[i + 5])
        out.fma(-out.dot(up), up)
        if (out.lengthSquared() < MIN_SIDE * MIN_SIDE) return false
        // The normal's sign is the pair's order; it must point from the block to the body.
        if (out.dot(lateral) < 0.0) out.negate()
        out.normalize()
        return true
    }

    /** True for a contact on top of the body (cargo against a ceiling): over the plate centre
     *  [ref] along [up], with a normal mostly along up. */
    fun isTopContact(data: DoubleArray, i: Int, ref: Vector3dc, up: Vector3dc): Boolean {
        val along = (data[i] - ref.x()) * up.x() + (data[i + 1] - ref.y()) * up.y() + (data[i + 2] - ref.z()) * up.z()
        if (along < TOP_MIN_UP) return false
        val normalUp = data[i + 3] * up.x() + data[i + 4] * up.y() + data[i + 5] * up.z()
        return abs(normalUp) >= MIN_SIDE
    }

    /** Position (0-2), normal (3-5), VS2 approach speed |v·n| (6), 1 for a START point (7). */
    const val STRIDE = 8
    const val SPEED = 6
    const val START = 7
    /** A point with a larger gap (m) is a contact VS2 expects, not a touch. */
    const val TOUCH_SEPARATION = 0.05f
    /** The least offset off the body's axis and the least sideways share of the normal. */
    private const val MIN_LATERAL = 0.2
    private const val MIN_SIDE = 0.5
    /** A top contact sits at least this far (m) over the plate centre; the plate's own top is at 0.5. */
    private const val TOP_MIN_UP = 0.25
    private const val MAX_POINTS = 32
    /** Events within this of each other are one physics frame. */
    private const val FRAME_NANOS = 4_000_000L
}
