package org.shipwrights.enderkinesis.blockentity

import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Mth
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.physics.ContactPoint
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.util.UnavailableInKrunch
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.concurrent.ConcurrentHashMap

/**
 * Fires only the explosive whose own block is at a contact, so a hit on the bow does not set off the stern.
 * Runs on the physics thread: only map reads and a volatile flag write here; Level.explode runs on the game thread.
 */
@OptIn(VsBeta::class)
object CrystalExplosiveCollisionRouter {

    /** Ship id → (shipyard block pos → BE). */
    private val byShip: ConcurrentHashMap<ShipId, ConcurrentHashMap<BlockPos, CrystalExplosiveBlockEntity>> =
        ConcurrentHashMap()

    /** Dimension id → (world block pos → BE). For world-placed explosives. */
    private val byWorld: ConcurrentHashMap<DimensionId, ConcurrentHashMap<BlockPos, CrystalExplosiveBlockEntity>> =
        ConcurrentHashMap()

    @Volatile private var registered: Boolean = false

    // Scratch for [handle] only: VS2 emits collision events from its one physics thread. Never passed to VS2.
    private val physProbe = Vector3d()
    private val physProbePos = BlockPos.MutableBlockPos()

    /** Loaded ship id → pre-step linear velocity, for every loaded ship, so an unarmed rammer reads true too.
     *  In a drop test the START contact velocity read ~35% of the true speed; this pre-step velocity read true.
     *  Stopgap until VS2 fixes the START contact velocity (confirmed upstream bug).
     *  The game thread adds and drops entries; the physics thread fills them. */
    private val preStep: ConcurrentHashMap<ShipId, Vector3d> = ConcurrentHashMap()

    // Physics thread only: the level of the tick in progress, read by [record].
    @OptIn(PhysTickOnly::class)
    private var tickLevel: PhysLevel? = null
    private val recordPreStep = java.util.function.BiConsumer<ShipId, Vector3d> { id, out -> record(id, out) }
    private val newPreStep = java.util.function.Function<ShipId, Vector3d> { Vector3d() }

    // Subscribes on the game thread; the callback runs on the physics thread. Konstant is the only target backend.
    @OptIn(PhysTickOnly::class, UnavailableInKrunch::class)
    fun register() {
        if (registered) return
        registered = true
        // Runs at the start of each physics tick, before the step that makes this tick's contacts.
        vsApi.physTickEvent.on { event, _ ->
            if (noExplosives()) return@on
            tickLevel = event.world
            preStep.forEach(recordPreStep)
            tickLevel = null
        }
        TickEvent.SERVER_POST.register(::syncLoadedShips)
        vsApi.collisionStartEvent.on { event, _ ->
            handle(event)
        }
    }

    @OptIn(PhysTickOnly::class)
    private fun record(id: ShipId, out: Vector3d) {
        val ship = tickLevel?.getShipById(id) ?: return
        out.set(ship.velocity)
    }

    // Server tick, on the game thread. One Vector3d per loaded ship; an unloaded or deleted ship loses its entry.
    @OptIn(GameTickOnly::class)
    private fun syncLoadedShips(server: MinecraftServer) {
        if (noExplosives()) {
            preStep.clear()
            return
        }
        val loaded = server.shipObjectWorld.loadedShips
        for (ship in loaded) preStep.computeIfAbsent(ship.id, newPreStep)
        val ids = preStep.keys.iterator()
        while (ids.hasNext()) {
            if (loaded.getById(ids.next()) == null) ids.remove()
        }
    }

    private fun noExplosives(): Boolean = byShip.isEmpty() && byWorld.isEmpty()

    fun addShip(be: CrystalExplosiveBlockEntity, shipId: ShipId) {
        byShip.computeIfAbsent(shipId) { ConcurrentHashMap() }[be.blockPos] = be
    }

    fun removeShip(be: CrystalExplosiveBlockEntity, shipId: ShipId) {
        val map = byShip[shipId] ?: return
        map.remove(be.blockPos, be)
        if (map.isEmpty()) byShip.remove(shipId, map)
    }

    fun addWorld(be: CrystalExplosiveBlockEntity, dim: DimensionId) {
        byWorld.computeIfAbsent(dim) { ConcurrentHashMap() }[be.blockPos] = be
    }

    fun removeWorld(be: CrystalExplosiveBlockEntity, dim: DimensionId) {
        val map = byWorld[dim] ?: return
        map.remove(be.blockPos, be)
        if (map.isEmpty()) byWorld.remove(dim, map)
    }

