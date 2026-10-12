package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import org.joml.Matrix3f
import org.joml.Matrix4dc
import org.joml.Matrix4f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.item.AegisBox
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.abs
import kotlin.math.sqrt

/** Lines where the shield faces cut the exposed faces of block collision boxes.
 *  All state is scratch for the render thread: call [collect], then the emit functions. */
object AegisIntersectionGlow {

    private const val STRIP_WIDTH: Double = 0.3
    private const val STRIP_ALPHA: Int = 60
    private const val MAX_SEGMENTS: Int = 1024

    /** Two end points and the strip offset, three doubles each. */
    private const val STRIDE: Int = 9

    /** Half diagonal of a unit cube. */
    private const val BLOCK_REACH: Double = 0.87

    /** Corner point, first strip offset, mitre offset, second strip offset. */
    private const val CORNER_STRIDE: Int = 12
    private const val MAX_CORNERS: Int = 1024
    private const val SHARED_POINT_DIST_SQR: Double = 1e-8
    private const val MAX_MITRE_SCALE: Double = 4.0
    private const val EPSILON: Double = 1e-6

    private const val HALF_W: Double = AegisBox.WIDTH * 0.5
    private const val HALF_H: Double = AegisBox.HEIGHT * 0.5
    private const val HALF_D: Double = AegisBox.VISUAL_DEPTH * 0.5

    private val DIRECTIONS: Array<Direction> = Direction.values()

    private val faceCorners = DoubleArray(6 * 12)
    private val faceNormals = DoubleArray(6 * 3)
    private val segments = DoubleArray(MAX_SEGMENTS * STRIDE)
    private val segmentFaces = IntArray(MAX_SEGMENTS)
    private var segmentCount = 0
    private val corners = DoubleArray(MAX_CORNERS * CORNER_STRIDE)
    private var cornerCount = 0
    private val box = DoubleArray(6)
    private val cut = DoubleArray(6)
    private var stripWidth = STRIP_WIDTH
    private val center = Vector3d()
    private val axisX = Vector3d()
    private val axisY = Vector3d()
    private val axisZ = Vector3d()
    private val shipCenter = Vector3d()
    private val shipAxisX = Vector3d()
    private val shipAxisY = Vector3d()
    private val shipAxisZ = Vector3d()
    private val scratch = Vector3d()
    private val cursor = BlockPos.MutableBlockPos()
    private val neighbour = BlockPos.MutableBlockPos()

    fun collect(level: ClientLevel, frame: AegisBox.Frame, expand: Double) {
        segmentCount = 0
        cornerCount = 0
        val hx = HALF_W * expand
        val hy = HALF_H * expand
        val hz = HALF_D * expand
        center.set(frame.center.x, frame.center.y, frame.center.z)
        axisX.set(frame.axisX.x, frame.axisX.y, frame.axisX.z)
        axisY.set(frame.axisY.x, frame.axisY.y, frame.axisY.z)
        axisZ.set(frame.axisZ.x, frame.axisZ.y, frame.axisZ.z)
        collectIn(level, center, axisX, axisY, axisZ, hx, hy, hz, STRIP_WIDTH)
        collectShips(level, hx, hy, hz)
    }

    /** Does the same collection in the shipyard frame of each ship near the shield. */
    private fun collectShips(level: ClientLevel, hx: Double, hy: Double, hz: Double) {
        val ex = extent(0, hx, hy, hz)
        val ey = extent(1, hx, hy, hz)
        val ez = extent(2, hx, hy, hz)
        for (ship in level.shipObjectWorld.loadedShips) {
            val clientShip = ship as? ClientShip ?: continue
            val aabb = clientShip.renderAABB
            if (aabb.maxX() < center.x - ex || aabb.minX() > center.x + ex) continue
            if (aabb.maxY() < center.y - ey || aabb.minY() > center.y + ey) continue
            if (aabb.maxZ() < center.z - ez || aabb.minZ() > center.z + ez) continue
            val transform = clientShip.renderTransform
            val toShip = transform.worldToShip
            toShip.transformPosition(center, shipCenter)
            // A ship can have a scale, thus the axes are not unit length in the shipyard frame.
            val kx = toShip.transformDirection(axisX, shipAxisX).length()
            val ky = toShip.transformDirection(axisY, shipAxisY).length()
            val kz = toShip.transformDirection(axisZ, shipAxisZ).length()
            if (kx < EPSILON || ky < EPSILON || kz < EPSILON) continue
            shipAxisX.div(kx)
            shipAxisY.div(ky)
            shipAxisZ.div(kz)
            val firstSegment = segmentCount
            val firstCorner = cornerCount
            collectIn(level, shipCenter, shipAxisX, shipAxisY, shipAxisZ, hx * kx, hy * ky, hz * kz, STRIP_WIDTH * kx)
            toWorld(transform.shipToWorld, segments, firstSegment * STRIDE, segmentCount * STRIDE, STRIDE, 6)
            toWorld(transform.shipToWorld, corners, firstCorner * CORNER_STRIDE, cornerCount * CORNER_STRIDE, CORNER_STRIDE, 3)
        }
    }

