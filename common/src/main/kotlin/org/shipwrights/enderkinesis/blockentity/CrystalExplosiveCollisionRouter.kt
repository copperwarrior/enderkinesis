package org.shipwrights.enderkinesis.blockentity

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import org.joml.Vector3d
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.physics.ContactPoint
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.vsApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes VS2 `collisionStartEvent` impulses to crystal explosives whose **exact
 * block position** is one of the two blocks meeting at the contact.
 *
 * A contact happens between two surfaces. In `byShip`/`byWorld` we have every
 * registered crystal-explosive BE keyed by the block position it occupies; for each
 * contact we figure out which two block coordinates straddle the contact plane on
 * each side and only fire the BE that sits exactly on one of them. A nearby-but-not-
 * involved explosive (say at the stern while the bow takes the hit) won't fire.
 *
 * Both ship sides and the world side are handled. A crystal explosive sitting in the
 * loose world detonates when a ship rams the block it occupies — the ship-side
 * passes its shipId, the world-side passes the dimension. Either, both, or neither
 * end of a contact may be a registered explosive.
 *
 * Threading: the collision listener fires on the physics thread and is not safe with
 * the game thread. All writes here are either CHM operations (registry mutation,
 * routing lookups) or a single atomic CAS into the BE's `pendingImpulse` field — the
 * actual `Level.explode` call happens on the game thread in the BE's `serverTick`.
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

    fun register() {
        if (registered) return
        registered = true
        vsApi.collisionStartEvent.on { event, _ ->
            handle(event)
        }
    }

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

    private fun handle(event: org.valkyrienskies.core.api.events.CollisionEvent) {
        val dim = event.dimensionId
        val shipBesA = byShip[event.shipIdA]
        val shipBesB = byShip[event.shipIdB]
        val worldBes = byWorld[dim]
        if (shipBesA == null && shipBesB == null && worldBes == null) return

        val physA = event.physLevel.getShipById(event.shipIdA)
        val physB = event.physLevel.getShipById(event.shipIdB)
        val mA = physA?.mass ?: 0.0
        val mB = physB?.mass ?: 0.0
        val effectiveMass = if (mA > 0.0 && mB > 0.0) {
            (mA * mB) / (mA + mB)
        } else {
            Math.max(mA, mB)
        }
        if (effectiveMass <= 0.0) return

        for (contact in event.contactPoints) {
            val v = contact.velocity
            val speed = Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z())
            if (speed <= 0.0) continue
            val impulse = effectiveMass * speed

            if (shipBesA != null && physA != null) {
                checkShipSide(shipBesA, physA, contact, impulse, effectiveMass, speed, "A", event.shipIdA)
            }
            if (shipBesB != null && physB != null) {
                checkShipSide(shipBesB, physB, contact, impulse, effectiveMass, speed, "B", event.shipIdB)
            }
            if (worldBes != null) {
                checkWorldSide(worldBes, contact, impulse, effectiveMass, speed)
            }
        }
    }

    private fun checkShipSide(
        map: ConcurrentHashMap<BlockPos, CrystalExplosiveBlockEntity>,
        ship: PhysShip,
        contact: ContactPoint,
        impulse: Double,
        mass: Double,
        speed: Double,
        sideLabel: String,
        shipId: ShipId,
    ) {
        val pos = contact.position
        val n = contact.normal
        val worldToShip = ship.transform.worldToShip
        for (sign in arrayOf(-1.0, 1.0)) {
            val probe = Vector3d(
                pos.x() + sign * n.x() * PROBE_DEPTH,
                pos.y() + sign * n.y() * PROBE_DEPTH,
                pos.z() + sign * n.z() * PROBE_DEPTH,
            )
            worldToShip.transformPosition(probe)
            val bp = BlockPos.containing(probe.x, probe.y, probe.z)
            val be = map[bp]
            if (be != null) {
                LOG.info(
                    "collision hit ship={} side={} block={} contactWorld=({},{},{}) speed={} effMass={} impulse={}",
                    shipId, sideLabel, bp,
                    "%.2f".format(pos.x()), "%.2f".format(pos.y()), "%.2f".format(pos.z()),
                    "%.3f".format(speed), "%.1f".format(mass), "%.1f".format(impulse),
                )
                be.queueDetonation(impulse)
                return
            }
        }
    }

    private fun checkWorldSide(
        map: ConcurrentHashMap<BlockPos, CrystalExplosiveBlockEntity>,
        contact: ContactPoint,
        impulse: Double,
        mass: Double,
        speed: Double,
    ) {
        val pos = contact.position
        val n = contact.normal
        for (sign in arrayOf(-1.0, 1.0)) {
            val px = pos.x() + sign * n.x() * PROBE_DEPTH
            val py = pos.y() + sign * n.y() * PROBE_DEPTH
            val pz = pos.z() + sign * n.z() * PROBE_DEPTH
            val bp = BlockPos.containing(px, py, pz)
            val be = map[bp]
            if (be != null) {
                LOG.info(
                    "collision hit world block={} contactWorld=({},{},{}) speed={} effMass={} impulse={}",
                    bp,
                    "%.2f".format(pos.x()), "%.2f".format(pos.y()), "%.2f".format(pos.z()),
                    "%.3f".format(speed), "%.1f".format(mass), "%.1f".format(impulse),
                )
                be.queueDetonation(impulse)
                return
            }
        }
    }

    private val LOG = LogUtils.getLogger()

    /** How far to probe inside each contact body along the contact normal when
     *  resolving "which block sits at the contact". Smaller than 0.5 (half a block)
     *  so a probe always lands in the touching block, never in its neighbour. */
    private const val PROBE_DEPTH: Double = 0.1
}
