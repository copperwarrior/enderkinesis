package org.shipwrights.enderkinesis.client

/** The [max] nearest sound sources of a tick. A playing source counts as [playingBiasSqr] times its
 *  squared distance, so two sources at near the same distance do not swap each tick. Client thread only. */
internal class NearestLoopSelection<T : Any>(private val max: Int, private val playingBiasSqr: Double) {
    private val selected = HashSet<T>()
    private val candidates = ArrayList<T>()
    private var candidateDistSqr = DoubleArray(16)

    init {
        require(max > 0) { "max must be positive" }
    }

    operator fun contains(source: T): Boolean = source in selected

    fun selected(): Set<T> = selected

    fun beginTick() {
        candidates.clear()
    }

    fun offer(source: T, distSqr: Double, playing: Boolean) {
        if (candidates.size == candidateDistSqr.size) candidateDistSqr = candidateDistSqr.copyOf(candidates.size * 2)
        candidateDistSqr[candidates.size] = if (playing) distSqr * playingBiasSqr else distSqr
        candidates.add(source)
    }

    /** A partial selection sort: at most [max] passes over the candidates. */
    fun select() {
        selected.clear()
        val count = candidates.size
        for (pick in 0 until minOf(max, count)) {
            var best = pick
            for (i in pick + 1 until count) if (candidateDistSqr[i] < candidateDistSqr[best]) best = i
            if (best != pick) {
                candidates[pick] = candidates[best].also { candidates[best] = candidates[pick] }
                candidateDistSqr[pick] = candidateDistSqr[best].also { candidateDistSqr[best] = candidateDistSqr[pick] }
            }
            selected.add(candidates[pick])
        }
    }

    fun clear() {
        selected.clear()
        candidates.clear()
    }
}