    private fun extent(axis: Int, hx: Double, hy: Double, hz: Double): Double =
        abs(axisX[axis]) * hx + abs(axisY[axis]) * hy + abs(axisZ[axis]) * hz

    /** In each record of [stride] doubles, the vectors from offset [directionsFrom] are directions. */
    private fun toWorld(toWorld: Matrix4dc, buffer: DoubleArray, from: Int, to: Int, stride: Int, directionsFrom: Int) {
        for (p in from until to step 3) {
            scratch.set(buffer[p], buffer[p + 1], buffer[p + 2])
            if ((p - from) % stride >= directionsFrom) toWorld.transformDirection(scratch) else toWorld.transformPosition(scratch)
            buffer[p] = scratch.x
            buffer[p + 1] = scratch.y
            buffer[p + 2] = scratch.z
        }
    }

    private fun collectIn(
        level: ClientLevel, c: Vector3d, ax: Vector3d, ay: Vector3d, az: Vector3d,
        hx: Double, hy: Double, hz: Double, strip: Double,
    ) {
        stripWidth = strip
        fillFace(0, c, ax, ay, az, hx, hy, hz)
        fillFace(2, c, ay, az, ax, hy, hz, hx)
        fillFace(4, c, az, ax, ay, hz, hx, hy)

        val ex = abs(ax.x) * hx + abs(ay.x) * hy + abs(az.x) * hz
        val ey = abs(ax.y) * hx + abs(ay.y) * hy + abs(az.y) * hz
        val ez = abs(ax.z) * hx + abs(ay.z) * hy + abs(az.z) * hz
        for (bx in Mth.floor(c.x - ex)..Mth.floor(c.x + ex)) {
            for (by in Mth.floor(c.y - ey)..Mth.floor(c.y + ey)) {
                for (bz in Mth.floor(c.z - ez)..Mth.floor(c.z + ez)) {
                    val rx = bx + 0.5 - c.x
                    val ry = by + 0.5 - c.y
                    val rz = bz + 0.5 - c.z
                    if (abs(rx * ax.x + ry * ax.y + rz * ax.z) > hx + BLOCK_REACH) continue
                    if (abs(rx * ay.x + ry * ay.y + rz * ay.z) > hy + BLOCK_REACH) continue
                    if (abs(rx * az.x + ry * az.y + rz * az.z) > hz + BLOCK_REACH) continue
                    collectBlock(level, bx, by, bz)
                }
            }
        }
    }

    fun emitGlow(consumer: VertexConsumer, matrix: Matrix4f) {
        for (i in 0 until segmentCount) {
            val s = i * STRIDE
            val ox = segments[s + 6]
            val oy = segments[s + 7]
            val oz = segments[s + 8]
            if (ox == 0.0 && oy == 0.0 && oz == 0.0) continue
            glowVertex(consumer, matrix, segments[s], segments[s + 1], segments[s + 2], STRIP_ALPHA)
            glowVertex(consumer, matrix, segments[s + 3], segments[s + 4], segments[s + 5], STRIP_ALPHA)
            glowVertex(consumer, matrix, segments[s + 3] + ox, segments[s + 4] + oy, segments[s + 5] + oz, 0)
            glowVertex(consumer, matrix, segments[s] + ox, segments[s + 1] + oy, segments[s + 2] + oz, 0)
        }
        for (i in 0 until cornerCount) {
            val c = i * CORNER_STRIDE
            glowVertex(consumer, matrix, corners[c], corners[c + 1], corners[c + 2], STRIP_ALPHA)
            for (o in 3..9 step 3) {
                glowVertex(
                    consumer, matrix,
                    corners[c] + corners[c + o], corners[c + 1] + corners[c + o + 1], corners[c + 2] + corners[c + o + 2], 0,
                )
            }
        }
    }

