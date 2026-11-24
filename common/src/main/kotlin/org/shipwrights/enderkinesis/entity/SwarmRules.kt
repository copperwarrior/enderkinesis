package org.shipwrights.enderkinesis.entity

import kotlin.math.sqrt

const val SWARM_MAX_FLIES = 24
const val SWARM_SPLIT_MIN = 12

private const val SWARM_BASE_RADIUS = 0.3f
private const val SWARM_RADIUS_PER_ROOT_FLY = 0.14f
private const val SWARM_REST_RADIUS = 0.3f
const val SWARM_DISPERSE_MIN = 2
const val SWARM_DISPERSE_MAX = 5

/** Flies that leave in a split; 0 means no split. */
fun swarmSplitSize(count: Int): Int = if (count < SWARM_SPLIT_MIN) 0 else count / 2

fun swarmCanMerge(count: Int, otherCount: Int): Boolean =
    count > 0 && otherCount > 0 && count + otherCount <= SWARM_MAX_FLIES

/** Exactly one of two different swarms absorbs the other: the larger, or the lower entity id on a tie. */
fun swarmAbsorbs(count: Int, id: Int, otherCount: Int, otherId: Int): Boolean =
    count > otherCount || (count == otherCount && id < otherId)

/**
 * Sizes of the small swarms a swat leaves. Each piece holds [SWARM_DISPERSE_MIN]..[SWARM_DISPERSE_MAX] flies,
 * except a swarm smaller than the minimum, which stays one piece. [nextSize] gives the wanted size of each piece.
 */
fun swarmDispersePieces(count: Int, nextSize: () -> Int): List<Int> {
    if (count <= 0) return emptyList()
    val pieces = ArrayList<Int>()
    var left = count
    // Each pass removes at least SWARM_DISPERSE_MIN flies, so the loop ends.
    while (left > SWARM_DISPERSE_MAX) {
        val size = nextSize().coerceIn(SWARM_DISPERSE_MIN, SWARM_DISPERSE_MAX).coerceAtMost(left - SWARM_DISPERSE_MIN)
        pieces.add(size)
        left -= size
    }
    pieces.add(left)
    return pieces
}

/** The cloud grows with the square root of the count, so its density stays near constant. */
fun swarmRadius(count: Int, resting: Boolean): Float =
    if (resting) SWARM_REST_RADIUS else SWARM_BASE_RADIUS + SWARM_RADIUS_PER_ROOT_FLY * sqrt(count.coerceAtLeast(0).toFloat())
