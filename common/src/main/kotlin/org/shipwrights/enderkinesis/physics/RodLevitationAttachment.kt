package org.shipwrights.enderkinesis.physics

import org.shipwrights.enderkinesis.util.ServerTicks
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-ship attachment used by the Rod of Levitation. Wears two hats so a single ship can
 * be the target of both behaviours simultaneously:
 *
 * - Touch-driven hover: while [touch] keeps being called from the game thread (the manager
 *   does so each game tick until the 4-second window expires), the phys tick applies a
 *   P-controlled force toward [targetWorldY]. The manager also calls [ShipGravityHold.hold],
 *   which cancels gravity. A stale touch releases the ship cleanly.
 *
 * - Queued one-shot impulse: [queueNudge] parks a (force, COM-relative position) pair
 *   that the next phys tick consumes via `applyInvariantForceToPos`. That single tick of
 *   force is the "slight nudge" — translation + induced torque both come out of the
 *   off-COM application.
 */
class RodLevitationAttachment : ShipPhysicsListener {

    @Volatile private var lastTouchTick: Int = NEVER
    @Volatile private var targetWorldY: Double = Double.NaN

    private val pendingNudge = AtomicReference<Pair<Vector3dc, Vector3dc>?>(null)

    fun touch(target: Double) {
        targetWorldY = target
        lastTouchTick = ServerTicks.now
    }

    fun queueNudge(force: Vector3dc, comRelative: Vector3dc) {
        pendingNudge.set(force to comRelative)
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        // One-shot impulse first — consumes the slot whether or not the ship is liftable.
        val nudge = pendingNudge.getAndSet(null)
        if (nudge != null && !physShip.isStatic && physShip.mass > 0.0) {
            physShip.applyInvariantForceToPos(Vector3d(nudge.first), Vector3d(nudge.second))
        }

        // Touch-driven lift. Three explicit stages:
        //   Stage 1 (rise):  currentY is more than HOVER_THRESHOLD below targetY → drive
        //                    velocity to MAX_LIFT_VEL upward.
        //   Stage 2 (hover): currentY is within HOVER_THRESHOLD of targetY → snap velocity
        //                    to zero (block hangs in place).
        //   Stage 3 (release): manager stops touching → staleness check trips → we
        //                      return early, gravity resumes (no lift force applied).
        if (ServerTicks.now - lastTouchTick > STALENESS_TICKS) return        // ← Stage 3
        if (physShip.isStatic || physShip.mass <= 0.0) return
        val target = targetWorldY
        if (target.isNaN()) return

        val mass = physShip.mass
        val currentY = physShip.transform.positionInWorld.y()
        val currentVy = physShip.velocity.y()
        val targetVy = if (target - currentY > HOVER_THRESHOLD) {
            MAX_LIFT_VEL                                    // ← Stage 1
        } else {
            0.0                                             // ← Stage 2
        }
        // F = m · Δv/Δt drives velocity to targetVy in one phys tick. It assumes
        // [ShipGravityHold] has cancelled gravity.
        val forceY = mass * (targetVy - currentVy) / PHYS_DT
        physShip.applyInvariantForce(Vector3d(0.0, forceY, 0.0))
    }

    companion object {
        /** Approximate phys tick interval (s). VS2's phys runs ~60 Hz; the exact value
         *  only affects responsiveness, not correctness. */
        private const val PHYS_DT = 1.0 / 60.0

        /** Rise velocity during Stage 1 (m/s). */
        private const val MAX_LIFT_VEL = 4.0

        /** Position-error threshold for the Stage 1 → Stage 2 handoff (blocks). With
         *  [MAX_LIFT_VEL] = 4 m/s and dt ≈ 1/60 s, the per-tick rise is ~0.067 blocks,
         *  so a threshold of 0.1 keeps the overshoot under 1/15 of a block — the snap
         *  to rest looks instantaneous. */
        private const val HOVER_THRESHOLD = 0.1

        /** Game-tick slack on the touch heartbeat. Past that the manager has either
         *  cleared the entry or the ship has gone away. */
        private const val STALENESS_TICKS = 2
        private const val NEVER = Int.MIN_VALUE / 2
    }
}
