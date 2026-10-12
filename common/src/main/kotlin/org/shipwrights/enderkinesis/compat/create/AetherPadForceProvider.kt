package org.shipwrights.enderkinesis.compat.create

import org.shipwrights.enderkinesis.util.ServerTicks
import java.util.concurrent.ConcurrentHashMap
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel

/**
 * Per-ship VS2 attachment that lets multiple Aether Pads mounted on a Create contraption queue
 * lift forces from the game thread for the phys thread to apply.
 *
 *  Differs from [ContraptionHarnessForceProvider] in shape: the harness applies *one* steering
 *  pull and *one* COM accumulator total. A hover vehicle has many pads at distinct positions
 *  and we want each one to apply its lift force at its own model-space block centre so the
 *  ship can naturally pitch/roll as pads come in and out of ground effect. Keyed by pad
 *  position (`BlockPos.asLong`) so concurrent stagers from different pads coexist; latest-set
 *  wins per pad, with a staleness cutoff so abandoned cached values (contraption disassembled,
 *  pad disabled) clear themselves out.
 */
class AetherPadForceProvider : ShipPhysicsListener {

    /** One entry per pad. World-frame force vector + the model-space (shipyard) position to
     *  apply it at, plus the server tick of the last refresh. */
    private data class StagedForce(
        val fx: Double, val fy: Double, val fz: Double,
        val mx: Double, val my: Double, val mz: Double,
        val setAtTick: Int,
    )

    private val staged = ConcurrentHashMap<Long, StagedForce>()

    /** Stage one pad's lift force for the next phys tick. [padKey] is `BlockPos.asLong`
     *  of the pad's model-space position — uniquely identifies the pad on a given ship,
     *  so a single pad's later stage() overwrites its earlier one within the same game
     *  tick (the latest direction wins) while different pads accumulate side-by-side. */
    fun stage(
        padKey: Long,
        fx: Double, fy: Double, fz: Double,
        mx: Double, my: Double, mz: Double,
    ) {
        staged[padKey] = StagedForce(fx, fy, fz, mx, my, mz, ServerTicks.now)
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        val now = ServerTicks.now
        // Block-sourced scale³ multiplier — preserves ship-lengths-per-second
        // across Staff-of-Scales settings (VS2's drag is cross-section ∝ scale²).
        val s1 = physShip.transform.shipToWorldScaling.x()
        val s = s1 * s1 * s1
        val iter = staged.entries.iterator()
        while (iter.hasNext()) {
            val (_, f) = iter.next()
            if (now - f.setAtTick > STALENESS_TICKS) {
                iter.remove()
                continue
            }
            physShip.applyWorldForceToModelPos(
                Vector3d(f.fx * s, f.fy * s, f.fz * s),
                Vector3d(f.mx, f.my, f.mz),
            )
        }
    }


    companion object {
        /** Server ticks after which a staged entry is considered abandoned — past that
         *  the contraption is probably gone, the pad lost power, or moved out of ground range. */
        private const val STALENESS_TICKS = 2
    }
}
