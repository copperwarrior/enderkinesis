package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.impl.game.ships.ShipInertiaDataImpl
import org.valkyrienskies.mod.common.BlockStateInfo

/**
 * VS2 resets a ship to mass 0 / zero tensor when a removal overshoots the recorded mass.
 * That drops earlier blocks from the tensor, so their later removal leaves it
 * non-positive-definite, and the solver turns that into NaN.
 */
internal object ConstriliarInertia {
    private const val REBUILD_CELL_BUDGET = 32_768

    /** Sylvester's criterion on the stored tensor, plus a finite positive mass. */
    fun isValid(ship: ServerShip): Boolean {
        val data = ship.inertiaData
        val m = data.inertiaTensor
        return data.mass.isFinite() && data.mass > 0.0 &&
            m.m00() > 0.0 &&
            m.m00() * m.m11() - m.m01() * m.m10() > 0.0 &&
            m.determinant() > 0.0
    }

    /** Rebuilds mass and tensor from the ship's blocks, with [platformMass] at the plate.
     *  False when VS2's inertia internals are not the expected type. */
    fun rebuild(level: ServerLevel, ship: ServerShip, platformPos: BlockPos, platformMass: Double): Boolean {
        val inertia = ship.inertiaData as? ShipInertiaDataImpl ?: return false
        val recorded = if (inertia.mass.isFinite()) Math.abs(inertia.mass) else 0.0
        // An overshoot on the first of onSetBlock's 8 corner adds trips VS2's own reset.
        inertia.onSetBlockUseSphereMOI(platformPos.x, platformPos.y, platformPos.z, 0.0, -8.0 * (recorded + 1.0))
        ConstriliarCargo.forEachShipBlock(level, ship, REBUILD_CELL_BUDGET) { pos, state ->
            val mass = if (pos == platformPos) platformMass else BlockStateInfo.get(state)?.first
            if (mass != null && mass > 0.0) inertia.onSetBlockUseSphereMOI(pos.x, pos.y, pos.z, 0.0, mass)
        }
        return true
    }
}
