package org.shipwrights.enderkinesis.body

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils
import org.valkyrienskies.mod.common.vsCore

/**
 * Skyriders-style helper that adjusts an entity's intended movement to
 * account for collisions with Orb of Potential VS bodies. Called from
 * the Mixin on `Entity.move`'s wrapped invocation of `Entity.collide`.
 *
 *  Per-tick flow inside the wrap:
 *    1. For each orb body in the entity's dimension (read from
 *       [OrbBodyRegistry]):
 *    2. Look up the live `ClientVsBody` / `ServerVsBody` to read its
 *       current `transform.toWorld`.
 *    3. Hand the (entity, movement, bbox, shape, transform) tuple to
 *       VS2's [EntityShipCollisionUtils.adjustEntityMovementForPrimitiveShapeCollision]
 *       which knows how to resolve a moving AABB against a primitive
 *       shape (here: the orb's [SPHERE_RADIUS] sphere).
 *
 *  No filtering by AABB-vs-sphere is done up-front — VS2's helper
 *  already early-exits when the swept box doesn't touch the shape, so
 *  the per-orb cost when far away is negligible. */
object OrbEntityCollision {

    private const val COLLISION_EPSILON: Double = 1.0e-12
    private const val SPHERE_RADIUS: Double = 1.5
    private val SPHERE_SHAPE = vsCore.newSphereBodyShape(SPHERE_RADIUS)

    @JvmStatic
    fun adjustMovement(
        entity: Entity,
        movement: Vec3,
        entityBoundingBox: AABB,
        level: Level,
    ): Vec3 {
        if (movement.lengthSqr() <= COLLISION_EPSILON) return movement
        val dim = level.dimensionId
        val shipWorld = level.shipObjectWorld

        // Broad-phase cull radius around the swept entity AABB. Any orb
        // whose live body position falls outside this radius can't
        // possibly clip against this move, so we skip the (expensive)
        // VS2 polygon construction inside `adjustEntityMovementForPrimitiveShapeCollision`.
        // Built once outside the loop because the entity bbox and
        // movement are loop-invariant. Spark report showed VS2's
        // `createPolygonsFromBodyShapeData` + `sphereDirections` were
        // ~17s cumulative server time at 700+ catalogers × N orbs;
        // most of those (entity, orb) pairs are nowhere near each
        // other, so the broad-phase prunes the vast majority.
        val cx = (entityBoundingBox.minX + entityBoundingBox.maxX) * 0.5
        val cy = (entityBoundingBox.minY + entityBoundingBox.maxY) * 0.5
        val cz = (entityBoundingBox.minZ + entityBoundingBox.maxZ) * 0.5
        val halfX = (entityBoundingBox.maxX - entityBoundingBox.minX) * 0.5
        val halfY = (entityBoundingBox.maxY - entityBoundingBox.minY) * 0.5
        val halfZ = (entityBoundingBox.maxZ - entityBoundingBox.minZ) * 0.5
        val halfExt = maxOf(halfX, halfY, halfZ)
        val moveLen = Math.sqrt(movement.lengthSqr())
        val cullR = halfExt + SPHERE_RADIUS + moveLen + 0.5  // 0.5 = small safety margin
        val cullRSqr = cullR * cullR

        var adjusted = movement
        OrbBodyRegistry.forEachIn(dim) { bodyId, _ ->
            val body = shipWorld.allBodies.getById(bodyId) ?: return@forEachIn
            // Broad-phase: distance from entity bbox centre to body
            // position must be ≤ cull radius. `body.kinematics.position`
            // is world coords (used the same way in OrbGravityCanceller).
            val bodyPos = body.kinematics.position
            val dx = bodyPos.x() - cx
            val dy = bodyPos.y() - cy
            val dz = bodyPos.z() - cz
            if (dx * dx + dy * dy + dz * dz > cullRSqr) return@forEachIn
            val toWorld = body.kinematics.transform.toWorld
            adjusted = EntityShipCollisionUtils.adjustEntityMovementForPrimitiveShapeCollision(
                entity, adjusted, entityBoundingBox, SPHERE_SHAPE, toWorld
            )
        }
        return adjusted
    }
}
