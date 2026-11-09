package org.shipwrights.enderkinesis.util

import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBd
import org.joml.primitives.AABBdc
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.bodies.VsBody
import org.valkyrienskies.core.api.bodies.shape.BodyShapeData
import org.valkyrienskies.core.api.bodies.shape.CompoundBodyShapeData
import org.valkyrienskies.core.api.bodies.shape.SphereBodyShapeData
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/** A standalone VS body on a ray, [distance] blocks from the ray origin. */
data class BodyHit(val bodyId: Long, val distance: Double)

/**
 * Slab-method ray-vs-AABB entry distance along the ray (origin + t·dir), or NaN for a miss.
 * An origin inside the box gives 0.
 */
fun rayAabbEntry(
    ox: Double, oy: Double, oz: Double,
    dx: Double, dy: Double, dz: Double,
    minX: Double, minY: Double, minZ: Double,
    maxX: Double, maxY: Double, maxZ: Double,
): Double {
    var tmin = Double.NEGATIVE_INFINITY
    var tmax = Double.POSITIVE_INFINITY
    if (Math.abs(dx) < 1e-9) {
        if (ox < minX || ox > maxX) return Double.NaN
    } else {
        val t1 = (minX - ox) / dx
        val t2 = (maxX - ox) / dx
        tmin = Math.max(tmin, Math.min(t1, t2))
        tmax = Math.min(tmax, Math.max(t1, t2))
        if (tmin > tmax) return Double.NaN
    }
    if (Math.abs(dy) < 1e-9) {
        if (oy < minY || oy > maxY) return Double.NaN
    } else {
        val t1 = (minY - oy) / dy
        val t2 = (maxY - oy) / dy
        tmin = Math.max(tmin, Math.min(t1, t2))
        tmax = Math.min(tmax, Math.max(t1, t2))
        if (tmin > tmax) return Double.NaN
    }
    if (Math.abs(dz) < 1e-9) {
        if (oz < minZ || oz > maxZ) return Double.NaN
    } else {
        val t1 = (minZ - oz) / dz
        val t2 = (maxZ - oz) / dz
        tmin = Math.max(tmin, Math.min(t1, t2))
        tmax = Math.min(tmax, Math.max(t1, t2))
        if (tmin > tmax) return Double.NaN
    }
    return if (tmin >= 0) tmin else if (tmax >= 0) 0.0 else Double.NaN
}

fun rayAabbEntry(origin: Vec3, dir: Vec3, box: AABBdc): Double = rayAabbEntry(
    origin.x, origin.y, origin.z, dir.x, dir.y, dir.z,
    box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
)

/** World AABBs of the standalone VS bodies that meet [aabb]. Ships are left out. */
@OptIn(GameTickOnly::class, VsBeta::class)
fun Level.standaloneBodyBoxes(aabb: AABBdc): List<AABBdc> {
    val world = shipObjectWorld
    return world.allBodies.getIntersecting(aabb, dimensionId).filter { world.allShips.getById(it.id) == null }.map { it.aabb }
}

/** Ray entry into a sphere at the local origin, or NaN for a miss. [d] need not be a unit vector. */
private fun raySphereEntry(o: Vector3dc, d: Vector3dc, radius: Double): Double {
    val a = d.lengthSquared()
    val b = o.dot(d)
    val c = o.lengthSquared() - radius * radius
    if (c <= 0.0) return 0.0
    val disc = b * b - a * c
    if (a < 1e-18 || disc < 0.0) return Double.NaN
    val t = (-b - Math.sqrt(disc)) / a
    return if (t >= 0.0) t else Double.NaN
}

/** A shape that is not a sphere counts as its own local box, which is the AABB the body already had. */
@OptIn(VsBeta::class)
private fun rayPrimitiveEntry(o: Vector3dc, d: Vector3dc, shape: BodyShapeData): Double {
    if (shape is SphereBodyShapeData) return raySphereEntry(o, d, shape.radius)
    val box = shape.aabb
    return rayAabbEntry(
        o.x(), o.y(), o.z(), d.x(), d.y(), d.z(),
        box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
    )
}

/**
 * Ray entry into the true collision shape of a body, with [origin] and [dir] already in the body frame.
 * The entry distance equals the world one, because every transform here keeps the ray parameter.
 */
@OptIn(VsBeta::class)
private fun rayShapeEntry(origin: Vector3dc, dir: Vector3dc, shape: BodyShapeData): Double {
    if (shape !is CompoundBodyShapeData) return rayPrimitiveEntry(origin, dir, shape)
    var best = Double.NaN
    val o = Vector3d()
    val d = Vector3d()
    for (child in shape.children) {
        // Child point = rotation * ((local + offset) * scaling) + position.
        child.rotation.transformInverse(origin.sub(child.position, o)).div(child.collisionShapeScaling).sub(child.collisionShapeOffset)
        child.rotation.transformInverse(d.set(dir)).div(child.collisionShapeScaling)
        val t = rayPrimitiveEntry(o, d, child.shape)
        if (!t.isNaN() && (best.isNaN() || t < best)) best = t
    }
    return best
}

@OptIn(VsBeta::class)
private fun bodyShapeEntry(origin: Vec3, dir: Vec3, shape: BodyShapeData, body: VsBody): Double {
    val toModel = body.kinematics.transform.toModel
    val o = toModel.transformPosition(Vector3d(origin.x, origin.y, origin.z))
    val d = toModel.transformDirection(Vector3d(dir.x, dir.y, dir.z))
    return rayShapeEntry(o, d, shape)
}

/**
 * The nearest standalone VS body whose collision shape the ray enters within [maxDistance].
 * VS2 has no body pick, and clip() sees only blocks. [dir] must be a unit vector.
 * The world AABB only culls: a sphere or compound shape is tested exactly, and a body with no shape data uses the AABB.
 */
@OptIn(GameTickOnly::class, VsBeta::class)
fun Level.pickStandaloneBody(origin: Vec3, dir: Vec3, maxDistance: Double): BodyHit? {
    val end = origin.add(dir.scale(maxDistance))
    val reach = AABBd(
        minOf(origin.x, end.x), minOf(origin.y, end.y), minOf(origin.z, end.z),
        maxOf(origin.x, end.x), maxOf(origin.y, end.y), maxOf(origin.z, end.z),
    )
    val world = shipObjectWorld
    var best: BodyHit? = null
    for (body in world.allBodies.getIntersecting(reach, dimensionId)) {
        if (world.allShips.getById(body.id) != null) continue
        val boxT = rayAabbEntry(origin, dir, body.aabb)
        if (boxT.isNaN() || boxT > maxDistance) continue
        val t = body.collisionShape?.let { bodyShapeEntry(origin, dir, it, body) } ?: boxT
        if (t.isNaN() || t > maxDistance || (best != null && t >= best.distance)) continue
        best = BodyHit(body.id, t)
    }
    return best
}

/**
 * The body under the crosshair of [player], if no block is nearer. Both sides can call it.
 * The reach is the vanilla client pick range.
 */
fun Player.lookedAtBody(): BodyHit? {
    val eye = eyePosition
    val look = lookAngle
    val reach = if (isCreative) CREATIVE_REACH else SURVIVAL_REACH
    val blockHit = level().clip(ClipContext(eye, eye.add(look.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this))
    // VS2 gives a WORLD location here, also for a ship block.
    return level().pickStandaloneBody(eye, look, blockHit.location.distanceTo(eye))
}

private const val SURVIVAL_REACH = 4.5
private const val CREATIVE_REACH = 5.0
