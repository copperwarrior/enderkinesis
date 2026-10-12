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
 *  Orb positions are snapshotted once per level per tick, so a move far from every orb costs
 *  a few distance checks and no VS2 body lookups. Orbs that pass the cull go through VS2's
 *  [EntityShipCollisionUtils.adjustEntityMovementForPrimitiveShapeCollision] against their
 *  live transform. */
object OrbEntityCollision {

    private const val COLLISION_EPSILON: Double = 1.0e-12
    private const val SPHERE_RADIUS: Double = 1.5
    private val SPHERE_SHAPE = vsCore.newSphereBodyShape(SPHERE_RADIUS)
    private const val TICKS_PER_SECOND: Double = 20.0
    private const val INITIAL_CAPACITY = 16

    /** Orb positions and per-tick reach for one level and game tick. */
    private class OrbSnapshot {
        var levelIdentity = 0
        var gameTime = Long.MIN_VALUE
        var count = 0
        var ids = LongArray(INITIAL_CAPACITY)
        var x = DoubleArray(INITIAL_CAPACITY)
        var y = DoubleArray(INITIAL_CAPACITY)
        var z = DoubleArray(INITIAL_CAPACITY)
        var reach = DoubleArray(INITIAL_CAPACITY)

        fun refresh(level: Level) {
            levelIdentity = System.identityHashCode(level)
            gameTime = level.gameTime
            count = 0
            val shipWorld = level.shipObjectWorld
            // The server table is empty on the client of a dedicated server; the client has
            // its own table for its dimension, sent by the server.
            // Only the orbs near a player: the table holds each orb that was ever found.
            val near = if (level is net.minecraft.server.level.ServerLevel) OrbBodyRegistry.near(level)
            else if (level.isClientSide) ClientOrbRegistry.near(level)
            else return
            for (bodyId in near) add(shipWorld, bodyId)
        }

        private fun add(shipWorld: org.valkyrienskies.core.api.world.ShipWorld, bodyId: Long) {
            val kinematics = shipWorld.allBodies.getById(bodyId)?.kinematics ?: return
            if (count == ids.size) grow()
            val pos = kinematics.position
            val vel = kinematics.velocity
            ids[count] = bodyId
            x[count] = pos.x()
            y[count] = pos.y()
            z[count] = pos.z()
            // The snapshot is up to one tick old; widen the cull by the distance the orb can cover.
            reach[count] = vel.length() / TICKS_PER_SECOND
            count++
        }

        private fun grow() {
            val size = ids.size * 2
            ids = ids.copyOf(size)
            x = x.copyOf(size)
            y = y.copyOf(size)
            z = z.copyOf(size)
            reach = reach.copyOf(size)
        }
    }

    // Entity.move runs on both the server and client threads.
    private val snapshots: ThreadLocal<OrbSnapshot> = ThreadLocal.withInitial { OrbSnapshot() }

    /** False when this side has no orb at all, so `Entity.move` can skip the collision pass. */
    @JvmStatic
    fun mayCollide(level: Level): Boolean =
        if (level.isClientSide) !ClientOrbRegistry.isEmpty() else !OrbBodyRegistry.hasNone()

    @JvmStatic
    fun adjustMovement(
        entity: Entity,
        movement: Vec3,
        entityBoundingBox: AABB,
        level: Level,
    ): Vec3 {
        if (movement.lengthSqr() <= COLLISION_EPSILON) return movement
        val snapshot = snapshots.get()
        if (snapshot.gameTime != level.gameTime || snapshot.levelIdentity != System.identityHashCode(level)) {
            snapshot.refresh(level)
        }
        if (snapshot.count == 0) return movement

        // Broad-phase radius around the swept entity AABB; the VS2 polygon build inside the
        // primitive-shape collision is the expensive part, and most (entity, orb) pairs are far apart.
        val cx = (entityBoundingBox.minX + entityBoundingBox.maxX) * 0.5
        val cy = (entityBoundingBox.minY + entityBoundingBox.maxY) * 0.5
        val cz = (entityBoundingBox.minZ + entityBoundingBox.maxZ) * 0.5
        val halfX = (entityBoundingBox.maxX - entityBoundingBox.minX) * 0.5
        val halfY = (entityBoundingBox.maxY - entityBoundingBox.minY) * 0.5
        val halfZ = (entityBoundingBox.maxZ - entityBoundingBox.minZ) * 0.5
        val halfExt = maxOf(halfX, halfY, halfZ)
        val moveLen = Math.sqrt(movement.lengthSqr())
        val baseCullR = halfExt + SPHERE_RADIUS + moveLen + 0.5  // 0.5 = small safety margin

        var adjusted = movement
        val shipWorld = level.shipObjectWorld
        for (i in 0 until snapshot.count) {
            val cullR = baseCullR + snapshot.reach[i]
            val dx = snapshot.x[i] - cx
            val dy = snapshot.y[i] - cy
            val dz = snapshot.z[i] - cz
            if (dx * dx + dy * dy + dz * dz > cullR * cullR) continue
            val body = shipWorld.allBodies.getById(snapshot.ids[i]) ?: continue
            adjusted = EntityShipCollisionUtils.adjustEntityMovementForPrimitiveShapeCollision(
                entity, adjusted, entityBoundingBox, SPHERE_SHAPE, body.kinematics.transform.toWorld,
            )
        }
        return adjusted
    }
}
