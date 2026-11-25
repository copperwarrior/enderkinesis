package org.shipwrights.enderkinesis.item

import kotlin.math.ceil
import kotlin.math.floor

/** Health points (1 = half a heart) that regrow each tick under the crown: one heart in 10 s. */
const val WOOD_REGEN_PER_TICK = 0.01f

/** Damage resistance when every heart is wood. */
const val WOOD_MAX_RESISTANCE = 0.6f

/** Movement speed loss when every heart is wood. */
const val WOOD_MAX_SLOWNESS = 0.35f

/** Wood part of a player's current health. Health is in points; one point is half a heart. */
class WoodHearts {

    var wood: Float = 0f
        private set

    // -1 means no health seen yet (new body or just loaded), so the first look gains no wood.
    private var lastHealth = -1f
    private var regrowCredit = 0f

    /** What the client and the speed modifier last got; -1 forces a resend. */
    var sentHalfHearts: Int = -1
    var sentMaxHealth: Float = -1f
    var sentWorn: Boolean = false

    /** Cached Curios/Trinkets wear state; the gatherer allocates, so it is not read every tick. */
    var accessoryWorn: Boolean = false
    var accessoryCheckDue: Boolean = true

    val halfHearts: Int get() = halfHeartsOf(wood)

    /** Health gained since the last look becomes wood while the crown is worn. */
    fun observe(health: Float, crownWorn: Boolean) {
        if (crownWorn && lastHealth >= 0f && health > lastHealth) wood += health - lastHealth
        lastHealth = health
        clampTo(health)
    }

    /** Takes [loss] from [health] half from red, half from wood; see [woodShare]. */
    fun takeHit(health: Float, loss: Float) {
        require(loss >= 0f) { "loss must not be negative: $loss" }
        wood -= woodShare(loss, maxOf(health - wood, 0f), wood)
        lastHealth = maxOf(health - loss, 0f)
        clampTo(lastHealth)
    }

    /** The health to regrow now. Whole points only, so health syncs once for each half heart. */
    fun regrow(health: Float, maxHealth: Float, crownWorn: Boolean): Float {
        if (!crownWorn || health <= 0f || health >= maxHealth) {
            regrowCredit = 0f
            return 0f
        }
        regrowCredit += WOOD_REGEN_PER_TICK
        if (regrowCredit < 1f) return 0f
        regrowCredit -= 1f
        return minOf(1f, maxHealth - health)
    }

    fun load(value: Float) {
        wood = if (value.isFinite()) maxOf(value, 0f) else 0f
        lastHealth = -1f
        regrowCredit = 0f
        sentHalfHearts = -1
    }

    fun copyFrom(other: WoodHearts) = load(other.wood)

    private fun clampTo(health: Float) {
        if (wood > health) wood = maxOf(health, 0f)
        if (wood < 0f) wood = 0f
    }
}

/**
 * The wood part of a hit of [loss] points. Red takes half, rounded down to whole points, so an odd half heart
 * goes to wood. When one kind runs out, the other takes the rest.
 */
fun woodShare(loss: Float, red: Float, wood: Float): Float {
    val redShare = minOf(floor(loss / 2f), red)
    return (loss - redShare).coerceIn(0f, maxOf(wood, 0f))
}

// The HUD rounds health up to half hearts, so wood does the same.
fun halfHeartsOf(points: Float): Int = if (points <= 0f) 0 else ceil(points).toInt()

fun woodFraction(halfHearts: Int, maxHealth: Float): Float =
    if (maxHealth <= 0f) 0f else (halfHearts / maxHealth).coerceIn(0f, 1f)

fun woodResistance(fraction: Float): Float = WOOD_MAX_RESISTANCE * fraction.coerceIn(0f, 1f)

fun woodSlowness(fraction: Float): Float = WOOD_MAX_SLOWNESS * fraction.coerceIn(0f, 1f)

/** The health a hit of [loss] takes. Wood resists it with or without the crown; void and /kill get no resistance. */
fun woodDamageTaken(loss: Float, woodHalfHearts: Int, maxHealth: Float, bypassesInvulnerability: Boolean): Float =
    if (bypassesInvulnerability) loss else loss * (1f - woodResistance(woodFraction(woodHalfHearts, maxHealth)))
