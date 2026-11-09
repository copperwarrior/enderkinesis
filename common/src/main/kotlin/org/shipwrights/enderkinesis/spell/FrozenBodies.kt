package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.RelativeMovement
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.util.deleteStandaloneBody
import org.valkyrienskies.core.api.bodies.VsBodyCreateData
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.hooks.VSGameEvents
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore
import java.util.EnumSet
import java.util.IdentityHashMap
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.VsBeta

/** The client moves a player, so a frozen player follows its body by teleport.
 *  A frozen entity that is also Absent stays where it vanished. */
@OptIn(GameTickOnly::class, VsBeta::class)
object FrozenBodies {

    private class Frozen(val bodyId: Long, initial: Quaterniond) {
        val initialInverse: Quaterniond = Quaterniond(initial).invert()
        // The tumble last sent; NaN until the first send.
        val sent = FloatArray(4) { Float.NaN }
        var sentChange = true
        val viewers = HashSet<ServerPlayer>()
    }

    // Each pose batch holds the frozen poses one player gets this tick. Game thread only.
    private class PoseBatch {
        var ids = IntArray(4)
        var poses = FloatArray(16)
        var count = 0

        fun add(id: Int, pose: FloatArray) {
            if (count == ids.size) {
                ids = ids.copyOf(count * 2)
                poses = poses.copyOf(count * 8)
            }
            ids[count] = id
            pose.copyInto(poses, count * 4)
            count++
        }
    }

    // Game thread only.
    private val frozen = IdentityHashMap<LivingEntity, Frozen>()
    private val levelMobs = ArrayList<LivingEntity>()
    private val batches = HashMap<ServerPlayer, PoseBatch>()
    private val freeBatches = ArrayDeque<PoseBatch>()
    private val tumble = Quaterniond()
    private val pose = FloatArray(4)

    // Below this change in the tumble's dot product (about 0.05°) the pose is not sent again.
    private const val POSE_EPSILON = 1.0e-7

    // kg per cubic block of hitbox, near the density of ice.
    private const val DENSITY = 900.0
    private const val MIN_MASS = 10.0
    private const val PLAYER_SLACK = 0.01

    fun init() {
        // After VS2 sets this tick's body poses and before it drags entities with ships.
        VSGameEvents.shipPositionsUpdated.on { level, _ -> follow(level) }
        LifecycleEvent.SERVER_STOPPING.register { dropAll() }
    }

    fun freeze(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        SpellHolds.hold(entity, SpellHolds.By.FROZEN)
        if (entity in frozen) return
        // JOML +Y turns CCW and MC yaw turns CW, so negate the yaw.
        val initial = Quaterniond().rotateY(-Math.toRadians(entity.yBodyRot.toDouble()))
        val bodyId = createBody(level, entity, initial) ?: return
        frozen[entity] = Frozen(bodyId, initial)
    }

    fun thaw(entity: LivingEntity) {
        SpellHolds.release(entity, SpellHolds.By.FROZEN)
        val mob = entity
        val state = frozen.remove(mob) ?: return
        val level = mob.level() as? ServerLevel ?: return
        val body = level.shipObjectWorld.allBodies.getById(state.bodyId)
        if (body != null && !Absence.isAbsent(mob)) {
            val v = body.kinematics.velocity
            mob.deltaMovement = Vec3(v.x() / 20.0, v.y() / 20.0, v.z() / 20.0)
            mob.hurtMarked = true
        }
        level.deleteStandaloneBody(state.bodyId)
    }

    private fun createBody(level: ServerLevel, mob: LivingEntity, initial: Quaterniond): Long? {
        val shipWorld = level.shipObjectWorld as? ServerShipWorld ?: return null
        val w = mob.bbWidth.toDouble()
        val h = mob.bbHeight.toDouble()
        val mass = (DENSITY * w * w * h).coerceAtLeast(MIN_MASS)
        // Box moment of inertia: m(a² + b²)/12 about each axis.
        val ixz = mass * (w * w + h * h) / 12.0
        val iy = mass * (2 * w * w) / 12.0
        val velocity = mob.deltaMovement.scale(20.0)
        val centre = mob.position().add(0.0, h / 2.0, 0.0)
        val body = shipWorld.createBody(
            VsBodyCreateData(
                dimensionId = level.dimensionId,
                inertiaData = vsCore.newShipInertiaData(Vector3d(), mass, Matrix3d().scaling(ixz, iy, ixz)),
                kinematics = vsCore.newBodyKinematics(
                    Vector3d(velocity.x, velocity.y, velocity.z), Vector3d(),
                    Vector3d(centre.x, centre.y, centre.z),
                    Quaterniond(initial),
                    Vector3d(1.0), Vector3d(),
                ),
                collisionShape = vsCore.newBoxBodyShape(Vector3d(w, h, w)),
                isStatic = false,
                collisionMask = -1,
                staticFrictionCoefficient = 0.05,
                dynamicFrictionCoefficient = 0.02,
                restitutionCoefficient = 0.1,
            ),
        )
        return body.id
    }

