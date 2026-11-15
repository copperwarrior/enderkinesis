package org.shipwrights.enderkinesis.util

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.toWorldCoordinates
import kotlin.math.abs
import kotlin.math.sqrt

// Short of the face, so a block that only touches the ray at its end does not block.
private const val FACE_PULL = 1.0e-3
// A ground point on a ship deck can sit just inside the deck; at most this many lifts, one cell each.
private const val MAX_LIFTS = 2
private const val LIFT_CLEARANCE = 0.05

/**
 * True when no collision block of the world or of a ship is between [from] and [to]. Give both ends in WORLD.
 * VS2 gives a ship hit a SHIPYARD blockPos, so [target] is in the frame of its own ship.
 */
fun Level.sees(from: Vec3, to: Vec3, target: BlockPos? = null): Boolean {
    var origin = from
    for (attempt in 0..MAX_LIFTS) {
        val hit = clip(colliderClip(origin, to))
        if (hit.type == HitResult.Type.MISS || (target != null && hit.blockPos == target)) return true
        if (attempt == MAX_LIFTS) break
        origin = liftedOutOf(origin, hit.blockPos) ?: break
    }
    return false
}

/** Ship axis (0 X, 1 Y, 2 Z) whose world +Y component is largest in size; Y wins ties, so a level ship lifts along +Y. */
internal fun liftAxis(upOfX: Double, upOfY: Double, upOfZ: Double): Int {
    val ax = abs(upOfX); val ay = abs(upOfY); val az = abs(upOfZ)
    return if (ax > ay && ax >= az) 0 else if (az > ay) 2 else 1
}

/** [origin] out of the collision box of cell [pos] (either frame) that holds it, toward world up; null when no box holds it. */
private fun Level.liftedOutOf(origin: Vec3, pos: BlockPos): Vec3? {
    val ship = getShipManagingPos(pos)
    val local = Vector3d(origin.x, origin.y, origin.z)
    ship?.transform?.worldToShip?.transformPosition(local)
    val x = local.x - pos.x; val y = local.y - pos.y; val z = local.z - pos.z
    val box = getBlockState(pos).getCollisionShape(this, pos).toAabbs().firstOrNull {
        x > it.minX && x < it.maxX && y > it.minY && y < it.maxY && z > it.minZ && z < it.maxZ
    } ?: return null
    val toWorld = ship?.transform?.shipToWorld
    // A ship tilted past 90 degrees has its own +Y pointing down, so lift along the axis that points most upward.
    val upOfX = toWorld?.m01() ?: 0.0
    val upOfY = toWorld?.m11() ?: 1.0
    val upOfZ = toWorld?.m21() ?: 0.0
    when (liftAxis(upOfX, upOfY, upOfZ)) {
        0 -> local.x = if (upOfX >= 0.0) pos.x + box.maxX + LIFT_CLEARANCE else pos.x + box.minX - LIFT_CLEARANCE
        2 -> local.z = if (upOfZ >= 0.0) pos.z + box.maxZ + LIFT_CLEARANCE else pos.z + box.minZ - LIFT_CLEARANCE
        else -> local.y = if (upOfY >= 0.0) pos.y + box.maxY + LIFT_CLEARANCE else pos.y + box.minY - LIFT_CLEARANCE
    }
    toWorld?.transformPosition(local)
    return Vec3(local.x, local.y, local.z)
}

/** A shipyard entity (seat, projectile) has a shipyard box. */
fun Level.sees(from: Vec3, target: Entity): Boolean = sees(from, toWorldCoordinates(target.boundingBox.center))

/** Tests to the point of cell [pos] of [ship] (null is the world) nearest [from], so the cell does not block itself. */
fun Level.sees(from: Vec3, pos: BlockPos, ship: Ship?, scratch: Vector3d): Boolean {
    scratch.set(from.x, from.y, from.z)
    ship?.transform?.worldToShip?.transformPosition(scratch)
    scratch.set(
        scratch.x.coerceIn(pos.x.toDouble(), pos.x + 1.0),
        scratch.y.coerceIn(pos.y.toDouble(), pos.y + 1.0),
        scratch.z.coerceIn(pos.z.toDouble(), pos.z + 1.0),
    )
    ship?.transform?.shipToWorld?.transformPosition(scratch)
    val dx = from.x - scratch.x; val dy = from.y - scratch.y; val dz = from.z - scratch.z
    val d = sqrt(dx * dx + dy * dy + dz * dz)
    if (d <= FACE_PULL) return true
    val k = FACE_PULL / d
    return sees(from, Vec3(scratch.x + dx * k, scratch.y + dy * k, scratch.z + dz * k), pos)
}

/** A WORLD point [by] off [face] of the struck block [pos]. [location] can be in either frame; a ship hit has a ship-local face. */
fun Level.offFace(location: Vec3, pos: BlockPos, face: Direction, by: Double): Vec3 {
    val n = Vector3d(face.stepX.toDouble(), face.stepY.toDouble(), face.stepZ.toDouble())
    getShipManagingPos(pos)?.transform?.shipToWorld?.transformDirection(n)?.normalize()
    return toWorldCoordinates(location).add(n.x * by, n.y * by, n.z * by)
}
