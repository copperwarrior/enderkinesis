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

private const val SPARSE_ONLY_COUNT = 3
private const val MID_COUNT = 12
private const val MID_VOLUME = 0.6f
private const val DENSE_END_SPARSE = 0.15f
private const val REST_VOLUME_SCALE = 0.4f
private const val WANDER_VOLUME_SCALE = 0.7f

/** Loop volume scale for a swarm mode. */
fun swarmModeScale(mode: SwarmFlyEntity.Mode): Float = when (mode) {
    SwarmFlyEntity.Mode.REST -> REST_VOLUME_SCALE
    SwarmFlyEntity.Mode.WANDER -> WANDER_VOLUME_SCALE
    SwarmFlyEntity.Mode.PESTER -> 1f
}

/** Sparse loop volume: 1 for 1-3 flies, 0.6 at 12, 0.15 at 24, times [scale]. */
fun swarmSparseVolume(flyCount: Int, scale: Float): Float {
    val count = flyCount.coerceIn(1, SWARM_MAX_FLIES)
    val base = when {
        count <= SPARSE_ONLY_COUNT -> 1f
        count <= MID_COUNT -> 1f + (MID_VOLUME - 1f) * (count - SPARSE_ONLY_COUNT) / (MID_COUNT - SPARSE_ONLY_COUNT)
        else -> MID_VOLUME + (DENSE_END_SPARSE - MID_VOLUME) * (count - MID_COUNT) / (SWARM_MAX_FLIES - MID_COUNT)
    }
    return base * scale
}

/** Dense loop volume: 0 for 1-3 flies, 0.6 at 12, 1 at 24, times [scale]. */
fun swarmDenseVolume(flyCount: Int, scale: Float): Float {
    val count = flyCount.coerceIn(1, SWARM_MAX_FLIES)
    val base = when {
        count <= SPARSE_ONLY_COUNT -> 0f
        count <= MID_COUNT -> MID_VOLUME * (count - SPARSE_ONLY_COUNT) / (MID_COUNT - SPARSE_ONLY_COUNT)
        else -> MID_VOLUME + (1f - MID_VOLUME) * (count - MID_COUNT) / (SWARM_MAX_FLIES - MID_COUNT)
    }
    return base * scale
}
