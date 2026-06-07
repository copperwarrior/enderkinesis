package org.shipwrights.enderkinesis.compat.create

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
     *  apply it at, plus the wallclock the entry was last refreshed. */
    private data class StagedForce(
        val fx: Double, val fy: Double, val fz: Double,
        val mx: Double, val my: Double, val mz: Double,
        val setAtMs: Long,
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
        staged[padKey] = StagedForce(fx, fy, fz, mx, my, mz, nowMs())
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        val now = nowMs()
        val iter = staged.entries.iterator()
        while (iter.hasNext()) {
            val (_, f) = iter.next()
            if (now - f.setAtMs > STALENESS_MS) {
                iter.remove()
                continue
            }
            physShip.applyWorldForceToModelPos(
                Vector3d(f.fx, f.fy, f.fz),
                Vector3d(f.mx, f.my, f.mz),
            )
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    companion object {
        /** Wallclock window after which a staged entry is considered abandoned. Two game-
         *  tick windows of slack — past that the contraption is probably gone, the pad
         *  lost power, or moved out of ground range. */
        private const val STALENESS_MS = 100L
    }
}