    fun emitLines(consumer: VertexConsumer, matrix: Matrix4f, normal: Matrix3f) {
        for (i in 0 until segmentCount) {
            val s = i * STRIDE
            val dx = (segments[s + 3] - segments[s]).toFloat()
            val dy = (segments[s + 4] - segments[s + 1]).toFloat()
            val dz = (segments[s + 5] - segments[s + 2]).toFloat()
            val invLen = 1f / sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            for (p in 0..3 step 3) {
                consumer.vertex(
                    matrix, segments[s + p].toFloat(), segments[s + p + 1].toFloat(), segments[s + p + 2].toFloat(),
                )
                    .color(255, 255, 255, 255)
                    .normal(normal, dx * invLen, dy * invLen, dz * invLen)
                    .endVertex()
            }
        }
    }

    private fun glowVertex(consumer: VertexConsumer, matrix: Matrix4f, x: Double, y: Double, z: Double, alpha: Int) {
        consumer.vertex(matrix, x.toFloat(), y.toFloat(), z.toFloat()).color(255, 255, 255, alpha).endVertex()
    }

    /** Fills the two faces on the [n] axis: [face] on the negative side, [face] + 1 on the positive side. */
    private fun fillFace(
        face: Int, c: Vector3d, n: Vector3d, u: Vector3d, v: Vector3d, hn: Double, hu: Double, hv: Double,
    ) {
        for (side in 0..1) {
            val sign = if (side == 0) -1.0 else 1.0
            val f = face + side
            faceNormals[f * 3] = n.x * sign
            faceNormals[f * 3 + 1] = n.y * sign
            faceNormals[f * 3 + 2] = n.z * sign
            for (i in 0 until 4) {
                val su = if (i == 1 || i == 2) hu else -hu
                val sv = if (i >= 2) hv else -hv
                val o = f * 12 + i * 3
                faceCorners[o] = c.x + n.x * sign * hn + u.x * su + v.x * sv
                faceCorners[o + 1] = c.y + n.y * sign * hn + u.y * su + v.y * sv
                faceCorners[o + 2] = c.z + n.z * sign * hn + u.z * su + v.z * sv
            }
        }
    }

