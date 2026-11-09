package org.shipwrights.enderkinesis.physics

import java.util.concurrent.ConcurrentHashMap
import org.joml.Vector3d
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.api.vsApi
import org.shipwrights.enderkinesis.util.ServerTicks

/** The buoyancy of light gas in ship cells: one force for each ship, at the middle of its gas. */
@OptIn(PhysTickOnly::class, VsBeta::class)
object GasLift {

    /** One game tick of lift in one dimension for one gas kind. Centres are in shipyard coordinates. */
    class Snapshot(
        val dimension: String,
        val shipIds: LongArray,
        val newtons: DoubleArray,
        val centreX: DoubleArray,
        val centreY: DoubleArray,
        val centreZ: DoubleArray,
        val tick: Int = ServerTicks.now,
    )

    // The gas sim publishes each game tick; an older snapshot means it stopped (unload, dimension change).
    private const val STALE_TICKS = 2

    // Written on the server thread, read on the physics thread. Keyed by dimension and gas kind.
    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    @Volatile private var registered = false

    // VS2 copies the model position, so a scratch is safe; one per thread, as dimensions may step in parallel.
    private val centre = ThreadLocal.withInitial { Vector3d() }

    fun register() {
        if (registered) return
        registered = true
        vsApi.physTickEvent.on { event, _ -> tick(event.world) }
    }

    fun publish(key: String, snapshot: Snapshot) {
        snapshots[key] = snapshot
    }

    fun remove(key: String) {
        snapshots.remove(key)
    }

    fun clearSession() = snapshots.clear()

    private fun tick(physLevel: PhysLevel) {
        if (snapshots.isEmpty()) return
        val dimension = physLevel.dimension
        val at = centre.get()
        for ((key, lift) in snapshots) {
            if (ServerTicks.now - lift.tick > STALE_TICKS) {
                snapshots.remove(key, lift)
                continue
            }
            if (lift.dimension != dimension) continue
            for (i in lift.shipIds.indices) {
                val ship = physLevel.getShipById(lift.shipIds[i]) ?: continue
                if (ship.isStatic) continue
                // Lift is a volume force, so it grows as scale³, the same as the ship's mass.
                val s = ship.transform.shipToWorldScaling.x()
                val force = Vector3d(0.0, lift.newtons[i] * s * s * s, 0.0)
                at.set(lift.centreX[i], lift.centreY[i], lift.centreZ[i])
                // VS2 throws on a non-finite wrench, and that ends the server.
                if (!force.isFinite || !at.isFinite) continue
                ship.applyWorldForceToModelPos(force, at)
            }
        }
    }
}
