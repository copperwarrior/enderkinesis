package org.shipwrights.enderkinesis.compat.create

import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel

/**
 * Per-ship VS2 attachment letting a contraption-mounted Void Harness queue forces from the
 * game thread for the phys thread to apply, via [accumulateForce] (world-frame COM impulses)
 * and [setOffComSteering] (cached pos snapshot, re-projected each phys tick).
 *
 * Both channels are sticky: phys reads but doesn't clear, with a wallclock staleness
 * window for cleanup. A ship-resident harness gets ~3 phys-tick impulses per game tick
 * (game tick at 20 Hz overwrites cache that phys reads at 60 Hz). An earlier iteration here
 * cleared on first read → contraption harness was ~1/3 the effective force of a resident
 * one. Stickiness matches the resident pattern.
 */
class ContraptionHarnessForceProvider : ShipPhysicsListener {

    private val lock = Any()

    private var fx = 0.0; private var fy = 0.0; private var fz = 0.0
    private var lastAccumMs = Long.MIN_VALUE

    private var hasOffCom = false
    private var owx = 0.0; private var owy = 0.0; private var owz = 0.0
    private var omx = 0.0; private var omy = 0.0; private var omz = 0.0
    private var peakForceMag = 0.0
    private var lastOffComSetMs = Long.MIN_VALUE

    /** Add a world-frame force vector at the ship's COM. The accumulator auto-resets when more
     *  than [ACCUM_RESET_MS] has elapsed since the last call (game-tick boundary); within a
     *  single game tick multiple harnesses can contribute and their forces sum cleanly. */
    fun accumulateForce(x: Double, y: Double, z: Double) {
        synchronized(lock) {
            val now = nowMs()
            if (now - lastAccumMs > ACCUM_RESET_MS) {
                fx = 0.0; fy = 0.0; fz = 0.0
            }
            lastAccumMs = now
            fx += x; fy += y; fz += z
        }
    }

    /** Stage a same-ship-style XZ steering pull on the host ship. [worldX/Y/Z] is the harness's
     *  current world position — phys tick uses it (NOT a re-projection of the model position)
     *  to compute the world-frame direction against the live phys-tick COM, mirroring the
     *  ship-resident harness exactly. [modelX/Y/Z] is the shipyard position where the off-COM
     *  force is applied so it produces a torque about the COM. Latest setter wins. */
    fun setOffComSteering(
        worldX: Double, worldY: Double, worldZ: Double,
        modelX: Double, modelY: Double, modelZ: Double,
        peakForceMag: Double,
    ) {
        synchronized(lock) {
            owx = worldX; owy = worldY; owz = worldZ
            omx = modelX; omy = modelY; omz = modelZ
            this.peakForceMag = peakForceMag
            hasOffCom = true
            lastOffComSetMs = nowMs()
        }
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        val now = nowMs()
        // Snapshot under the lock; release before touching the phys API to keep critical
        // sections short. NB: do NOT clear `fx`/`fy`/`fz` or `hasOffCom` here — those are
        // overwritten by the game thread each game tick, and clearing them mid-game-tick would
        // collapse our effective force rate from ~60 Hz to ~20 Hz (see file header comment).
        var comX = 0.0; var comY = 0.0; var comZ = 0.0; var haveCom = false
        var offWx = 0.0; var offWz = 0.0
        var offMx = 0.0; var offMy = 0.0; var offMz = 0.0
        var offPeak = 0.0; var haveOff = false
        synchronized(lock) {
            if (now - lastAccumMs > STALENESS_MS) {
                fx = 0.0; fy = 0.0; fz = 0.0
            } else if (fx != 0.0 || fy != 0.0 || fz != 0.0) {
                comX = fx; comY = fy; comZ = fz; haveCom = true
            }
            if (hasOffCom) {
                if (now - lastOffComSetMs > STALENESS_MS) {
                    hasOffCom = false
                } else {
                    offWx = owx; offWz = owz
                    offMx = omx; offMy = omy; offMz = omz
                    offPeak = peakForceMag
                    haveOff = true
                }
            }
        }
        // Block-sourced scale³ multiplier — matches every other block-sourced
        // applier for scale-invariant ship-feel.
        val s1 = physShip.transform.shipToWorldScaling.x()
        val s = s1 * s1 * s1
        if (haveCom) physShip.applyInvariantForce(Vector3d(comX * s, comY * s, comZ * s))
        if (haveOff) {
            val com = physShip.kinematics.position
            val dx = offWx - com.x()
            val dz = offWz - com.z()
            val xz = Math.sqrt(dx * dx + dz * dz)
            if (xz >= 1.0e-3) {
                val mag = offPeak * s
                physShip.applyWorldForceToModelPos(
                    Vector3d(dx / xz * mag, 0.0, dz / xz * mag),
                    Vector3d(offMx, offMy, offMz),
                )
            }
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    companion object {
        /** Treat the COM accumulator as belonging to a "new game tick" if more than this many
         *  milliseconds elapsed since the last [accumulateForce] call. 30 ms safely separates
         *  server tick boundaries (game tick = 50 ms at 20 TPS) without splitting multiple
         *  harnesses' calls that fire back-to-back within a single tick. */
        private const val ACCUM_RESET_MS = 30L

        /** If the game thread hasn't refreshed a channel in this long, the cached value is
         *  considered abandoned and cleared. Two game-tick windows of slack — past that the
         *  contraption is probably gone or the harness has lost power. */
        private const val STALENESS_MS = 100L
    }
}
