package org.shipwrights.enderkinesis.physics

import org.shipwrights.enderkinesis.util.ServerTicks
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.VsBeta

/**
 * The Rod of Levitation lift on one ship. The nudge goes through [HeldPushes].
 *
 * - Touch-driven hover: while [touch] keeps being called from the game thread (the manager
 *   does so each game tick until the 4-second window expires), the phys tick applies a
 *   P-controlled force toward [targetWorldY]. The manager also calls [ShipGravityHold.hold],
 *   which cancels gravity. A stale touch releases the ship cleanly.
 */
@OptIn(PhysTickOnly::class, VsBeta::class)
class RodLevitationAttachment : ShipPhysicsListener {

    @Volatile private var lastTouchTick: Int = NEVER
    @Volatile private var targetWorldY: Double = Double.NaN

    fun touch(target: Double) {
        targetWorldY = target
        lastTouchTick = ServerTicks.now
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        physTick(physShip, physLevel, 0.0)
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel, delta: Double) {
        // Touch-driven lift. Three explicit stages:
        //   Stage 1 (rise):  currentY is more than HOVER_THRESHOLD below targetY → drive
        //                    velocity to MAX_LIFT_VEL upward.
        //   Stage 2 (hover): currentY is within HOVER_THRESHOLD of targetY → snap velocity
        //                    to zero (block hangs in place).
        //   Stage 3 (release): manager stops touching → staleness check trips → we
        //                      return early, gravity resumes (no lift force applied).
        if (isStale(lastTouchTick)) return        // ← Stage 3
        if (physShip.isStatic || physShip.mass <= 0.0) return
        val target = targetWorldY
        if (target.isNaN()) return

        // It assumes [ShipGravityHold] has cancelled gravity.
        val forceY = liftForceY(physShip.mass, physShip.transform.positionInWorld.y(), physShip.velocity.y(), target, physicsStepSeconds(delta))
        physShip.applyWorldForceToBodyPos(Vector3d(0.0, forceY, 0.0))
    }

    companion object {
        /** The lift force on a target of [mass] at [currentY], without gravity, for a physics step of [stepSeconds]. */
        fun liftForceY(mass: Double, currentY: Double, currentVy: Double, targetY: Double, stepSeconds: Double): Double {
            val targetVy = if (targetY - currentY > HOVER_THRESHOLD) {
                MAX_LIFT_VEL                                    // ← Stage 1
            } else {
                0.0                                             // ← Stage 2
            }
            // F = m · Δv/Δt drives velocity to targetVy in one phys tick, up to the acceleration cap.
            return mass * ((targetVy - currentVy) / stepSeconds).coerceIn(-MAX_LIFT_ACCEL, MAX_LIFT_ACCEL)
        }

        /** Game-tick slack on the touch heartbeat, shared with the body lift. */
        fun isStale(touchTick: Int): Boolean = ServerTicks.now - touchTick > STALENESS_TICKS

        /** m/s². Stops a ship pinned under a ceiling from getting a huge force. */
        private const val MAX_LIFT_ACCEL = 20.0

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
