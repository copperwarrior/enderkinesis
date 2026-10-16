package org.shipwrights.enderkinesis.physics

import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.level.ServerLevel
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/** Removes lattice inducers that no lattice drives, e.g. on a fracture piece that lost its lattice. */
object LatticeInducerSweep {
    private const val SWEEP_INTERVAL_TICKS = 20L

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::sweep)
    }

    private fun sweep(level: ServerLevel) {
        if (level.gameTime % SWEEP_INTERVAL_TICKS != 0L) return
        val dim = level.dimensionId
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dim) continue
            val inducer = ship.getAttachment(CrepusculiteLatticeForceInducer::class.java) ?: continue
            if (!isStale(level, inducer) || mayHoldLattice(level, ship)) continue
            ship.removeAttachment(CrepusculiteLatticeForceInducer::class.java)
        }
    }

    private fun isStale(level: ServerLevel, inducer: CrepusculiteLatticeForceInducer): Boolean =
        inducer.authorityTickedAt == Long.MIN_VALUE ||
            level.gameTime - inducer.authorityTickedAt > CrepusculiteLatticeForceInducer.AUTHORITY_STALE_TICKS

    // An unloaded chunk counts as a possible lattice: a lattice in a non-ticking chunk must keep its buoyancy.
    private fun mayHoldLattice(level: ServerLevel, ship: LoadedServerShip): Boolean {
        var found = false
        ship.activeChunksSet.forEach { cx, cz ->
            if (found) return@forEach
            val chunk = level.chunkSource.getChunkNow(cx, cz)
            found = chunk == null || chunk.blockEntities.values.any {
                it is CrepusculiteLatticeBlockEntity && !it.isRemoved
            }
        }
        return found
    }
}