    private fun collectBlock(level: ClientLevel, bx: Int, by: Int, bz: Int) {
        cursor.set(bx, by, bz)
        val state = level.getBlockState(cursor)
        if (state.isAir) return
        if (state.isCollisionShapeFullBlock(level, cursor)) {
            collectBox(level, bx, by, bz, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
            return
        }
        val shape = state.getCollisionShape(level, cursor)
        if (shape.isEmpty) return
        shape.forAllBoxes { x0, y0, z0, x1, y1, z1 -> collectBox(level, bx, by, bz, x0, y0, z0, x1, y1, z1) }
    }

    private fun collectBox(
        level: ClientLevel, bx: Int, by: Int, bz: Int,
        x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
    ) {
        box[0] = bx + x0; box[1] = by + y0; box[2] = bz + z0
        box[3] = bx + x1; box[4] = by + y1; box[5] = bz + z1
        val firstSegment = segmentCount
        for (direction in DIRECTIONS) {
            val axis = direction.axis.ordinal
            val positive = direction.axisDirection == Direction.AxisDirection.POSITIVE
            val plane = box[if (positive) axis + 3 else axis]
            val blockEdge = direction.axis.choose(bx, by, bz) + if (positive) 1.0 else 0.0
            if (abs(plane - blockEdge) < EPSILON && isCovered(level, bx, by, bz, direction)) continue
            for (face in 0 until 6) collectSegment(face, axis, plane, if (positive) 1.0 else -1.0)
        }
        collectCorners(firstSegment)
    }

    /** The strips of two segments leave an open wedge at a convex corner. A convex corner of
     *  the cut is always between two faces of one collision box. */
    private fun collectCorners(firstSegment: Int) {
        for (i in firstSegment until segmentCount) {
            for (j in i + 1 until segmentCount) {
                if (segmentFaces[i] != segmentFaces[j]) continue
                for (endI in 0..3 step 3) {
                    for (endJ in 0..3 step 3) collectCorner(i * STRIDE, endI, j * STRIDE, endJ)
                }
            }
        }
    }

    private fun collectCorner(a: Int, endA: Int, b: Int, endB: Int) {
        if (cornerCount >= MAX_CORNERS) return
        var distSqr = 0.0
        var aOut = 0.0
        var bOut = 0.0
        var dot = 0.0
        var lenA = 0.0
        var lenB = 0.0
        for (m in 0..2) {
            val point = segments[a + endA + m]
            val gap = point - segments[b + endB + m]
            distSqr += gap * gap
            // Each offset must point away from the body of the other segment.
            aOut += segments[b + 6 + m] * (point - segments[a + (3 - endA) + m])
            bOut += segments[a + 6 + m] * (point - segments[b + (3 - endB) + m])
            dot += segments[a + 6 + m] * segments[b + 6 + m]
            lenA += segments[a + 6 + m] * segments[a + 6 + m]
            lenB += segments[b + 6 + m] * segments[b + 6 + m]
        }
        if (distSqr > SHARED_POINT_DIST_SQR || aOut < EPSILON || bOut < EPSILON) return
        val cos = dot / sqrt(lenA * lenB).coerceAtLeast(EPSILON)
        val mitre = (1.0 / (1.0 + cos).coerceAtLeast(EPSILON)).coerceAtMost(MAX_MITRE_SCALE)
        val c = cornerCount * CORNER_STRIDE
        for (m in 0..2) {
            corners[c + m] = segments[a + endA + m]
            corners[c + 3 + m] = segments[a + 6 + m]
            corners[c + 6 + m] = (segments[a + 6 + m] + segments[b + 6 + m]) * mitre
            corners[c + 9 + m] = segments[b + 6 + m]
        }
        cornerCount++
    }

    private fun isCovered(level: ClientLevel, bx: Int, by: Int, bz: Int, direction: Direction): Boolean {
        neighbour.set(bx + direction.stepX, by + direction.stepY, bz + direction.stepZ)
        return level.getBlockState(neighbour).isCollisionShapeFullBlock(level, neighbour)
    }

    /** Cuts shield face [face] with the world plane `axis = plane`, then clips the cut to [box]. */
    private fun collectSegment(face: Int, axis: Int, plane: Double, outward: Double) {
        if (segmentCount >= MAX_SEGMENTS) return
        val base = face * 12
        var found = 0
        for (i in 0 until 4) {
            val a = base + i * 3
            val b = base + ((i + 1) and 3) * 3
            val da = faceCorners[a + axis] - plane
            val db = faceCorners[b + axis] - plane
            if ((da < 0.0) == (db < 0.0) || found == 2) continue
            val t = da / (da - db)
            for (m in 0..2) cut[found * 3 + m] = faceCorners[a + m] + (faceCorners[b + m] - faceCorners[a + m]) * t
            found++
        }
        if (found < 2) return

        var t0 = 0.0
        var t1 = 1.0
        for (m in 0..2) {
            if (m == axis) continue
            val p = cut[m]
            val d = cut[3 + m] - p
            if (abs(d) < EPSILON) {
                if (p < box[m] || p > box[m + 3]) return
                continue
            }
            val ta = (box[m] - p) / d
            val tb = (box[m + 3] - p) / d
            t0 = maxOf(t0, minOf(ta, tb))
            t1 = minOf(t1, maxOf(ta, tb))
            if (t1 - t0 < EPSILON) return
        }

        val s = segmentCount * STRIDE
        for (m in 0..2) {
            val d = cut[3 + m] - cut[m]
            segments[s + m] = cut[m] + d * t0
            segments[s + 3 + m] = cut[m] + d * t1
        }
        // The strip goes along the shield face, away from the block.
        val n = face * 3
        val along = faceNormals[n + axis]
        val length = sqrt((1.0 - along * along).coerceAtLeast(0.0))
        val scale = if (length < EPSILON) 0.0 else outward * stripWidth / length
        for (m in 0..2) {
            val unit = if (m == axis) 1.0 else 0.0
            segments[s + 6 + m] = (unit - along * faceNormals[n + m]) * scale
        }
        segmentFaces[segmentCount] = face
        segmentCount++
    }
}
