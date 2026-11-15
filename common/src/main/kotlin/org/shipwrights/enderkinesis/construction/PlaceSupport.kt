package org.shipwrights.enderkinesis.construction

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos

/** A test on one world cell, by packed position. */
internal fun interface CellTest {
    fun test(packed: Long): Boolean
}

/** What [placeSupport] found for a plan cell. */
internal enum class PlaceHold { WORLD, LATER, FLOATING }

/** Plan cells [placeSupport] searches at most: a part bigger than this is taken to reach the world. */
const val SUPPORT_SEARCH_CAP = 256

private val SIDE_X = intArrayOf(0, 0, 1, -1, 0, 0)
private val SIDE_Y = intArrayOf(-1, 1, 0, 0, 0, 0)
private val SIDE_Z = intArrayOf(0, 0, 0, 0, 1, -1)

/**
 * Whether a block placed at [start] holds on something. [PlaceHold.WORLD]: a face neighbour is solid in the world
 * now. [PlaceHold.LATER]: the plan cells still to come that join [start] ([pending]) touch the world somewhere, so
 * it waits for the build to grow to it. [PlaceHold.FLOATING]: that part of the plan touches nothing, so no cell
 * can ever hold it and it goes in unsupported. [solid] is a solid world block. Bounded by [cap] plan cells.
 */
@Suppress("ReturnCount") // One return per outcome of the bounded search.
internal fun placeSupport(
    start: Long, solid: CellTest, pending: CellTest, queue: LongArrayFIFOQueue, seen: LongOpenHashSet,
    cap: Int = SUPPORT_SEARCH_CAP,
): PlaceHold {
    require(cap > 0) { "Cap must be positive: $cap" }
    for (d in SIDE_X.indices) {
        if (solid.test(BlockPos.offset(start, SIDE_X[d], SIDE_Y[d], SIDE_Z[d]))) return PlaceHold.WORLD
    }
    queue.clear()
    seen.clear()
    queue.enqueue(start)
    seen.add(start)
    while (!queue.isEmpty) {
        val cur = queue.dequeueLong()
        for (d in SIDE_X.indices) {
            val next = BlockPos.offset(cur, SIDE_X[d], SIDE_Y[d], SIDE_Z[d])
            if (seen.contains(next)) continue
            if (solid.test(next)) return PlaceHold.LATER
            if (!pending.test(next)) continue
            if (seen.size >= cap) return PlaceHold.LATER
            seen.add(next)
            queue.enqueue(next)
        }
    }
    return PlaceHold.FLOATING
}
