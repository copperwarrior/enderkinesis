package org.shipwrights.enderkinesis.vine

import org.joml.Vector3d
import kotlin.math.sqrt

/** The point a fragment's path is scaled about: its anchored end, or its centre when it has no anchor. */
enum class FragmentPivot { FIRST, LAST, CENTROID }

/**
 * First and last node of each unbroken run of links in a chain of [nodes] nodes. A run never bridges a broken
 * link, and a run has at least one link.
 */
fun unbrokenRuns(nodes: Int, broken: Set<Int>): List<IntRange> {
    require(nodes >= 0) { "Node count $nodes" }
    val runs = ArrayList<IntRange>()
    var first = 0
    for (link in 0 until nodes - 1) {
        if (link !in broken) continue
        if (link > first) runs.add(first..link)
        first = link + 1
    }
    if (nodes - 1 > first) runs.add(first until nodes)
    return runs
}

/**
 * A path over nodes [run] of [positions] (x, y, z triples) with a total length of exactly [restLength]: the
 * deformed shape, scaled about [pivot]. A stretched run comes back shorter and a slack run longer, so a new chain
 * made from it starts at rest and never has more links than the run held. Null when the run has no length.
 */
fun restPath(positions: DoubleArray, run: IntRange, restLength: Double, pivot: FragmentPivot): List<Vector3d>? {
    require(run.first >= 0 && run.last * 3 + 2 < positions.size && run.last > run.first) { "Run $run" }
    require(restLength.isFinite() && restLength > 0) { "Rest length $restLength" }
    var deformed = 0.0
    for (i in run.first until run.last) {
        val k = i * 3
        val dx = positions[k + 3] - positions[k]; val dy = positions[k + 4] - positions[k + 1]; val dz = positions[k + 5] - positions[k + 2]
        deformed += sqrt(dx * dx + dy * dy + dz * dz)
    }
    if (!deformed.isFinite() || deformed <= 0.0) return null
    val scale = restLength / deformed
    val centre = Vector3d()
    when (pivot) {
        FragmentPivot.FIRST -> centre.set(positions[run.first * 3], positions[run.first * 3 + 1], positions[run.first * 3 + 2])
        FragmentPivot.LAST -> centre.set(positions[run.last * 3], positions[run.last * 3 + 1], positions[run.last * 3 + 2])
        FragmentPivot.CENTROID -> {
            for (i in run) centre.add(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])
            centre.div((run.last - run.first + 1).toDouble())
        }
    }
    return run.map { i ->
        Vector3d(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]).sub(centre).mul(scale).add(centre)
    }
}
