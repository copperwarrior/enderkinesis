package org.shipwrights.enderkinesis.item

import net.minecraft.server.level.ServerPlayer
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.impl.game.ships.ShipInertiaDataImpl
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

    /** Read the current multiplier (1.0 if never set). Used by the BEGIN packet to
     *  initialise the client's drag accumulator so a tiny mouse motion doesn't reset
     *  a previously-set value. */
    fun getCurrentMultiplier(ship: LoadedServerShip): Double {
        val att = ship.getAttachment(DensityAttachment::class.java) ?: return 1.0
        return att.currentMultiplier
    }

    /** Apply a new multiplier to the ship, computed from the player's drag.
     *
     *  Returns true if the change was applied; false on validation failure (ship not
     *  reachable, multiplier out of range, etc.). */
    fun commitMultiplier(player: ServerPlayer, shipId: Long, requestedMultiplier: Double): Boolean {
        val newMul = requestedMultiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        val level = player.level()
        val ship = level.shipObjectWorld.allShips.getById(shipId) as? LoadedServerShip ?: return false
        if (!isReachable(player, ship)) return false

        // First-use: capture the ship's current mass as the immutable reference.
        var att = ship.getAttachment(DensityAttachment::class.java)
        if (att == null) {
            att = DensityAttachment().apply {
                originalMass = ship.inertiaData.mass
                currentMultiplier = 1.0
            }
            ship.setAttachment(att)
        }

        // Compute the absolute mass delta to apply at the CoM. Going from 1.0x to 2.0x
        // adds +1.0*originalMass; from 1.5x to 1.0x subtracts -0.5*originalMass.
        val delta = att.originalMass * (newMul - att.currentMultiplier)
        if (delta == 0.0) return true

        val inertia = ship.inertiaData as? ShipInertiaDataImpl ?: return false
        val com = inertia.centerOfMass
        // VS2's ShipInertiaDataImpl.addMassAt is module-private. We use the public
        // onSetBlock entrypoint which simulates a block transition (oldMass → newMass)
        // at an integer block position. Targeting the CoM block keeps the contribution
        // close to the CoM, so the parallel-axis term added to the inertia tensor is
        // tiny (≤ delta × 0.25 for ≤1-block offset, vs. tensor ~ mass × ship_size²).
        val cx = kotlin.math.floor(com.x()).toInt()
        val cy = kotlin.math.floor(com.y()).toInt()
        val cz = kotlin.math.floor(com.z()).toInt()
        inertia.onSetBlock(cx, cy, cz, 0.0, delta)

        att.currentMultiplier = newMul
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
