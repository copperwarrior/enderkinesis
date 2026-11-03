package org.shipwrights.enderkinesis.possession

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.mod.common.toWorldCoordinates
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/** Path-node walking that the client walk and the server's fake-player walk share, so both reach nodes the same way. */
object PathFollower {

    private const val MAX_NODE_DY = 1.0

    /** Where [entity] stands on [node], in the world frame. A node on a ship is in shipyard coordinates. */
    fun point(entity: Entity, node: BlockPos): Vec3 =
        entity.level().toWorldCoordinates(Vec3(node.x + 0.5, node.y.toDouble(), node.z + 0.5))

    /** Index of the first node in [nodes] that [entity] has not reached, from [index] on. */
    fun advance(entity: Entity, nodes: List<BlockPos>, index: Int): Int {
        var i = index.coerceIn(0, nodes.size)
        while (i < nodes.size && reached(entity, point(entity, nodes[i]))) i++
        return i
    }

    /** The node nearest [entity], for a path that arrives while the body is part way along it. */
    fun nearest(entity: Entity, nodes: List<BlockPos>): Int {
        var best = 0
        var bestSq = Double.MAX_VALUE
        for (i in nodes.indices) {
            val distSq = entity.distanceToSqr(point(entity, nodes[i]))
            if (distSq < bestSq) {
                best = i
                bestSq = distSq
            }
        }
        return best
    }

    // Same reach rule as PathNavigation.followThePath.
    private fun reached(entity: Entity, node: Vec3): Boolean {
        val width = entity.bbWidth
        val reach = if (width > 0.75f) width / 2.0 else 0.75 - width / 2.0
        val dx = node.x - entity.x
        val dz = node.z - entity.z
        return dx * dx + dz * dz < reach * reach && Math.abs(node.y - entity.y) < MAX_NODE_DY
    }

    fun yawTo(entity: Entity, x: Double, z: Double): Float =
        (Mth.atan2(z - entity.z, x - entity.x) * Mth.RAD_TO_DEG).toFloat() - 90f

    fun pitchTo(entity: Entity, point: Vec3): Float {
        val dx = point.x - entity.x
        val dz = point.z - entity.z
        return (-(Mth.atan2(point.y - entity.eyeY, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG)).toFloat()
    }

    /** Same step-up rule as MoveControl, plus a jump when a wall stops the body. */
    fun needsJump(entity: LivingEntity, node: Vec3): Boolean {
        val dx = node.x - entity.x
        val dz = node.z - entity.z
        val stepUp = node.y - entity.y > entity.maxUpStep() && dx * dx + dz * dz < 1.0
        return entity.onGround() && (stepUp || entity.horizontalCollision)
    }

    /** Snaps a ghost pathfinder onto [body]. `PathNavigation` does nothing unless the mob is grounded. */
    fun syncGhost(ghost: Mob, body: LivingEntity) {
        ghost.moveTo(body.x, body.y, body.z, body.yRot, 0f)
        ghost.setOnGround(true)
        ghost.deltaMovement = Vec3.ZERO
        // VS2 selects the ship frame for a pathfind from this state. The ghost never
        // ticks in the world, so it must get the state from the body.
        val from = (body as? IEntityDraggingInformationProvider)?.draggingInformation ?: return
        val to = (ghost as? IEntityDraggingInformationProvider)?.draggingInformation ?: return
        to.lastShipStoodOn = if (from.isEntityBeingDraggedByAShip()) from.lastShipStoodOn else null
        to.ticksSinceStoodOnShip = from.ticksSinceStoodOnShip
    }
}
