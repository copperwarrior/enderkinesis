package org.shipwrights.enderkinesis.util

import it.unimi.dsi.fastutil.longs.LongArrayList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import org.joml.Matrix3d
import org.joml.Matrix4dc
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBd
import org.joml.primitives.AABBdc
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.bodies.ServerBaseVsBody
import org.valkyrienskies.core.api.bodies.shape.SphereBodyShapeData
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/** An oriented box in the world. [rotation] turns the box axes into world axes. */
class WorldBox(val centre: Vector3dc, val rotation: Quaterniondc, val half: Vector3dc) {
    fun worldAabb(): AABBd = worldAabb(AABBd(), Matrix3d())

    fun worldAabb(out: AABBd, scratch: Matrix3d): AABBd {
        val r = scratch.set(rotation)
        val ex = Math.abs(r.m00) * half.x() + Math.abs(r.m10) * half.y() + Math.abs(r.m20) * half.z()
        val ey = Math.abs(r.m01) * half.x() + Math.abs(r.m11) * half.y() + Math.abs(r.m21) * half.z()
        val ez = Math.abs(r.m02) * half.x() + Math.abs(r.m12) * half.y() + Math.abs(r.m22) * half.z()
        return out.setMin(centre.x() - ex, centre.y() - ey, centre.z() - ez).setMax(centre.x() + ex, centre.y() + ey, centre.z() + ez)
    }

    companion object {
        fun of(aabb: AABBdc) = WorldBox(
            Vector3d((aabb.minX() + aabb.maxX()) * 0.5, (aabb.minY() + aabb.maxY()) * 0.5, (aabb.minZ() + aabb.maxZ()) * 0.5),
            Quaterniond(),
            Vector3d((aabb.maxX() - aabb.minX()) * 0.5, (aabb.maxY() - aabb.minY()) * 0.5, (aabb.maxZ() - aabb.minZ()) * 0.5),
        )
    }
}

/**
 * The box [b] as seen from the frame of a box A: [rel] turns B axes into A axes, [centre] is the B middle in A.
 * Not thread-safe: one instance for each caller.
 */
class BoxInFrame {
    val rel = Matrix3d()
    val centre = Vector3d()
    val half = Vector3d()
    private val abs = Matrix3d()
    private val ha = DoubleArray(3)
    private val t = DoubleArray(3)
    private val hb = DoubleArray(3)
    private val q = Quaterniond()

    /** [worldToFrame] null means the frame is the world. [frameScale] is world blocks per frame unit (uniform). */
    fun set(b: WorldBox, frameRotation: Quaterniondc, worldToFrame: Matrix4dc?, frameScale: Double = 1.0): BoxInFrame {
        rel.set(q.set(frameRotation).conjugate().mul(b.rotation))
        abs.set(rel).apply {
            m00 = Math.abs(m00); m01 = Math.abs(m01); m02 = Math.abs(m02)
            m10 = Math.abs(m10); m11 = Math.abs(m11); m12 = Math.abs(m12)
            m20 = Math.abs(m20); m21 = Math.abs(m21); m22 = Math.abs(m22)
        }
        if (worldToFrame == null) centre.set(b.centre) else worldToFrame.transformPosition(b.centre, centre)
        half.set(b.half).div(frameScale)
        hb[0] = half.x; hb[1] = half.y; hb[2] = half.z
        return this
    }

    /** Extent of this box along frame axis [i]. */
    fun reach(i: Int): Double = abs.getRowColumn(i, 0) * half.x + abs.getRowColumn(i, 1) * half.y + abs.getRowColumn(i, 2) * half.z

    /** Separating-axis test against a frame-aligned box from [min] to [max]. */
    @Suppress("CyclomaticComplexMethod") // The 15 axes of the separating-axis test.
    fun overlaps(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): Boolean {
        ha[0] = (maxX - minX) * 0.5; ha[1] = (maxY - minY) * 0.5; ha[2] = (maxZ - minZ) * 0.5
        t[0] = centre.x - (minX + maxX) * 0.5; t[1] = centre.y - (minY + maxY) * 0.5; t[2] = centre.z - (minZ + maxZ) * 0.5
        val eps = 1.0e-9
        for (i in 0..2) if (Math.abs(t[i]) > ha[i] + reach(i)) return false
        for (j in 0..2) {
            val proj = t[0] * rel.getRowColumn(0, j) + t[1] * rel.getRowColumn(1, j) + t[2] * rel.getRowColumn(2, j)
            val ra = ha[0] * abs.getRowColumn(0, j) + ha[1] * abs.getRowColumn(1, j) + ha[2] * abs.getRowColumn(2, j)
            if (Math.abs(proj) > ra + hb[j]) return false
        }
        for (i in 0..2) for (j in 0..2) {
            val i1 = (i + 1) % 3; val i2 = (i + 2) % 3
            val j1 = (j + 1) % 3; val j2 = (j + 2) % 3
            val ra = ha[i1] * (abs.getRowColumn(i2, j) + eps) + ha[i2] * (abs.getRowColumn(i1, j) + eps)
            val rb = hb[j1] * (abs.getRowColumn(i, j2) + eps) + hb[j2] * (abs.getRowColumn(i, j1) + eps)
            val proj = t[i2] * rel.getRowColumn(i1, j) - t[i1] * rel.getRowColumn(i2, j)
            if (Math.abs(proj) > ra + rb) return false
        }
        return true
    }

