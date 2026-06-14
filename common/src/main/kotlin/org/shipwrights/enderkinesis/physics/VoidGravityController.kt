package org.shipwrights.enderkinesis.physics

import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel

/**
 * Per-ship attachment that cancels gravity on the ship for as long as some external system
 * keeps [touch]ing it. Modelled on VMod's `GravityController` — the per-ship-attachment
 * shape is what lets us avoid per-caller coordination: any number of void hooks can hold
 * the same ship in their cone, but the cancellation force is applied exactly once per phys
 * tick by this attachment.
 *
 * Lifecycle is touch-driven, not refcount-driven. The game thread (a hook's `serverTick`)
 * calls [touch] each game tick while it wants gravity off for this ship; the phys thread
 * checks the wall-clock staleness of the last touch each tick and applies `+m·g` upward
 * only if a touch landed recently. When no one keeps touching, gravity restores
 * automatically — no explicit deactivation, no leaks if a hook is destroyed mid-grab.
 */
class VoidGravityController : ShipPhysicsListener {

    @Volatile private var lastTouchMs: Long = Long.MIN_VALUE

    fun touch() {
        lastTouchMs = System.nanoTime() / 1_000_000L
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        val now = System.nanoTime() / 1_000_000L
        if (now - lastTouchMs > STALENESS_MS) return
        if (physShip.isStatic || physShip.mass <= 0.0) return
        physShip.applyInvariantForce(Vector3d(0.0, physShip.mass * GRAVITY, 0.0))
    }

    companion object {
        /** VS2 world gravity magnitude (m/s²). Matches the dimension default; we don't read
         *  [PhysLevel.getGravity] because the attachment lives across phys ticks and the
         *  constant is the only value the mod ever sets at the dimension level. */
        private const val GRAVITY = 10.0

        /** Two game-tick windows of slack (50 ms × 2). Past that, the caller is either
         *  gone, lost power, or lost cone coverage — restore normal gravity. */
        private const val STALENESS_MS = 100L
    }
}