    // A dead or unloaded entity loses its body but keeps its SpellHolds hold.
    // An entity that lost the effect without a removal call (milk, `/effect clear`) thaws here.
    private fun follow(level: ServerLevel) {
        if (frozen.isEmpty()) return
        levelMobs.clear()
        for (mob in frozen.keys) if (mob.level() === level) levelMobs.add(mob)
        for (mob in levelMobs) {
            val state = frozen[mob] ?: continue
            if (mob.isRemoved || !mob.isAlive) {
                frozen.remove(mob)
                level.deleteStandaloneBody(state.bodyId)
                continue
            }
            if (!mob.hasEffect(EKEffects.FROZEN.get())) {
                thaw(mob)
                continue
            }
            if (Absence.isAbsent(mob)) continue
            val body = level.shipObjectWorld.allBodies.getById(state.bodyId) ?: continue
            val p = body.kinematics.position
            val y = p.y() - mob.bbHeight / 2.0
            if (mob is ServerPlayer) {
                if (mob.position().distanceToSqr(p.x(), y, p.z()) > PLAYER_SLACK * PLAYER_SLACK) {
                    mob.connection.teleport(p.x(), y, p.z(), 0f, 0f, EnumSet.of(RelativeMovement.X_ROT, RelativeMovement.Y_ROT))
                }
            } else mob.teleportTo(p.x(), y, p.z())
            mob.deltaMovement = Vec3.ZERO
            // Send only the rotation since the freeze, because the mob keeps the facing it froze with.
            tumble.set(body.kinematics.rotation).mul(state.initialInverse)
            queuePose(level, mob, state)
        }
        flushPoses()
    }

    /** Queues the tumble for the viewers that need it: all of them when it changed, and new viewers always. */
    private fun queuePose(level: ServerLevel, mob: LivingEntity, state: Frozen) {
        pose[0] = tumble.x.toFloat(); pose[1] = tumble.y.toFloat(); pose[2] = tumble.z.toFloat(); pose[3] = tumble.w.toFloat()
        val sent = state.sent
        // Float components of a unit quaternion have a norm error near 1e-7, so normalise the dot in Double.
        var dot = 0.0
        var poseSq = 0.0
        var sentSq = 0.0
        for (i in 0..3) {
            val p = pose[i].toDouble()
            val q = sent[i].toDouble()
            dot += p * q
            poseSq += p * p
            sentSq += q * q
        }
        val cosHalf = Math.abs(dot) / Math.sqrt(poseSq * sentSq)
        val changed = cosHalf.isNaN() || cosHalf < 1.0 - POSE_EPSILON
        // The client draws between its last two poses, so one more send after a change makes them equal at rest.
        val sendAll = changed || state.sentChange
        state.sentChange = changed
        if (sendAll) pose.copyInto(sent)
        val viewers = SpellNetwork.frozenPoseViewers(level, mob)
        for (viewer in viewers) {
            if (!sendAll && viewer in state.viewers) continue
            batches.getOrPut(viewer) { freeBatches.removeFirstOrNull() ?: PoseBatch() }.add(mob.id, if (sendAll) pose else sent)
        }
        state.viewers.clear()
        state.viewers.addAll(viewers)
    }

    private fun flushPoses() {
        if (batches.isEmpty()) return
        for ((player, batch) in batches) {
            SpellNetwork.sendFrozenPoses(player, batch.ids, batch.poses, batch.count)
            batch.count = 0
            freeBatches.addLast(batch)
        }
        batches.clear()
    }

    // Each entity keeps its hold, so it freezes again when it next loads.
    private fun dropAll() {
        for ((mob, state) in frozen) (mob.level() as? ServerLevel)?.deleteStandaloneBody(state.bodyId)
        frozen.clear()
    }
}
