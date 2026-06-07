package org.shipwrights.enderkinesis.physics

/**
 * Global leaky-bucket budget for hull-splash particles.
 *
 * Splashes are emitted per-tick by every loaded crepusculite lattice when its exterior blocks
 * pierce the Gerstner surface. A fleet of small ships in choppy water, or one large hull
 * bobbing through a wave train, can each individually stay under their per-ship caps
 * ([CrepusculiteLatticeBlockEntity.MAX_SPLASH_EMITS] × [CrepusculiteLatticeBlockEntity.SPLASH_MAX])
 * while collectively dumping thousands of particles into the client per tick. This bucket
 * caps the total across *every* ship in the server, and tapers the cap progressively rather
 * than cliff-cutting it: at 50 % fill each requesting splash gets ~50 % of its droplets, at
 * 90 % fill ~10 %, etc., so a saturated ocean still emits *something* on every column
 * crossing instead of going visually dead.
 *
 * Wallclock-based refill (not server-tick based) so the bucket drains correctly regardless
 * of which thread or callback invokes [allocate]; no lifecycle hook needed.
 */
object SplashBudget {

    /** Particles "in flight" considered the saturation point — at this fill, [allocate]
     *  returns zero for any request. Sized for a few large ships in heavy weather without
     *  client-side particle saturation. */
    private const val CAPACITY = 256.0

    /** Drain rate, particles per millisecond. 256 / sec ≈ 12.8 / tick at 20 TPS — i.e., the
     *  bucket fully refills in one second of no splashes. Tuned so a brief heavy splash
     *  saturates instantly but recovers within a couple of seconds. */
    private const val REFILL_PER_MS = 256.0 / 1000.0

    private val lock = Any()
    private var used = 0.0
    private var lastMs = Long.MIN_VALUE

    /**
     * Request [requested] splash droplets for a single column crossing. Returns the actual
     * granted count, clamped to `[0, requested]`. The granted droplets are immediately
     * deducted from the bucket.
     *
     * The grant fraction is `(1 − used / CAPACITY)` — linear taper, not a hard cliff — so
     * adjacent splashes within the same overload tick still each contribute a few droplets
     * rather than the first emitter winning everything and the rest going silent.
     */
    fun allocate(requested: Int): Int {
        if (requested <= 0) return 0
        synchronized(lock) {
            val now = System.nanoTime() / 1_000_000L
            if (lastMs != Long.MIN_VALUE) {
                used -= (now - lastMs) * REFILL_PER_MS
                if (used < 0.0) used = 0.0
            }
            lastMs = now
            val fraction = (1.0 - used / CAPACITY).coerceIn(0.0, 1.0)
            val granted = (requested * fraction).toInt()
            if (granted > 0) used += granted
            return granted
        }
    }
}
