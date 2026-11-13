package org.shipwrights.enderkinesis.vine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

// A grip is a place along a vine in node units: the integer part is a node, the fraction is the way to the next node.
// [nodes] holds x, y, z for each of [count] nodes, as ServerChain.copyNodePositions writes them.

private const val MIN_LINK_LENGTH = 1.0e-6

/** The link that holds [grip]: the last link holds the last node. */
internal fun gripLink(grip: Double, count: Int): Int = floor(grip).toInt().coerceIn(0, maxOf(count - 2, 0))

/** One coordinate ([axis] 0, 1 or 2) of the point at [grip]. */
internal fun gripCoordinate(nodes: DoubleArray, count: Int, grip: Double, axis: Int): Double {
    require(count >= 1) { "a vine has at least one node" }
    val link = gripLink(grip, count)
    val a = nodes[link * 3 + axis]
    if (count == 1) return a
    val t = (grip - link).coerceIn(0.0, 1.0)
    return a + (nodes[(link + 1) * 3 + axis] - a) * t
}

/**
 * The node direction (+1 or -1) that goes most along the wish vector ([wishX], [wishY], [wishZ]) from [grip].
 * The caller keeps it for the full climb, so peaks and sharp bends do not stop or turn the climb.
 */
internal fun climbDirection(nodes: DoubleArray, count: Int, grip: Double, wishX: Double, wishY: Double, wishZ: Double): Int {
    val dot = gripTangent(nodes, count, grip, 0) * wishX + gripTangent(nodes, count, grip, 1) * wishY +
        gripTangent(nodes, count, grip, 2) * wishZ
    return if (dot >= 0.0) 1 else -1
}

/** One axis of the vine way at [grip] in the + node direction, over one node to each side, so a bend does not flip it. */
internal fun gripTangent(nodes: DoubleArray, count: Int, grip: Double, axis: Int): Double {
    val last = (count - 1).toDouble()
    return gripCoordinate(nodes, count, (grip + 1.0).coerceAtMost(last), axis) -
        gripCoordinate(nodes, count, (grip - 1.0).coerceAtLeast(0.0), axis)
}

/** The wish moves the grip only when the vine goes at least this much along it (cosine); else it pumps the swing. */
internal const val MOVE_MIN_COS = 0.35

/** A wish split into a grip move ([dir], [share] of the climb speed) and the [rest] that the vine does not take. */
internal class WishSplit {
    var dir = 0
    var share = 0.0
    var restX = 0.0
    var restY = 0.0
    var restZ = 0.0
}

/**
 * Splits the wish ([wx], [wy], [wz]) at [grip] into [out]. [keptDir] (0: none) is the direction of the move in
 * progress: the grip does not turn back on it at a hairpin. With no move, [WishSplit.rest] is the full wish.
 */
@Suppress("LongParameterList") // Plain doubles keep the hold tick free of allocation.
internal fun splitWish(
    nodes: DoubleArray, count: Int, grip: Double, keptDir: Int, wx: Double, wy: Double, wz: Double, out: WishSplit,
): WishSplit {
    out.dir = 0
    out.share = 0.0
    out.restX = wx; out.restY = wy; out.restZ = wz
    if (count < 2) return out
    val tx = gripTangent(nodes, count, grip, 0)
    val ty = gripTangent(nodes, count, grip, 1)
    val tz = gripTangent(nodes, count, grip, 2)
    val tangentSq = tx * tx + ty * ty + tz * tz
    val wishLength = sqrt(wx * wx + wy * wy + wz * wz)
    if (tangentSq < MIN_LINK_LENGTH * MIN_LINK_LENGTH || wishLength < MIN_LINK_LENGTH) return out
    val dot = tx * wx + ty * wy + tz * wz
    val cos = dot / (sqrt(tangentSq) * wishLength)
    val dir = if (keptDir != 0) keptDir else if (cos >= 0.0) 1 else -1
    if (cos * dir < MOVE_MIN_COS) return out
    out.dir = dir
    out.share = cos * dir
    val k = dot / tangentSq
    out.restX = wx - k * tx; out.restY = wy - k * ty; out.restZ = wz - k * tz
    return out
}

/** At or over this side-hang weight a vine is steep: jump and sneak climb. Under it they mantle and let go. */
internal const val STEEP_SIDE_WEIGHT = 0.5

