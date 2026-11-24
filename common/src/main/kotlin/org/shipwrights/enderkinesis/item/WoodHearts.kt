package org.shipwrights.enderkinesis.item

import kotlin.math.ceil

/** Health points (1 = half a heart) that turn back from wood each tick: one heart in 10 s. */
const val WOOD_REGEN_PER_TICK = 0.01f

/** Damage resistance when every heart is wood. */
const val WOOD_MAX_RESISTANCE = 0.6f

/** Movement speed loss when every heart is wood. */
const val WOOD_MAX_SLOWNESS = 0.5f

/** Wood part of a player's health under the Antlered Crown. Health is in points; one point is half a heart. */
class WoodHearts {

    var wood: Float = 0f
        private set

    /** The half-heart count the client and the speed modifier last got; -1 forces a resend. */
    var sentHalfHearts: Int = -1
    var sentMaxHealth: Float = -1f

    val halfHearts: Int get() = halfHeartsOf(wood)

    /** Marks [damage] of [health] as wood. Returns true when every visible heart is wood. */
    fun absorb(damage: Float, health: Float): Boolean {
        require(damage >= 0f) { "damage must not be negative: $damage" }
        wood = minOf(wood + damage, maxOf(health, 0f))
        return isPetrified(health)
    }

    fun isPetrified(health: Float): Boolean = health > 0f && halfHearts >= halfHeartsOf(health)

    fun regenerate() {
        if (wood > 0f) wood = maxOf(wood - WOOD_REGEN_PER_TICK, 0f)
    }

    /** Damage taken without the crown can leave less health than wood. */
    fun clampTo(health: Float) {
        if (wood > health) wood = maxOf(health, 0f)
    }

    fun load(value: Float) {
        wood = if (value.isFinite()) maxOf(value, 0f) else 0f
        sentHalfHearts = -1
    }

    fun copyFrom(other: WoodHearts) = load(other.wood)
}

// The HUD rounds health up to half hearts, so wood does the same.
fun halfHeartsOf(points: Float): Int = if (points <= 0f) 0 else ceil(points).toInt()

fun woodFraction(halfHearts: Int, maxHealth: Float): Float =
    if (maxHealth <= 0f) 0f else (halfHearts / maxHealth).coerceIn(0f, 1f)

fun woodResistance(fraction: Float): Float = WOOD_MAX_RESISTANCE * fraction.coerceIn(0f, 1f)

fun woodSlowness(fraction: Float): Float = WOOD_MAX_SLOWNESS * fraction.coerceIn(0f, 1f)
