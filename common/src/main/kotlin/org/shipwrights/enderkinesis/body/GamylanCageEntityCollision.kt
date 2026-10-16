package org.shipwrights.enderkinesis.body

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4dc
import org.joml.Vector3d
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
        val shipWorld = level.shipObjectWorld

        // Broad-phase cull radius around the swept entity AABB. Loop-
        // invariant so we compute it once.
        val cx = (entityBoundingBox.minX + entityBoundingBox.maxX) * 0.5
        val cy = (entityBoundingBox.minY + entityBoundingBox.maxY) * 0.5
        val cz = (entityBoundingBox.minZ + entityBoundingBox.maxZ) * 0.5
        val halfX = (entityBoundingBox.maxX - entityBoundingBox.minX) * 0.5
        val halfY = (entityBoundingBox.maxY - entityBoundingBox.minY) * 0.5
        val halfZ = (entityBoundingBox.maxZ - entityBoundingBox.minZ) * 0.5
        val halfExt = maxOf(halfX, halfY, halfZ)
        val moveLen = Math.sqrt(movement.lengthSqr())

        var adjusted = movement
        val collide = { bodyId: Long, bodyRadius: Double, adjust: (Vec3, Matrix4dc) -> Vec3 ->
            val body = shipWorld.allBodies.getById(bodyId)
            if (body != null) {
                val bodyPos = body.kinematics.position
                val cullR = halfExt + bodyRadius + moveLen + 0.5
                val dx = bodyPos.x() - cx
                val dy = bodyPos.y() - cy
                val dz = bodyPos.z() - cz
                if (dx * dx + dy * dy + dz * dz <= cullR * cullR) {
                    adjusted = adjust(adjusted, body.kinematics.transform.toWorld)
                }
            }
        }
        val collideHull = { bodyId: Long, dims: Vector3d, hull: GamylanCageShape.Hull ->
            collide(bodyId, GamylanCageShape.boundingRadius(dims)) { move, toWorld ->
                val polygon = vsCore.entityPolygonCollider
                    .createPolygonFromPointsAndNormals(hull.points, hull.normals, toWorld, null)
                EntityShipCollisionUtils
                    .adjustEntityMovementForPrimitiveShapePolygons(entity, move, entityBoundingBox, listOf(polygon))
            }
        }
        if (level.isClientSide) {
            // The server table is empty on the client of a dedicated server. The client entry has
            // no Tribulla flag, so a soder bottle collides as the crystal of the same size.
            org.shipwrights.enderkinesis.client.GamylanCageClientRegistry.forEachIn(level.dimension()) { entry ->
                collideHull(entry.bodyId, entry.shapeDims, entry.hull)
            }
        } else {
            GamylanCageRegistry.forEachInVsDim(vsDim) { data ->
                if (data.isTribulla) {
                    collide(data.bodyId, SoderShape.boundingRadius(data.entityCullSize)) { move, toWorld ->
                        EntityShipCollisionUtils.adjustEntityMovementForPrimitiveShapeCollision(
                            entity, move, entityBoundingBox, SoderShape.newCompoundShape(data.entityCullSize), toWorld,
                        )
                    }
                } else {
                    collideHull(data.bodyId, data.shapeDims, data.hull)
                }
            }
        }
        return adjusted
    }
}
