package org.shipwrights.enderkinesis.block

/** Timing of the ritual ignite tendrils, in seconds from ignite. The client animates it; the server holds the tree growth until it ends. */
object WohlonRitualTimeline {
    const val RISE = 1.6
    const val RISE_STAGGER = 0.08
    const val SPREAD_GAP = 0.3
    const val SPREAD = 2.4
    const val SPREAD_STAGGER = 0.4
    const val HOLD = 1.5
    const val FADE = 2.0
    // A tree build that never reports its roots still fades out.
    const val ROOTS_TIMEOUT = 8.0

    /** The candle tendrils have all reached the Heart, with a short pause after. */
    val riseEnd: Double = RISE + RISE_STAGGER * WohlonnogondoniaPortalRitual.PATTERN.size + SPREAD_GAP

    /** The root tendrils start when the root tips are known, but not before the rise ends. */
    fun spreadStart(rootsKnownSec: Double): Double = maxOf(rootsKnownSec, riseEnd)

    /** The last root tendril is grown, has held, and has faded. */
    fun fadeStart(spreadStart: Double): Double = spreadStart + SPREAD + SPREAD_STAGGER + HOLD

    fun animationEnd(spreadStart: Double): Double = fadeStart(spreadStart) + FADE
}
