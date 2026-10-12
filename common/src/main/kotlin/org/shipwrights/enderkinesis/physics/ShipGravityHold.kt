package org.shipwrights.enderkinesis.physics

import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.ShipGravityAttachment

/**
 * Cancels gravity through VS2's [ShipGravityAttachment] while any system calls [hold] each
 * game tick. VS-core saves both attachments with the ship, so this marker keeps the value
 * to restore after the holds stop, or after an unload, stop or crash.
 */
@OptIn(VsBeta::class)
class ShipGravityHold(var savedOverride: Double? = null) {

    // Game thread only. Transient, so a marker loaded from disk is stale at once.
    @Transient private var lastHoldTick: Int = NEVER
    @Transient private var written: ShipGravityAttachment? = null

    /** `/vs gravity` replaces the attachment instance, so a new instance or value is a newer
     *  intent than [savedOverride]. */
    private fun adoptExternalChange(current: ShipGravityAttachment?) {
        val ours = written ?: return
        if (current !== ours || ours.gravityOverride != CANCELLED) {
            savedOverride = current?.gravityOverride
        }
    }

    companion object {
        private const val NEVER = -1
        private const val CANCELLED = 0.0

        /** Game ticks without a [hold] before the original gravity comes back. */
        private const val STALE_TICKS = 2

        fun hold(level: ServerLevel, ship: LoadedServerShip) {
            val marker = ship.getAttachment(ShipGravityHold::class.java)
                ?: ShipGravityHold(ship.getAttachment(ShipGravityAttachment::class.java)?.gravityOverride)
                    .also { ship.setAttachment(it) }
            marker.lastHoldTick = level.server.tickCount
        }

        /** Called by ShipWelderGravityHoldMixin, the only writer of the VS2 attachment. */
        @JvmStatic
        fun applyAll(level: ServerLevel) {
            val now = level.server.tickCount
            for (ship in level.shipObjectWorld.loadedShips) {
                val marker = ship.getAttachment(ShipGravityHold::class.java) ?: continue
                val current = ship.getAttachment(ShipGravityAttachment::class.java)
                marker.adoptExternalChange(current)
                if (marker.lastHoldTick != NEVER && now - marker.lastHoldTick <= STALE_TICKS) {
                    val attachment = current ?: ShipGravityAttachment().also { ship.setAttachment(it) }
                    attachment.gravityOverride = CANCELLED
                    marker.written = attachment
                } else {
                    current?.gravityOverride = marker.savedOverride
                    ship.removeAttachment(ShipGravityHold::class.java)
                }
            }
        }
    }
}