/** True when the held link at [grip] is steep (the side hang band); a one-node vine counts as steep. */
internal fun isSteepAt(nodes: DoubleArray, count: Int, grip: Double): Boolean {
    if (count < 2) return true
    val k = gripLink(grip, count) * 3
    return sideHangWeight(nodes[k + 3] - nodes[k], nodes[k + 4] - nodes[k + 1], nodes[k + 5] - nodes[k + 2]) >= STEEP_SIDE_WEIGHT
}

/** Gap between the vine top and the feet of a mantled player, so the stand does not start inside the vine. */
private const val MANTLE_GAP = 0.01

/** Feet height of a player that mantles onto the vine at grip height [gripY]. */
internal fun mantleFeetY(gripY: Double): Double = gripY + VINE_HALF_WIDTH + MANTLE_GAP

/**
 * True when a body of [halfWidth] and [height] can stand on top of the vine at ([gx], [gy], [gz]): [isFree] gets its
 * box (minX, minY, minZ, maxX, maxY, maxZ) and checks the world and ships.
 */
@Suppress("LongParameterList") // Plain doubles keep the hold tick free of allocation.
internal inline fun hasMantleRoom(
    gx: Double, gy: Double, gz: Double, halfWidth: Double, height: Double,
    isFree: (Double, Double, Double, Double, Double, Double) -> Boolean,
): Boolean {
    val feet = mantleFeetY(gy)
    return isFree(gx - halfWidth, feet, gz - halfWidth, gx + halfWidth, feet + height, gz + halfWidth)
}

/** [grip] moved [distance] blocks along the vine in node direction [dir]; it stops at the end nodes. */
internal fun climbAlong(nodes: DoubleArray, count: Int, grip: Double, dir: Int, distance: Double): Double {
    require(count >= 1) { "a vine has at least one node" }
    require(dir == 1 || dir == -1) { "dir must be 1 or -1, not $dir" }
    require(distance >= 0.0) { "distance must not be negative" }
    var at = grip.coerceIn(0.0, (count - 1).toDouble())
    var left = distance
    // Each pass ends on the next node or uses all the distance, so count passes are enough.
    var passes = 0
    while (left > 0.0 && passes++ < count) {
        val link = if (dir > 0) floor(at).toInt() else ceil(at).toInt() - 1
        if (link < 0 || link >= count - 1) break
        val length = linkLength(nodes, link)
        val nextNode = if (dir > 0) link + 1.0 else link.toDouble()
        if (length < MIN_LINK_LENGTH) { at = nextNode; continue }
        val room = (if (dir > 0) link + 1.0 - at else at - link) * length
        if (left < room) return at + dir * left / length
        left -= room
        at = nextNode
    }
    return at
}

private fun linkLength(nodes: DoubleArray, link: Int): Double {
    val k = link * 3
    val dx = nodes[k + 3] - nodes[k]
    val dy = nodes[k + 4] - nodes[k + 1]
    val dz = nodes[k + 5] - nodes[k + 2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** The vine end (0 start, 1 end) that a climb in node direction [dir] stands on at [grip]; -1 when on neither. */
internal fun climbEndReached(grip: Double, count: Int, dir: Int): Int = when {
    dir < 0 && grip <= 0.0 -> 0
    dir > 0 && grip >= count - 1.0 -> 1
    else -> -1
}

/** True when a grip move from [from] to [to] reaches or passes [node]; a move that starts on it does not count. */
internal fun crossesNode(from: Double, to: Double, node: Int): Boolean {
    val n = node.toDouble()
    return (from < n && to >= n) || (from > n && to <= n)
}

/** The cosine between the way ([dx], [dy], [dz]) and the wish ([wx], [wy], [wz]); -2 for a zero way, so any way wins. */
@Suppress("LongParameterList") // Plain doubles keep the climb tick free of allocation.
internal fun alongWish(dx: Double, dy: Double, dz: Double, wx: Double, wy: Double, wz: Double): Double {
    val length = sqrt(dx * dx + dy * dy + dz * dz) * sqrt(wx * wx + wy * wy + wz * wz)
    if (length < MIN_LINK_LENGTH) return -2.0
    return (dx * wx + dy * wy + dz * wz) / length
}
