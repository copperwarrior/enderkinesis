package org.shipwrights.enderkinesis.vine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Body place of a vine hold, relative to the grip point. The orbit angle is atan2(z, x) of the horizontal body offset.

/** Rope slope from level, in degrees: at or under this the body hangs under the grip (pose E). */
internal const val HANG_SLOPE_UNDER = 35.0
/** Rope slope from level, in degrees: at or over this the body hangs beside the rope (pose B). */
internal const val HANG_SLOPE_SIDE = 60.0
/** Half width of the drawn torso and head: 4 model px at the player renderer's 0.9375 scale. */
internal const val DRAWN_HALF_WIDTH = 4.0 * 0.9375 / 16
/** Height of the drawn head top over the feet: 32 model px at 0.9375 scale; the hitbox top is lower, at 1.8. */
internal const val DRAWN_HEIGHT = 32.0 * 0.9375 / 16
/** Corner radius of the drawn head: a wall slide or the orbit ease can put the rope at any angle to the body. */
internal const val DRAWN_RADIUS = DRAWN_HALF_WIDTH * 1.4142135623730951
/** Gap between the drawn model and the rope surface, in blocks. */
internal const val DRAWN_GAP = 0.005
/** The side hang puts the rope this far forward of the left shoulder, so the rope and the hands on it are in view. */
internal const val HANG_ROPE_FORWARD_DEGREES = 40.0
/**
 * Distance from the body centre to the rope axis: the rope just outside the drawn model, the same at every yaw, and
 * beside a pitched head at the hang angle, as pitch turns the head about the side axis. The hitbox can overlap the
 * held rope; the hang sweep has no vine shapes.
 */
internal val HANG_RADIUS = max(
    DRAWN_RADIUS + VINE_HALF_WIDTH + DRAWN_GAP,
    (DRAWN_HALF_WIDTH + VINE_HALF_WIDTH + DRAWN_GAP) / cos(Math.toRadians(HANG_ROPE_FORWARD_DEGREES)),
)
/** Grip height over the drawn head top: the raised hands of the player model end about 0.1 over it. */
internal const val HAND_REACH = 0.1
/** The body never drops more than this under the grip, so the hands stay near the rope. */
internal const val MAX_HANG_DROP = 1.0
/** A side that needs more drop than this to clear the rope counts as blocked. */
internal const val MAX_SIDE_DROP = 0.5
/** Over this last part of a vine end that hangs free, in node units, the body moves to the centre under the end. */
internal const val END_BLEND = 0.5
internal const val ORBIT_SEARCH_STEPS = 8
internal const val ORBIT_SEARCH_STEP = PI / ORBIT_SEARCH_STEPS
private const val ORBIT_EASE = 0.35
/** About 17 degrees per tick, so a fast turn of the look does not throw the body round the rope. */
internal const val MAX_ORBIT_STEP = 0.3
internal const val HANG_AMOUNT_EASE = 0.25
private const val DEGENERATE = 1e-9
private const val DROP_MARGIN = 1e-6
/** On a leaning rope the side radius can grow by this much, so the body moves out round the rope, not off it. */
internal const val MAX_SIDE_EXTRA = 0.4
private const val SIDE_RADIUS_STEPS = 20
private const val SIDE_RADIUS_BISECTIONS = 8

/** Upper bound of the distance from the grip to the top centre of the hitbox, for the server hold-gap check. */
internal val MAX_HANG_OFFSET = HANG_RADIUS + MAX_SIDE_EXTRA + MAX_HANG_DROP + DRAWN_HEIGHT

