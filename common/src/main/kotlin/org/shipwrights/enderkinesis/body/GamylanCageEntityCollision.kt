package org.shipwrights.enderkinesis.body

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4dc
import org.valkyrienskies.core.api.bodies.QueryableVsBodyData
import org.valkyrienskies.core.api.bodies.VsBody
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils
import org.valkyrienskies.mod.common.vsCore

/**
 * Entity-vs-cage collision for Wand-of-Ga'Mylan cages. A crystal collides as its exact convex
 * hull ([GamylanCageShape.Hull]). A soder bottle collides as its compound of primitives.
 */
object GamylanCageEntityCollision {

    private const val COLLISION_EPSILON: Double = 1.0e-12

    /** False when this side has no cage, so `Entity.move` can skip the collision pass. */
    @JvmStatic
    fun mayCollide(level: Level): Boolean =
        if (level.isClientSide) {
            !org.shipwrights.enderkinesis.client.GamylanCageClientRegistry.hasNone(level.dimension())
        } else {
            !GamylanCageRegistry.hasNone()
        }

    @JvmStatic
    fun adjustMovement(entity: Entity, movement: Vec3, entityBoundingBox: AABB, level: Level): Vec3 {
        if (movement.lengthSqr() <= COLLISION_EPSILON) return movement
        val vsDim = level.dimensionId
        val bodies = level.shipObjectWorld.allBodies
        val moveLen = Math.sqrt(movement.lengthSqr())

        var adjusted = movement
        if (level.isClientSide) {
            // The server table is empty on the client of a dedicated server. The client entry has
            // no Tribulla flag, so a soder bottle collides as the crystal of the same size.
            org.shipwrights.enderkinesis.client.GamylanCageClientRegistry.forEachIn(level.dimension()) { entry ->
                val radius = GamylanCageShape.boundingRadius(entry.shapeDims)
                val toWorld = nearbyToWorld(bodies, entry.bodyId, radius, entityBoundingBox, moveLen)
                if (toWorld != null) adjusted = collideHull(entity, adjusted, entityBoundingBox, entry.hull, toWorld)
            }
        } else {
            GamylanCageRegistry.forEachInVsDim(vsDim) { data ->
                if (data.isTribulla) {
                    val radius = SoderShape.boundingRadius(data.entityCullSize)
                    val toWorld = nearbyToWorld(bodies, data.bodyId, radius, entityBoundingBox, moveLen)
                    if (toWorld != null) {
                        adjusted = EntityShipCollisionUtils.adjustEntityMovementForPrimitiveShapeCollision(
                            entity, adjusted, entityBoundingBox, SoderShape.newCompoundShape(data.entityCullSize), toWorld,
                        )
                    }
                } else {
                    val radius = GamylanCageShape.boundingRadius(data.shapeDims)
                    val toWorld = nearbyToWorld(bodies, data.bodyId, radius, entityBoundingBox, moveLen)
                    if (toWorld != null) adjusted = collideHull(entity, adjusted, entityBoundingBox, data.hull, toWorld)
                }
            }
        }
        return adjusted
    }

    /** Broad phase: the body's transform when it can touch the swept entity box, else null. */
    private fun nearbyToWorld(
        bodies: QueryableVsBodyData<VsBody>,
        bodyId: Long,
        bodyRadius: Double,
        box: AABB,
        moveLen: Double,
    ): Matrix4dc? {
        val body = bodies.getById(bodyId) ?: return null
        val halfExt = maxOf(box.maxX - box.minX, box.maxY - box.minY, box.maxZ - box.minZ) * 0.5
        val cullR = halfExt + bodyRadius + moveLen + 0.5
        val bodyPos = body.kinematics.position
        val dx = bodyPos.x() - (box.minX + box.maxX) * 0.5
        val dy = bodyPos.y() - (box.minY + box.maxY) * 0.5
        val dz = bodyPos.z() - (box.minZ + box.maxZ) * 0.5
        return if (dx * dx + dy * dy + dz * dz <= cullR * cullR) body.kinematics.transform.toWorld else null
    }

    private fun collideHull(
        entity: Entity,
        movement: Vec3,
        box: AABB,
        hull: GamylanCageShape.Hull,
        toWorld: Matrix4dc,
    ): Vec3 {
        val polygon = vsCore.entityPolygonCollider
            .createPolygonFromPointsAndNormals(hull.points, hull.normals, toWorld, null)
        return EntityShipCollisionUtils
            .adjustEntityMovementForPrimitiveShapePolygons(entity, movement, box, listOf(polygon))
    }
}
