package org.shipwrights.enderkinesis.physics

import org.valkyrienskies.core.api.events.CollisionEvent
import org.valkyrienskies.mod.api.vsApi
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

/** The contacts VS2 reports for walker ships, kept per ship for the game thread.
 *  Collision events fire on the physics thread: each one replaces a ship's snapshot whole,
 *  so a reader never sees half of one. */
internal object ConstriliarContacts {

    /** One frame's contact points of one ship: positions, normals and separations. */
    class Snapshot(val nanos: Long, val count: Int, val data: DoubleArray, val separation: FloatArray)

    /** Copy-on-write: the physics thread reads it for every touching pair each frame,
     *  without boxing; the server thread changes it only on ship add or remove. */
    @Volatile
    private var walkers = LongArray(0)
    private val latest = ConcurrentHashMap<Long, Snapshot>()
    private val merger = BiFunction<Snapshot, Snapshot, Snapshot>(::sameFrame)

    fun register() {
        @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
        vsApi.collisionStartEvent.on { event, _ -> record(event) }
        @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
        vsApi.collisionPersistEvent.on { event, _ -> record(event) }
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

    /** The newest snapshot of [shipId] no older than [maxAgeNanos], or null. */
    fun recent(shipId: Long, maxAgeNanos: Long): Snapshot? {
        val snap = latest[shipId] ?: return null
        return if (System.nanoTime() - snap.nanos <= maxAgeNanos) snap else null
    }

    @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
    private fun record(event: CollisionEvent) {
        val a = event.shipIdA
        val b = event.shipIdB
        val ids = walkers
        val forA = isWalker(ids, a)
        val forB = isWalker(ids, b)
        if (!forA && !forB) return
        val points = event.contactPoints
        val count = Math.min(points.size, MAX_POINTS)
        val data = DoubleArray(count * STRIDE)
        val separation = FloatArray(count)
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
            separation[k] = p.separation
            k++
        }
        val snap = Snapshot(System.nanoTime(), count, data, separation)
        if (forA) latest.merge(a, snap, merger)
        if (forB) latest.merge(b, snap, merger)
    }

    /** One event per touching pair: the terrain and another body in the same frame both
     *  belong to the snapshot. An older one is replaced. */
    private fun sameFrame(old: Snapshot, new: Snapshot): Snapshot {
        if (new.nanos - old.nanos > FRAME_NANOS || old.count + new.count > MAX_POINTS) return new
        return Snapshot(new.nanos, old.count + new.count, old.data + new.data, old.separation + new.separation)
    }

    const val STRIDE = 6
    private const val MAX_POINTS = 32
    /** Events within this of each other are one physics frame. */
    private const val FRAME_NANOS = 4_000_000L
}
