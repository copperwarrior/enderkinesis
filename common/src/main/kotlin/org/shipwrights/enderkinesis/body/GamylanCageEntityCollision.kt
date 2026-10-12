package org.shipwrights.enderkinesis.body

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils

/**
 * Entity-vs-cage collision adjustment for Wand-of-Ga'Mylan cages.
 * Mirrors the structure of [OrbEntityCollision]: per-cage compound
 * hex-bipyramid shape (see [GamylanCageShape]) is fed into
 * [EntityShipCollisionUtils.adjustEntityMovementForPrimitiveShapeCollision],
 * which traces the entity's swept AABB against each primitive child of
 * the compound and clamps the movement.
 *
 * Called from [org.shipwrights.enderkinesis.mixin.EntityOrbCollisionMixin]
 * after the orb adjustment pass, on the same wrapped collide step.
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
        val collide = { bodyId: Long, bodyRadius: Double, shape: () -> org.valkyrienskies.core.api.bodies.shape.BodyShapeData ->
            val body = shipWorld.allBodies.getById(bodyId)
            if (body != null) {
                val bodyPos = body.kinematics.position
                val cullR = halfExt + bodyRadius + moveLen + 0.5
                val dx = bodyPos.x() - cx
                val dy = bodyPos.y() - cy
                val dz = bodyPos.z() - cz
                if (dx * dx + dy * dy + dz * dz <= cullR * cullR) {
                    adjusted = EntityShipCollisionUtils.adjustEntityMovementForPrimitiveShapeCollision(
                        entity, adjusted, entityBoundingBox, shape(), body.kinematics.transform.toWorld,
                    )
                }
            }
        }
        if (level.isClientSide) {
            // The server table is empty on the client of a dedicated server. The client entry has
            // no Tribulla flag, so a soder bottle collides as the crystal of the same size.
            org.shipwrights.enderkinesis.client.GamylanCageClientRegistry.forEachIn(level.dimension()) { entry ->
                collide(entry.bodyId, GamylanCageShape.boundingRadius(entry.shapeDims)) {
                    GamylanCageShape.newCompoundShape(entry.shapeDims)
                }
            }
        } else {
            GamylanCageRegistry.forEachInVsDim(vsDim) { data ->
                // Bounding-sphere broad-phase: largest extent of the bipyramid (or soder
                // bottle, in Tribulla cages) from its centre.
                val bodyRadius = if (data.isTribulla) SoderShape.boundingRadius(data.entityCullSize)
                else GamylanCageShape.boundingRadius(data.shapeDims)
                collide(data.bodyId, bodyRadius) {
                    if (data.isTribulla) SoderShape.newCompoundShape(data.entityCullSize)
                    else GamylanCageShape.newCompoundShape(data.shapeDims)
                }
            }
        }
        return adjusted
    }
}
