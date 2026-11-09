package org.shipwrights.enderkinesis.physics

import java.util.concurrent.ConcurrentLinkedQueue
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.util.ServerTicks
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.api.vsApi

/** The GameToPhysicsAdapter one-shot world force, for standalone VS bodies: the adapter finds ships only. */
@OptIn(PhysTickOnly::class, VsBeta::class)
object BodyForceQueue {

    private class Queued(val bodyId: Long, val force: Vector3dc, val worldPos: Vector3dc, val tick: Int)

    private val queued = ConcurrentLinkedQueue<Queued>()

    @Volatile private var registered = false

    /** Applies [force] at [worldPos] on the next physics tick. Server thread. */
    fun applyWorldForce(bodyId: Long, force: Vector3dc, worldPos: Vector3dc) {
        queued.add(Queued(bodyId, Vector3d(force), Vector3d(worldPos), ServerTicks.now))
    }

    fun register() {
        if (registered) return
        registered = true
        vsApi.physTickEvent.on { event, _ -> tick(event.world) }
    }

    fun clearSession() = queued.clear()

    // The event fires once per dimension, so a body that is not in this level stays queued until it is stale.
    private fun tick(physLevel: PhysLevel) {
        if (queued.isEmpty()) return
        val iter = queued.iterator()
        while (iter.hasNext()) {
            val q = iter.next()
            if (RodLevitationAttachment.isStale(q.tick)) { iter.remove(); continue }
            val body = physLevel.getBodyById(q.bodyId) ?: continue
            iter.remove()
            if (body.isStatic || body.inertiaData.mass <= 0.0) continue
            body.applyWorldForce(Vector3d(q.force), Vector3d(q.worldPos))
        }
    }
}
