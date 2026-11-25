package org.shipwrights.enderkinesis.client

import java.util.PriorityQueue
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

const val VEIN_SKIN_SIZE = 64

/** The highest grow order. A pixel with order `o` shows when `o <= growth * VEIN_MAX_ORDER`. */
const val VEIN_MAX_ORDER = 255

private const val ROOT_COUNT = 5
private const val TIP_COUNT = 48
private const val THICK_USAGE = 8
private const val NOISE = 2f
private const val NEIGHBOUR_REACH = 1.05f
private const val MIN_STEP = 0.25f

// Head side pixels above this model y (two rows under the top) touch the crown band.
private const val CROWN_BAND_Y = -6f

fun isVeinVisible(order: Int, growth: Float): Boolean =
    order in 1..VEIN_MAX_ORDER && order <= growth.coerceIn(0f, 1f) * VEIN_MAX_ORDER

/**
 * A grow order (0 = no vein) for each pixel of a 64x64 skin, row by row. Roots start at random crown-band pixels
 * of the head and run along shortest noisy paths over the rest-pose body surface to random tips, so branches
 * merge toward the crown. Paths that many tips share are one pixel wider. Only base-layer pixels are set.
 */
fun buildWoodVeinMask(seed: Long, slim: Boolean): ByteArray {
    val random = Random(seed)
    val nodes = skinNodes(slim)
    val count = nodes.size
    val noise = FloatArray(count) { random.nextFloat() * NOISE }
    val neighbours = neighbours(nodes)

    val sources = nodes.indices.filter { nodes[it].part == HEAD && nodes[it].side && nodes[it].y < CROWN_BAND_Y }
        .shuffled(random).take(ROOT_COUNT)
    val distance = FloatArray(count) { Float.MAX_VALUE }
    val parent = IntArray(count) { -1 }
    shortestPaths(nodes, neighbours, noise, sources, distance, parent)

    val usage = IntArray(count)
    repeat(TIP_COUNT) {
        var node = random.nextInt(count)
        // A parent chain visits each node once, so it ends in at most `count` steps.
        var steps = 0
        while (node >= 0 && distance[node] < Float.MAX_VALUE && steps <= count) {
            usage[node]++
            node = parent[node]
            steps++
        }
    }

    val veinDistance = FloatArray(count) { if (usage[it] > 0) distance[it] else -1f }
    for (node in 0 until count) {
        if (usage[node] < THICK_USAGE) continue
        for (other in neighbours[node]) {
            if (veinDistance[other] < 0f && nodes[other].part == nodes[node].part) veinDistance[other] = distance[node]
        }
    }

    val maxDistance = veinDistance.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val mask = ByteArray(VEIN_SKIN_SIZE * VEIN_SKIN_SIZE)
    for (node in 0 until count) {
        val d = veinDistance[node]
        if (d < 0f) continue
        val order = (1 + (d / maxDistance * (VEIN_MAX_ORDER - 1)).roundToInt()).coerceIn(1, VEIN_MAX_ORDER).toByte()
        val n = nodes[node]
        mask[n.v * VEIN_SKIN_SIZE + n.u] = order
    }
    return mask
}

private class SkinNode(val u: Int, val v: Int, val x: Float, val y: Float, val z: Float, val part: Int, val side: Boolean)

private const val HEAD = 0

private class Box(
    val u: Int, val v: Int,
    val x: Float, val y: Float, val z: Float, val w: Int, val h: Int, val d: Int,
    val px: Float, val py: Float, val pz: Float,
)

// PlayerModel rest pose: head, body, right arm, left arm, right leg, left leg.
private fun playerBoxes(slim: Boolean): List<Box> {
    val armWidth = if (slim) 3 else 4
    val armY = if (slim) 2.5f else 2f
    return listOf(
        Box(0, 0, -4f, -8f, -4f, 8, 8, 8, 0f, 0f, 0f),
        Box(16, 16, -4f, 0f, -2f, 8, 12, 4, 0f, 0f, 0f),
        Box(40, 16, if (slim) -2f else -3f, -2f, -2f, armWidth, 12, 4, -5f, armY, 0f),
        Box(32, 48, -1f, -2f, -2f, armWidth, 12, 4, 5f, armY, 0f),
        Box(0, 16, -2f, 0f, -2f, 4, 12, 4, -1.9f, 12f, 0f),
        Box(16, 48, -2f, 0f, -2f, 4, 12, 4, 1.9f, 12f, 0f),
    )
}

