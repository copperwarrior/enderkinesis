package org.shipwrights.enderkinesis.spell

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBd
import org.shipwrights.enderkinesis.physics.HeldPushes
import org.shipwrights.enderkinesis.util.containsWorldPoint
import org.shipwrights.enderkinesis.util.standaloneBodies
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The push of a vent jet. Each parcel that hits a ship wall, a standalone body or an entity gives its normal momentum.
 * Impulses add up for each emitter and target in one game tick, and [flush] holds one force for each pair.
 */
object GasJetImpacts : JetPush {
    private const val TICK_SECONDS = 0.05
    private const val TICKS_PER_SECOND = 20.0
    // Server thread only.
    private val box = AABBd()
    private val point = Vector3d()
    private val centre = Vector3d()
    private val force = Vector3d()
    private val at = Vector3d()
    private val motion = Vector3d()

    private data class Source(val emitter: Long, val kind: GasKind, val hand: Boolean)

    override fun wall(e: GasJetEmitter, bodyId: Long, x: Double, y: Double, z: Double, jx: Double, jy: Double, jz: Double) {
        val size = sqrt(jx * jx + jy * jy + jz * jz)
        if (size <= 0.0 || !size.isFinite()) return
        val sum = e.pushSums.get(bodyId) ?: DoubleArray(SUM_SIZE).also { e.pushSums.put(bodyId, it) }
        sum[0] += jx; sum[1] += jy; sum[2] += jz
        sum[3] += x * size; sum[4] += y * size; sum[5] += z * size; sum[6] += size
    }

    @OptIn(GameTickOnly::class, VsBeta::class)
    @Suppress("LongParameterList") // Same as JetPush.segment.
    override fun segment(level: Level, e: GasJetEmitter, parcels: GasJetParcels, i: Int, from: Vector3d, to: Vector3d, v: Vector3dc): Boolean {
        if (level !is ServerLevel) return false
        val pad = parcels.r[i] + 0.1
        box.setMin(min(from.x, to.x) - pad, min(from.y, to.y) - pad, min(from.z, to.z) - pad)
            .setMax(max(from.x, to.x) + pad, max(from.y, to.y) + pad, max(from.z, to.z) + pad)
        val mass = parcels.push[i].toDouble()
        for (body in level.standaloneBodies(box)) {
            if (!body.containsWorldPoint(to.x, to.y, to.z, point)) continue
            // The normal runs from the middle of the body to the hit point; exact for a sphere.
            val n = centre.set(to).sub(body.kinematics.position)
            if (n.lengthSquared() <= 1.0e-12) continue
            n.normalize()
            val u = body.kinematics.velocity
            val along = (v.x() - u.x() / TICKS_PER_SECOND) * n.x +
                (v.y() - u.y() / TICKS_PER_SECOND) * n.y + (v.z() - u.z() / TICKS_PER_SECOND) * n.z
            if (along >= 0.0) continue
            val j = along * mass * TICKS_PER_SECOND
            wall(e, body.id, to.x, to.y, to.z, n.x * j, n.y * j, n.z * j)
            return true
        }
        val reach = AABB(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
        for (entity in level.getEntities(null as Entity?, reach) { it.isAlive && !it.isSpectator && !(it is Player && it.abilities.flying) }) {
            if (!entity.boundingBox.inflate(pad).clip(Vec3(from.x, from.y, from.z), Vec3(to.x, to.y, to.z)).isPresent) continue
            val n = centre.set(to.x, to.y, to.z).sub(entity.x, entity.y + entity.bbHeight * 0.5, entity.z)
            if (n.lengthSquared() <= 1.0e-12) continue
            n.normalize()
            // Relative to the entity, so it never goes faster than the jet.
            val u = motionOf(entity)
            val along = (v.x() - u.x) * n.x + (v.y() - u.y) * n.y + (v.z() - u.z) * n.z
            if (along >= 0.0) continue
            val j = -along * mass * TICKS_PER_SECOND
            applyEntityPush(entity, -n.x * j, -n.y * j, -n.z * j)
            return true
        }
        return false
    }

    /** Holds one force for each target body that this emitter hit in the last tick, then clears the sums. */
    fun flush(e: GasJetEmitter) {
        if (e.pushSums.isEmpty()) return
        val it = e.pushSums.long2ObjectEntrySet().fastIterator()
        while (it.hasNext()) {
            val entry = it.next()
            val sum = entry.value
            if (sum[6] > 0.0) {
                force.set(sum[0], sum[1], sum[2]).div(TICK_SECONDS)
                at.set(sum[3] / sum[6], sum[4] / sum[6], sum[5] / sum[6])
                if (force.isFinite && at.isFinite) HeldPushes.holdAtWorldPos(Source(e.id, e.kind, e.hand), entry.longKey, force, at)
            }
            sum.fill(0.0)
        }
    }

    /** World motion of [entity] in blocks a tick. */
    private fun motionOf(entity: Entity): Vector3d {
        // A server player's motion comes from the client, so its deltaMovement goes stale; the position change is real.
        if (entity is Player) return motion.set(entity.x - entity.xo, entity.y - entity.yo, entity.z - entity.zo)
        val m = entity.deltaMovement
        motion.set(m.x, m.y, m.z)
        // VS2 carries a mob on a ship by position, so deltaMovement leaves out the ship motion.
        val info = (entity as? IEntityDraggingInformationProvider)?.draggingInformation
        if (info != null && info.isEntityBeingDraggedByAShip()) motion.add(info.addedMovementLastTick)
        return motion
    }

    /** The mass of an entity for a push: [ENTITY_DENSITY] times its hitbox volume, never under [MIN_ENTITY_MASS]. */
    private fun entityMass(entity: Entity): Double {
        val box = entity.boundingBox
        return max(MIN_ENTITY_MASS, ENTITY_DENSITY * box.xsize * box.ysize * box.zsize)
    }

    private fun applyEntityPush(entity: Entity, jx: Double, jy: Double, jz: Double) {
        val dv = 1.0 / (entityMass(entity) * TICKS_PER_SECOND)
        entity.push(jx * dv, jy * dv, jz * dv)
        entity.hurtMarked = true
    }

    // kg per cubic metre of hitbox: a player is about 78 kg.
    private const val ENTITY_DENSITY = 120.0
    private const val MIN_ENTITY_MASS = 10.0
    private const val SUM_SIZE = 7
}
