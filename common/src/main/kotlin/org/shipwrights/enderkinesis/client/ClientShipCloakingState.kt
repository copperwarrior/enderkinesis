package org.shipwrights.enderkinesis.client

import java.util.concurrent.ConcurrentHashMap

/** Client mirror of [org.shipwrights.enderkinesis.item.ShipCloakingTracker]. Receives
 *  S2C cloak-state transitions and animates a per-ship fade timer locally — the server
 *  doesn't send per-tick progress, just the start/stop edges.
 *
 *  Progress goes `0.0 → 1.0` over [FADE_DURATION_MS] when cloaking, then reverses when
 *  uncloaking. Renderers query [getProgress] each frame; the timer is wall-clock based
 *  so it stays smooth across variable frame rates. */
object ClientShipCloakingState {

    private const val FADE_DURATION_MS: Long = 5_000L

    private val states: MutableMap<Long, CloakState> = ConcurrentHashMap()

    private class CloakState(
        var direction: Int,            // +1 cloaking, -1 uncloaking, 0 settled
        var progress: Float,           // 0.0 (visible) → 1.0 (fully cloaked)
        var lastAdvanceMs: Long,
    )

    /** S2C entry point — called from [org.shipwrights.enderkinesis.item.ConcealmentNetwork]. */
    fun onServerUpdate(shipId: Long, cloaking: Boolean) {
        val now = nowMs()
        val existing = states[shipId]
        if (existing == null) {
            // Fresh entry. If "uncloaking" arrives without prior cloak (race after late
            // join), just clamp to 0 and drop the direction.
            if (!cloaking) return
            states[shipId] = CloakState(direction = +1, progress = 0f, lastAdvanceMs = now)
            return
        }
        advance(existing, now)
        existing.direction = if (cloaking) +1 else -1
    }

    /** Per-frame query. Returns 0.0 (fully visible) → 1.0 (fully cloaked). */
    fun getProgress(shipId: Long): Float {
        val state = states[shipId] ?: return 0f
        advance(state, nowMs())
        // Once fully uncloaked, drop the entry to keep the map sparse.
        if (state.direction == -1 && state.progress <= 0f) {
            states.remove(shipId)
            return 0f
        }
        return state.progress
    }

    /** Disconnect cleanup — wipe everything. Called on world-change / world-leave. */
    fun reset() {
        states.clear()
    }

    /** Snapshot of ship IDs with any cloak activity (progress > 0 or actively cloaking).
     *  Used by [ConcealmentPostEffect] to drive the screen-warp uniforms. */
    fun activeShipIds(): Collection<Long> = states.keys.toList()

    private fun advance(state: CloakState, nowMs: Long) {
        if (state.direction == 0) {
            state.lastAdvanceMs = nowMs
            return
        }
        val dtMs = nowMs - state.lastAdvanceMs
        state.lastAdvanceMs = nowMs
        val deltaProgress = (dtMs.toFloat() / FADE_DURATION_MS.toFloat()) * state.direction
        state.progress = (state.progress + deltaProgress).coerceIn(0f, 1f)
        // Settle when an endpoint is reached.
        if (state.direction == +1 && state.progress >= 1f) state.direction = 0
        if (state.direction == -1 && state.progress <= 0f) state.direction = 0
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