private fun skinNodes(slim: Boolean): List<SkinNode> {
    val nodes = ArrayList<SkinNode>()
    playerBoxes(slim).forEachIndexed { part, box -> addBoxNodes(box, part, nodes) }
    return nodes
}

// Corners and face UVs follow ModelPart.Cube: each face lists its corners for (u1,v0), (u0,v0), (u0,v1), (u1,v1).
private fun addBoxNodes(b: Box, part: Int, out: MutableList<SkinNode>) {
    val x0 = b.x + b.px
    val y0 = b.y + b.py
    val z0 = b.z + b.pz
    val x1 = x0 + b.w
    val y1 = y0 + b.h
    val z1 = z0 + b.d
    val c = arrayOf(
        floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
        floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1),
    )
    val u = b.u
    val v = b.v
    val (w, h, d) = Triple(b.w, b.h, b.d)
    face(out, part, false, arrayOf(c[5], c[4], c[0], c[1]), u + d, v, u + d + w, v + d)
    face(out, part, false, arrayOf(c[2], c[3], c[7], c[6]), u + d + w, v + d, u + d + w + w, v)
    face(out, part, true, arrayOf(c[0], c[4], c[7], c[3]), u, v + d, u + d, v + d + h)
    face(out, part, true, arrayOf(c[1], c[0], c[3], c[2]), u + d, v + d, u + d + w, v + d + h)
    face(out, part, true, arrayOf(c[5], c[1], c[2], c[6]), u + d + w, v + d, u + d + w + d, v + d + h)
    face(out, part, true, arrayOf(c[4], c[5], c[6], c[7]), u + d + w + d, v + d, u + d + w + d + w, v + d + h)
}

@Suppress("LongParameterList") // One face: its four corners and its uv rect.
private fun face(
    out: MutableList<SkinNode>, part: Int, side: Boolean, k: Array<FloatArray>, u0: Int, v0: Int, u1: Int, v1: Int,
) {
    for (t in minOf(v0, v1) until maxOf(v0, v1)) {
        for (s in minOf(u0, u1) until maxOf(u0, u1)) {
            val a = (s + 0.5f - u0) / (u1 - u0)
            val b = (t + 0.5f - v0) / (v1 - v0)
            fun at(i: Int) = (1 - b) * ((1 - a) * k[1][i] + a * k[0][i]) + b * ((1 - a) * k[2][i] + a * k[3][i])
            out.add(SkinNode(s, t, at(0), at(1), at(2), part, side))
        }
    }
}

private fun neighbours(nodes: List<SkinNode>): Array<IntArray> {
    val lists = Array(nodes.size) { ArrayList<Int>() }
    val reach = NEIGHBOUR_REACH * NEIGHBOUR_REACH
    for (i in nodes.indices) {
        for (j in i + 1 until nodes.size) {
            if (squaredDistance(nodes[i], nodes[j]) <= reach) {
                lists[i].add(j)
                lists[j].add(i)
            }
        }
    }
    return Array(nodes.size) { lists[it].toIntArray() }
}

private fun squaredDistance(a: SkinNode, b: SkinNode): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return dx * dx + dy * dy + dz * dz
}

// Dijkstra from the roots. Each node settles once, so the loop runs at most once for each queued edge.
private fun shortestPaths(
    nodes: List<SkinNode>, neighbours: Array<IntArray>, noise: FloatArray, sources: List<Int>,
    distance: FloatArray, parent: IntArray,
) {
    val queue = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
    for (s in sources) {
        distance[s] = 0f
        queue.add(0f to s)
    }
    val settled = BooleanArray(nodes.size)
    while (queue.isNotEmpty()) {
        val (d, node) = queue.poll()
        if (settled[node]) continue
        settled[node] = true
        for (other in neighbours[node]) {
            val step = maxOf(sqrt(squaredDistance(nodes[node], nodes[other])), MIN_STEP) * (1f + noise[other])
            val next = d + step
            if (next < distance[other]) {
                distance[other] = next
                parent[other] = node
                queue.add(next to other)
            }
        }
    }
}
