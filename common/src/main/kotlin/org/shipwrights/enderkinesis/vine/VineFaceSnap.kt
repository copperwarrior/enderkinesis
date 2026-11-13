package org.shipwrights.enderkinesis.vine

import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

private const val FACE_EPSILON = 1.0e-4

private val FACE_GRID = doubleArrayOf(0.0, 0.5, 1.0)
private val QUADRANT_GRID = doubleArrayOf(0.25, 0.75)

/**
 * Snaps [hit] to the nearest of the 13 anchor points (corners, edge middles, centre, quadrant centres)
 * on the [face] side of the box in [boxes] that holds the hit. [boxes] and [hit] use the same frame.
 * Returns [hit] when [boxes] is empty.
 */
internal fun snapToFaceAnchor(boxes: List<AABB>, face: Direction, hit: Vec3): Vec3 {
    val box = boxes.minByOrNull { faceDistanceSq(it, face, hit) } ?: return hit
    val axis = face.axis
    val (uAxis, vAxis) = Direction.Axis.entries.filter { it != axis }
    val plane = if (face.axisDirection == Direction.AxisDirection.POSITIVE) box.max(axis) else box.min(axis)
    val u0 = box.min(uAxis); val uSize = box.max(uAxis) - u0
    val v0 = box.min(vAxis); val vSize = box.max(vAxis) - v0
    val hitU = hit.get(uAxis); val hitV = hit.get(vAxis)

    var bestU = u0; var bestV = v0; var best = Double.MAX_VALUE
    fun consider(fu: Double, fv: Double) {
        val u = u0 + fu * uSize; val v = v0 + fv * vSize
        val d = (u - hitU) * (u - hitU) + (v - hitV) * (v - hitV)
        if (d < best) { best = d; bestU = u; bestV = v }
    }
    for (fu in FACE_GRID) for (fv in FACE_GRID) consider(fu, fv)
    for (fu in QUADRANT_GRID) for (fv in QUADRANT_GRID) consider(fu, fv)

    val coords = DoubleArray(3)
    coords[axis.ordinal] = plane
    coords[uAxis.ordinal] = bestU
    coords[vAxis.ordinal] = bestV
    return Vec3(coords[0], coords[1], coords[2])
}

/** Squared distance from [hit] to the [face] side of [box]; zero when the hit is on that side. */
private fun faceDistanceSq(box: AABB, face: Direction, hit: Vec3): Double {
    var sum = 0.0
    for (a in Direction.Axis.entries) {
        val p = hit.get(a)
        val d = if (a == face.axis) {
            val plane = if (face.axisDirection == Direction.AxisDirection.POSITIVE) box.max(a) else box.min(a)
            p - plane
        } else {
            when {
                p < box.min(a) - FACE_EPSILON -> box.min(a) - p
                p > box.max(a) + FACE_EPSILON -> p - box.max(a)
                else -> 0.0
            }
        }
        sum += d * d
    }
    return sum
}