    // Collision callback: runs on the physics thread.
    @OptIn(PhysTickOnly::class)
    private fun handle(event: org.valkyrienskies.core.api.events.CollisionEvent) {
        val dim = event.dimensionId
        val shipBesA = byShip[event.shipIdA]
        val shipBesB = byShip[event.shipIdB]
        val worldBes = byWorld[dim]
        if (shipBesA == null && shipBesB == null && worldBes == null) return

        val physA = event.physLevel.getShipById(event.shipIdA)
        val physB = event.physLevel.getShipById(event.shipIdB)

        val probe = physProbe
        val probePos = physProbePos
        for (contact in event.contactPoints) {
            // Stale manifold restart: the bodies were already apart when the start event arrived.
            if (contact.separation > SEPARATION_EPSILON) continue
            val hitA = if (shipBesA != null && physA != null) {
                findShipSide(shipBesA, physA, contact, probe, probePos)
            } else {
                null
            }
            val hitB = if (shipBesB != null && physB != null) {
                findShipSide(shipBesB, physB, contact, probe, probePos)
            } else {
                null
            }
            val hitWorld = if (worldBes != null) findWorldSide(worldBes, contact, probePos) else null
            if (hitA == null && hitB == null && hitWorld == null) continue
            // Normal component only: sliding and pulling apart (a tome yank) are not hits.
            // Magnitude: VS2 does not fix which body is A, so the sign is not reliable.
            val n = contact.normal
            val approach = normalSpeed(event.shipIdA, physA, n) - normalSpeed(event.shipIdB, physB, n)
            if (Math.abs(approach) < DETONATION_SPEED) continue
            hitA?.queueHit()
            hitB?.queueHit()
            hitWorld?.queueHit()
        }
    }

    /** The body's velocity along [n]: pre-step for a loaded ship, else its current velocity (a VS body such
     *  as a cage; part-solved). A static body or terrain gives 0. */
    @OptIn(PhysTickOnly::class)
    private fun normalSpeed(id: ShipId, ship: PhysShip?, n: Vector3dc): Double {
        val v: Vector3dc = preStep[id] ?: ship?.takeUnless { it.isStatic }?.velocity ?: return 0.0
        return v.dot(n)
    }

    // Called only from [handle], on the physics thread.
    @OptIn(PhysTickOnly::class)
    private fun findShipSide(
        map: ConcurrentHashMap<BlockPos, CrystalExplosiveBlockEntity>,
        ship: PhysShip,
        contact: ContactPoint,
        probe: Vector3d,
        probePos: BlockPos.MutableBlockPos,
    ): CrystalExplosiveBlockEntity? {
        val pos = contact.position
        val n = contact.normal
        val worldToShip = ship.transform.worldToShip
        for (i in 0..1) {
            val sign = if (i == 0) -1.0 else 1.0
            probe.set(
                pos.x() + sign * n.x() * PROBE_DEPTH,
                pos.y() + sign * n.y() * PROBE_DEPTH,
                pos.z() + sign * n.z() * PROBE_DEPTH,
            )
            worldToShip.transformPosition(probe)
            explosiveNear(map, probe.x, probe.y, probe.z, probePos)?.let { return it }
        }
        return null
    }

    // Called only from [handle], on the physics thread.
    @OptIn(PhysTickOnly::class)
    private fun findWorldSide(
        map: ConcurrentHashMap<BlockPos, CrystalExplosiveBlockEntity>,
        contact: ContactPoint,
        probePos: BlockPos.MutableBlockPos,
    ): CrystalExplosiveBlockEntity? {
        val pos = contact.position
        val n = contact.normal
        for (i in 0..1) {
            val sign = if (i == 0) -1.0 else 1.0
            val be = explosiveNear(
                map,
                pos.x() + sign * n.x() * PROBE_DEPTH,
                pos.y() + sign * n.y() * PROBE_DEPTH,
                pos.z() + sign * n.z() * PROBE_DEPTH,
                probePos,
            )
            if (be != null) return be
        }
        return null
    }

    /** The explosive whose block, grown by [EDGE_SLACK], holds the point. Contacts on a lone block sit on its
     *  edges and corners, where floor() alone can pick the empty neighbour. */
    private fun explosiveNear(
        map: ConcurrentHashMap<BlockPos, CrystalExplosiveBlockEntity>,
        x: Double, y: Double, z: Double,
        probePos: BlockPos.MutableBlockPos,
    ): CrystalExplosiveBlockEntity? {
        val x0 = Mth.floor(x - EDGE_SLACK)
        val y0 = Mth.floor(y - EDGE_SLACK)
        val z0 = Mth.floor(z - EDGE_SLACK)
        val x1 = Mth.floor(x + EDGE_SLACK)
        val y1 = Mth.floor(y + EDGE_SLACK)
        val z1 = Mth.floor(z + EDGE_SLACK)
        for (bx in x0..x1) for (by in y0..y1) for (bz in z0..z1) {
            map[probePos.set(bx, by, bz)]?.let { return it }
        }
        return null
    }

    /** How far to probe inside each contact body along the contact normal when
     *  resolving "which block sits at the contact". Smaller than 0.5 (half a block)
     *  so a probe always lands in the touching block, never in its neighbour. */
    private const val PROBE_DEPTH: Double = 0.1

    /** Below [PROBE_DEPTH], so the grown box never reaches past the contact into the other body. */
    private const val EDGE_SLACK: Double = 0.02

    /** Tolerated positive [ContactPoint.separation] before a contact is treated as
     *  "not actually in contact". A few cm of slack absorbs floating-point noise + the
     *  tail of a contact-manifold restart without rejecting real glancing impacts. */
    private const val SEPARATION_EPSILON: Float = 0.05f

    /** Normal approach speed (m/s) at the contact. Mass-independent, like a Kerbal crash tolerance: about a
     *  5-block fall; landings and dock bumps stay under it, rams at cruise speed go over it. */
    private const val DETONATION_SPEED: Double = 10.0

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        byShip.clear()
        byWorld.clear()
        preStep.clear()
        CrystalExplosiveBlockEntity.clearSession()
    }
}
