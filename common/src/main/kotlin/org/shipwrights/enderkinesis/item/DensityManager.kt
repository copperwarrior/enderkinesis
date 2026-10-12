package org.shipwrights.enderkinesis.item

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.impl.game.ships.ShipInertiaDataImpl
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/** Server-side application of Staff-of-Density mass changes.
 *
 *  Mass mutation goes through [ShipInertiaDataImpl.addMassAt] — VS2's public API only
 *  exposes mass reads, but the impl class is on the runtime classpath via the
 *  `valkyrienskies-core:impl` module dep declared in `common/build.gradle`. Adding mass
 *  at the ship's *current center of mass* is the one location that leaves both the CoM
 *  and the inertia tensor unchanged (parallel-axis theorem contributes zero at the CoM),
 *  so the multiplier scales only the mass, not the rotation feel.
 *
 *  Per-ship state is persisted in a [DensityAttachment] saved on the ServerShip — VS2
 *  serialises attachments alongside the ship, so the multiplier survives world reload. */
object DensityManager {

    const val MIN_MULTIPLIER: Double = 0.5
    const val MAX_MULTIPLIER: Double = 2.0
    /** Within reach: ship's nearest world point ≤ this many blocks from the player. */
    const val INTERACTION_RANGE: Double = 64.0
    private const val INTERACTION_RANGE_SQ: Double = INTERACTION_RANGE * INTERACTION_RANGE
    private const val MASS_EPSILON: Double = 1.0e-6
    private const val DISCOVER_INTERVAL_TICKS = 100

    /** Read the current multiplier (1.0 if never set). Used by the BEGIN packet to
     *  initialise the client's drag accumulator so a tiny mouse motion doesn't reset
     *  a previously-set value. */
    fun getCurrentMultiplier(ship: LoadedServerShip): Double {
        val att = ship.getAttachment(DensityAttachment::class.java) ?: return 1.0
        return att.currentMultiplier
    }

    /** Ships with a multiplier that is not 1.0. Server thread only. */
    private val activeShips = LongOpenHashSet()
    private var discoverCountdown = 0

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(TickEvent.ServerLevelTick(::reconcileLevel))
        LifecycleEvent.SERVER_STOPPED.register { _ -> activeShips.clear() }
    }

    /** The staff must be in a hand, and the ship in the dimension and in reach of the player. */
    fun targetFor(player: ServerPlayer, shipId: Long): LoadedServerShip? {
        if (!player.isHolding(EKItems.STAFF_OF_DENSITY.get())) return null
        val level = player.serverLevel()
        val ship = level.shipObjectWorld.loadedShips.getById(shipId) ?: return null
        if (ship.chunkClaimDimension != level.dimensionId || !isReachable(player, ship)) return null
        return ship
    }

    /** Apply a new multiplier to the ship. Returns false on a validation failure. */
    fun commitMultiplier(player: ServerPlayer, shipId: Long, requestedMultiplier: Double): Boolean {
        // NaN passes `coerceIn` and makes the ship mass NaN.
        if (!requestedMultiplier.isFinite()) return false
        val ship = targetFor(player, shipId) ?: return false
        val att = ship.getAttachment(DensityAttachment::class.java) ?: DensityAttachment().apply {
            appliedExtraMass = 0.0
        }
        att.currentMultiplier = requestedMultiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        ship.setAttachment(att)
        activeShips.add(ship.id)
        return reconcile(ship, att)
    }

    // At the end of the level tick, before VS2 builds its physics frame at the end of the
    // server tick. A fixed offset on a ship that lost blocks gives zero or negative mass.
    private fun reconcileLevel(level: ServerLevel) {
        val ships = level.shipObjectWorld.loadedShips
        val dim = level.dimensionId
        if (level.dimension() == Level.OVERWORLD && --discoverCountdown <= 0) {
            discoverCountdown = DISCOVER_INTERVAL_TICKS
            for (ship in level.shipObjectWorld.loadedShips) {
                val att = ship.getAttachment(DensityAttachment::class.java) ?: continue
                if (att.currentMultiplier != 1.0 || att.appliedExtraMass.isNaN()) activeShips.add(ship.id)
            }
        }
        if (activeShips.isEmpty()) return
        val iter = activeShips.iterator()
        while (iter.hasNext()) {
            val ship = ships.getById(iter.nextLong()) ?: continue
            if (ship.chunkClaimDimension != dim) continue
            val att = ship.getAttachment(DensityAttachment::class.java)
            if (att == null) {
                iter.remove()
                continue
            }
            reconcile(ship, att)
            if (att.currentMultiplier == 1.0) iter.remove()
        }
    }

    /** Makes the added mass equal to `blockMass * (multiplier - 1)` for the present block mass. */
    private fun reconcile(ship: LoadedServerShip, att: DensityAttachment): Boolean {
        val inertia = ship.inertiaData as? ShipInertiaDataImpl ?: return false
        if (att.appliedExtraMass.isNaN()) {
            att.appliedExtraMass = att.originalMass * (att.currentMultiplier - 1.0)
        }
        val blockMass = inertia.mass - att.appliedExtraMass
        if (!blockMass.isFinite() || blockMass <= 0.0) return false
        val wanted = blockMass * (att.currentMultiplier - 1.0)
        val delta = wanted - att.appliedExtraMass
        if (Math.abs(delta) <= blockMass * MASS_EPSILON) return true
        // `addMassAt` is module-private in VS2; `onSetBlock` simulates a block change (old
        // mass to new mass) at a block position. The block of the centre of mass keeps the
        // parallel-axis term on the inertia tensor small.
        val com = inertia.centerOfMass
        inertia.onSetBlock(
            kotlin.math.floor(com.x()).toInt(),
            kotlin.math.floor(com.y()).toInt(),
            kotlin.math.floor(com.z()).toInt(),
            0.0, delta,
        )
        att.appliedExtraMass = wanted
        ship.setAttachment(att)
        return true
    }

    private fun isReachable(player: ServerPlayer, ship: LoadedServerShip): Boolean {
        val px = player.x
        val py = player.y
        val pz = player.z
        // Use the ship's world AABB to find the nearest in-bound point — long ships
        // shouldn't fail the range test just because their CoM is far.
        val aabb = ship.worldAABB
        val nx = px.coerceIn(aabb.minX(), aabb.maxX())
        val ny = py.coerceIn(aabb.minY(), aabb.maxY())
        val nz = pz.coerceIn(aabb.minZ(), aabb.maxZ())
        val dx = nx - px
        val dy = ny - py
        val dz = nz - pz
        return (dx * dx + dy * dy + dz * dz) <= INTERACTION_RANGE_SQ
    }
}
