package org.shipwrights.enderkinesis.entity

import kotlin.math.tan

const val NOT_WATERLINE = Int.MIN_VALUE

// One more wet side is worth 16 blocks² of extra distance, so a frog walks a little for a better shore.
private const val WET_SIDE_WEIGHT = 16

/** Score of a land cell for a frog that lazes at the waterline; [NOT_WATERLINE] when no side touches water. */
fun waterlineScore(wetSides: Int, distanceSq: Int): Int =
    if (wetSides <= 0) NOT_WATERLINE else wetSides * WET_SIDE_WEIGHT - distanceSq

const val TONGUE_MAX_PITCH_DEGREES = 60.0
private const val TONGUE_LOWEST_RISE = -0.5
// Prey right overhead still counts as this far out, so a swarm sitting on the frog's head can be licked.
private const val TONGUE_MIN_HORIZONTAL = 0.5
private val TONGUE_MAX_SLOPE = tan(Math.toRadians(TONGUE_MAX_PITCH_DEGREES))

/** True when a tongue of [reach] blocks from the mouth hits a point [horizontal] out and [rise] up, at most 60° up. */
fun tongueCanReach(horizontal: Double, rise: Double, reach: Double): Boolean =
    rise >= TONGUE_LOWEST_RISE && horizontal * horizontal + rise * rise <= reach * reach &&
        rise <= maxOf(horizontal, TONGUE_MIN_HORIZONTAL) * TONGUE_MAX_SLOPE

// Vanilla LivingEntity.travel in air: move, then vy = (vy - 0.08) · 0.98 and horizontal · 0.91.
// From water the first tick runs the water branch instead: vy · 0.8 - 0.08/16 and horizontal · 0.9.
private const val GRAVITY = 0.08
private const val AIR_DRAG_Y = 0.98
private const val AIR_KEEP_H = 0.91
private const val WATER_KEEP_Y = 0.8
private const val WATER_KEEP_H = 0.9
private const val WATER_SINK = GRAVITY / 16
const val LEAP_MAX_TICKS = 40
const val LEAP_MAX_SPEED = 1.0
private const val LEAP_CLEARANCE = 0.25
private val LEAP_LIFTS = doubleArrayOf(0.42, 0.55, 0.7, 0.85)

data class LeapPlan(val speed: Double, val lift: Double, val ticks: Int)

/**
 * Offsets after each tick of a leap launched at [speed] across and [lift] up: index 2t holds the distance
 * across and 2t+1 the height after tick t+1. The caller must clear onGround at launch, or ground friction cuts tick 1.
 */
fun leapPath(speed: Double, lift: Double, fromWater: Boolean, ticks: Int): DoubleArray {
    require(ticks in 1..LEAP_MAX_TICKS) { "leap of $ticks ticks" }
    val path = DoubleArray(ticks * 2)
    var across = 0.0
    var height = 0.0
    var vh = speed
    var vy = lift
    for (t in 0 until ticks) {
        across += vh
        height += vy
        path[t * 2] = across
        path[t * 2 + 1] = height
        if (t == 0 && fromWater) {
            vh *= WATER_KEEP_H
            vy = vy * WATER_KEEP_Y - WATER_SINK
        } else {
            vh *= AIR_KEEP_H
            vy = (vy - GRAVITY) * AIR_DRAG_Y
        }
    }
    return path
}

/** The lowest leap that comes down through [rise] after [distance] across, within [LEAP_MAX_SPEED]; null if none. */
fun planLeap(distance: Double, rise: Double, fromWater: Boolean): LeapPlan? {
    require(distance > 0.0) { "leap distance $distance" }
    for (lift in LEAP_LIFTS) {
        // Distance across is linear in the launch speed, so plan with a unit speed and scale.
        val unit = leapPath(1.0, lift, fromWater, LEAP_MAX_TICKS)
        var peaked = false
        for (t in 0 until LEAP_MAX_TICKS) {
            val height = unit[t * 2 + 1]
            if (!peaked && t > 0 && height < unit[t * 2 - 1]) {
                peaked = true
                // The apex must clear the landing, or the frog meets the ledge edge.
                if (unit[t * 2 - 1] < rise + LEAP_CLEARANCE) break
            }
            if (!peaked || height > rise) continue
            val speed = distance / unit[t * 2]
            if (speed <= LEAP_MAX_SPEED) return LeapPlan(speed, lift, t + 1)
            break
        }
    }
    return null
}