    /** True when the box comes within [radius] of the frame origin. */
    fun reachesSphere(radius: Double): Boolean {
        // Clamp the origin into the box, in box axes.
        var d2 = 0.0
        for (j in 0..2) {
            val along = -(centre.x * rel.getRowColumn(0, j) + centre.y * rel.getRowColumn(1, j) + centre.z * rel.getRowColumn(2, j))
            val out = Math.abs(along) - hb[j]
            if (out > 0) d2 += out * out
        }
        return d2 <= radius * radius
    }
}

/** Reused objects for the box searches below. Not thread-safe: one instance for each caller. */
class BoxSearchScratch {
    val frame = BoxInFrame()
    val reach = AABBd()
    val rotation = Matrix3d()
    val pos = BlockPos.MutableBlockPos()
    val middle = Vector3d()
    val identity = Quaterniond()
}

/** The search stops for a box that covers more ship cells than this. */
const val MAX_BOX_CELLS = 32768

/**
 * Calls [action] for each block of a loaded ship whose collision shape bounds meet [box].
 * [shipPos] and [worldMiddle] are scratch objects: copy them to keep them.
 */
@OptIn(GameTickOnly::class)
inline fun ServerLevel.forEachShipBlockInBox(
    box: WorldBox,
    scratch: BoxSearchScratch = BoxSearchScratch(),
    action: (ship: LoadedServerShip, shipPos: BlockPos, worldMiddle: Vector3dc) -> Unit,
) {
    val reach = box.worldAabb(scratch.reach, scratch.rotation)
    val dim = dimensionId
    val inShip = scratch.frame
    val pos = scratch.pos
    val middle = scratch.middle
    for (ship in shipObjectWorld.loadedShips) {
        if (ship.chunkClaimDimension != dim) continue
        val wa = ship.worldAABB
        if (wa.maxX() < reach.minX || wa.minX() > reach.maxX || wa.maxY() < reach.minY || wa.minY() > reach.maxY ||
            wa.maxZ() < reach.minZ || wa.minZ() > reach.maxZ) continue
        inShip.set(box, ship.transform.shipToWorldRotation, ship.transform.worldToShip, ship.transform.shipToWorldScaling.x())
        val c = inShip.centre
        val x0 = Math.floor(c.x - inShip.reach(0)).toInt(); val x1 = Math.floor(c.x + inShip.reach(0)).toInt()
        val y0 = Math.floor(c.y - inShip.reach(1)).toInt(); val y1 = Math.floor(c.y + inShip.reach(1)).toInt()
        val z0 = Math.floor(c.z - inShip.reach(2)).toInt(); val z1 = Math.floor(c.z + inShip.reach(2)).toInt()
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) * (z1 - z0 + 1) > MAX_BOX_CELLS) continue
        for (x in x0..x1) for (y in y0..y1) for (z in z0..z1) {
            pos.set(x, y, z)
            val shape = getBlockState(pos).getCollisionShape(this, pos)
            if (shape.isEmpty) continue
            if (!inShip.overlaps(
                    x + shape.min(Direction.Axis.X), y + shape.min(Direction.Axis.Y), z + shape.min(Direction.Axis.Z),
                    x + shape.max(Direction.Axis.X), y + shape.max(Direction.Axis.Y), z + shape.max(Direction.Axis.Z),
                )) continue
            ship.transform.shipToWorld.transformPosition(x + 0.5, y + 0.5, z + 0.5, middle)
            action(ship, pos, middle)
        }
    }
}

/**
 * Ids of the standalone VS bodies whose shape meets [box]. A sphere is exact; other shapes use their bounds in the
 * body frame, and a body with no shape data on this side uses its world AABB.
 */
@OptIn(GameTickOnly::class, VsBeta::class)
fun ServerLevel.standaloneBodiesInBox(box: WorldBox, scratch: BoxSearchScratch = BoxSearchScratch()): LongArray {
    val ids = LongArrayList()
    val inBody = scratch.frame
    val identity = scratch.identity
    for (body in standaloneBodies(box.worldAabb(scratch.reach, scratch.rotation))) {
        val shape = body.collisionShape
        val hit = if (shape == null) {
            val a = body.aabb
            inBody.set(box, identity, null).overlaps(a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ())
        } else {
            val transform = body.kinematics.transform
            inBody.set(box, transform.rotation, transform.toModel, transform.scaling.x())
            if (shape is SphereBodyShapeData) inBody.reachesSphere(shape.radius)
            else shape.aabb.let { inBody.overlaps(it.minX(), it.minY(), it.minZ(), it.maxX(), it.maxY(), it.maxZ()) }
        }
        if (hit) ids.add(body.id)
    }
    return ids.toLongArray()
}

/** True when the world point is in the shape of this body, with the same shape rules as [standaloneBodiesInBox]. */
@OptIn(VsBeta::class)
fun ServerBaseVsBody.containsWorldPoint(x: Double, y: Double, z: Double, scratch: Vector3d): Boolean {
    val a = aabb
    if (x < a.minX() || x > a.maxX() || y < a.minY() || y > a.maxY() || z < a.minZ() || z > a.maxZ()) return false
    val shape = collisionShape ?: return true
    kinematics.transform.toModel.transformPosition(scratch.set(x, y, z))
    if (shape is SphereBodyShapeData) return scratch.lengthSquared() <= shape.radius * shape.radius
    val b = shape.aabb
    return scratch.x in b.minX()..b.maxX() && scratch.y in b.minY()..b.maxY() && scratch.z in b.minZ()..b.maxZ()
}
