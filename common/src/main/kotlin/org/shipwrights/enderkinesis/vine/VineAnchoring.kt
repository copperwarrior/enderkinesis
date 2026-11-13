package org.shipwrights.enderkinesis.vine

/**
 * Starts the fade of each vine that does not reach a block or ship anchor through its joints.
 * Joints count both ways: a group of vines joined only to each other has no anchor, so all of them fade.
 * A joint to a vine that is not loaded counts as an anchor while [waiting] says the target can come back.
 * Returns true when a fade started.
 */
internal fun WoggakVineData.startFadeOfUnanchored(gameTime: Long, waiting: (VineJoint) -> Boolean): Boolean {
    val n = vines.size
    if (n == 0) return false
    val indexOf = HashMap<Long, Int>(n * 2)
    for (i in 0 until n) indexOf[vines[i].id] = i
    val anchored = BooleanArray(n)

    // Each vine has at most two joined ends, so at most 2n edges; each edge is stored in both directions.
    val edgeFrom = IntArray(n * 4)
    val edgeTo = IntArray(n * 4)
    var edges = 0
    fun link(anchor: WoggakVineAnchor, from: Int) {
        if (!anchor.isJoined) return
        val joint = anchor.point?.joint ?: return
        val to = indexOf[joint.vine]
        if (to == null) {
            if (waiting(joint)) anchored[from] = true
            return
        }
        edgeFrom[edges] = from; edgeTo[edges] = to; edges++
        edgeFrom[edges] = to; edgeTo[edges] = from; edges++
    }
    for (i in 0 until n) {
        link(vines[i].start, i)
        link(vines[i].end, i)
    }

    // Edges grouped by their first vine, for the walk.
    val firstEdge = IntArray(n + 1)
    for (e in 0 until edges) firstEdge[edgeFrom[e] + 1]++
    for (i in 0 until n) firstEdge[i + 1] += firstEdge[i]
    val sorted = IntArray(edges)
    val fill = firstEdge.copyOf(n)
    for (e in 0 until edges) sorted[fill[edgeFrom[e]]++] = edgeTo[e]

    val stack = IntArray(n)
    var top = 0
    for (i in 0 until n) {
        if (vines[i].hasFixedAnchor || throws.containsKey(vines[i].id)) anchored[i] = true
        if (anchored[i]) stack[top++] = i
    }
    // Each vine goes on the stack once at most, so the walk ends after n pops.
    while (top > 0) {
        val v = stack[--top]
        for (e in firstEdge[v] until firstEdge[v + 1]) {
            val w = sorted[e]
            if (anchored[w]) continue
            anchored[w] = true
            stack[top++] = w
        }
    }

    var started = false
    for (i in 0 until n) {
        val vine = vines[i]
        if (anchored[i] || vine.fadeStart >= 0) continue
        vine.fadeStart = gameTime
        touch(vine)
        started = true
    }
    return started
}

private val WoggakVine.hasFixedAnchor: Boolean
    get() = start.isFixed || end.isFixed

private val WoggakVineAnchor.isFixed: Boolean
    get() = !released && point != null && point.joint == null
