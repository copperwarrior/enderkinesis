package org.shipwrights.enderkinesis.physics

private const val DEFAULT_STEP_SECONDS = 1.0 / 60.0

/** VS2's KDoc gives the listener delta in ms; seconds are also accepted, since no physics step lasts a full second. */
internal fun physicsStepSeconds(delta: Double): Double = when {
    delta <= 0.0 -> DEFAULT_STEP_SECONDS
    delta > 1.0 -> delta / 1000.0
    else -> delta
}