internal fun smoothStep(edge0: Double, edge1: Double, x: Double): Double {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

internal fun wrapAngle(a: Double): Double = a - 2.0 * PI * floor((a + PI) / (2.0 * PI))

/** Share of the side hang (B) for a rope tangent; the rest is the under-hang (E). */
internal fun sideHangWeight(tx: Double, ty: Double, tz: Double): Double {
    val horizontal = sqrt(tx * tx + tz * tz)
    if (horizontal < DEGENERATE && abs(ty) < DEGENERATE) return 1.0
    val slope = Math.toDegrees(atan2(abs(ty), horizontal))
    return smoothStep(HANG_SLOPE_UNDER, HANG_SLOPE_SIDE, slope)
}

/** 0 at a vine end that the rope rises from, so the body hangs centred under it; 1 from [END_BLEND] away. */
internal fun endHangWeight(gripToEnd: Double, ropeRisesFromEnd: Boolean): Double =
    if (ropeRisesFromEnd) smoothStep(0.0, END_BLEND, gripToEnd) else 1.0

/**
 * Orbit angle for a look yaw in degrees: the body goes back and to the right of the rope, so the rope is
 * [HANG_ROPE_FORWARD_DEGREES] forward of the left shoulder.
 */
internal fun orbitTargetAngle(yawDegrees: Double): Double =
    wrapAngle(Math.toRadians(yawDegrees + HANG_ROPE_FORWARD_DEGREES) + PI)

/** One tick of the shown orbit angle toward [target], the short way round. */
internal fun stepOrbitAngle(current: Double, target: Double): Double {
    val gap = wrapAngle(target - current)
    if (abs(gap) < DEGENERATE) return target
    return wrapAngle(current + (gap * ORBIT_EASE).coerceIn(-MAX_ORBIT_STEP, MAX_ORBIT_STEP))
}

/**
 * Drop of the drawn head top under the grip for a body centre at ([cx], [cz]) from the grip, so the drawn model stays
 * clear of the rope; at least [HAND_REACH]. The model counts as a round column of [DRAWN_HEIGHT], and the rope as a
 * line that goes downhill along the unit ([downX], [downZ]) at [tanSlope] for [downReach] blocks horizontally. The
 * rope clears the model when it passes over the head, or, on a steep rope beside the body, under the feet.
 * Infinite when a vertical rope goes through the model.
 */
@Suppress("LongParameterList") // Plain doubles keep the hang tick free of allocation.
internal fun hangDrop(cx: Double, cz: Double, downX: Double, downZ: Double, tanSlope: Double, downReach: Double): Double {
    // Less a hair, so a body placed exactly on the radius counts as clear.
    val radius = HANG_RADIUS - DEGENERATE
    val vertical = !tanSlope.isFinite() || (abs(downX) < DEGENERATE && abs(downZ) < DEGENERATE)
    if (vertical) return if (cx * cx + cz * cz < radius * radius) Double.POSITIVE_INFINITY else HAND_REACH
    val along = cx * downX + cz * downZ
    val sideSq = cx * cx + cz * cz - along * along
    if (sideSq >= radius * radius) return HAND_REACH
    // The rope axis is over the model's round footprint (grown by the rope) for downhill distances low..high.
    val half = sqrt(radius * radius - sideSq)
    val low = along - half
    val high = min(along + half, downReach)
    if (low >= high) return HAND_REACH
    val clearance = VINE_HALF_WIDTH + DRAWN_GAP
    // The deepest drop that keeps the rope under the feet over that span.
    val underFeet = low * tanSlope - DRAWN_HEIGHT - clearance
    if (HAND_REACH <= underFeet) return HAND_REACH
    return max(HAND_REACH, clearance + high * tanSlope + DROP_MARGIN)
}

/**
 * The smallest side radius at [angle], from [HANG_RADIUS] up to [MAX_SIDE_EXTRA] more, where the rope clears the drawn
 * model at the hands' reach: on the downhill side of a leaning rope the body moves out round the rope. NaN when none.
 */
@Suppress("LongParameterList") // Plain doubles keep the hang tick free of allocation.
internal fun sideRadius(angle: Double, downX: Double, downZ: Double, tanSlope: Double, downReach: Double): Double {
    val c = cos(angle)
    val s = sin(angle)
    var previous = Double.NaN
    var r = HANG_RADIUS
    for (i in 0..SIDE_RADIUS_STEPS) {
        if (hangDrop(c * r, s * r, downX, downZ, tanSlope, downReach) <= HAND_REACH) {
            if (previous.isNaN()) return r
            var low = previous
            var high = r
            repeat(SIDE_RADIUS_BISECTIONS) {
                val mid = (low + high) / 2
                if (hangDrop(c * mid, s * mid, downX, downZ, tanSlope, downReach) <= HAND_REACH) high = mid else low = mid
            }
            return high
        }
        previous = r
        r += MAX_SIDE_EXTRA / SIDE_RADIUS_STEPS
    }
    return Double.NaN
}

fun interface OrbitBlocker {
    fun isBlocked(angle: Double): Boolean
}

/**
 * The free orbit angle nearest to [desired], in steps of [ORBIT_SEARCH_STEP] to each side. On a tie it takes the side
 * nearer to [current], so the body does not flip across the rope. NaN when every angle is blocked.
 */
internal fun chooseOrbitAngle(desired: Double, current: Double, blocker: OrbitBlocker): Double {
    if (!blocker.isBlocked(desired)) return desired
    for (i in 1..ORBIT_SEARCH_STEPS) {
        val plus = wrapAngle(desired + i * ORBIT_SEARCH_STEP)
        val minus = wrapAngle(desired - i * ORBIT_SEARCH_STEP)
        val plusFirst = current.isNaN() || abs(wrapAngle(plus - current)) <= abs(wrapAngle(minus - current))
        val first = if (plusFirst) plus else minus
        val second = if (plusFirst) minus else plus
        if (!blocker.isBlocked(first)) return first
        if (!blocker.isBlocked(second)) return second
    }
    return Double.NaN
}
