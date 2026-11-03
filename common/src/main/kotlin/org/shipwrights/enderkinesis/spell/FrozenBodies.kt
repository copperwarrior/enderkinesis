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
import org.shipwrights.enderkinesis.util.VsBodies
import org.valkyrienskies.core.api.bodies.VsBodyCreateData
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.hooks.VSGameEvents
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore
import java.util.EnumSet
import java.util.IdentityHashMap

/** The client moves a player, so a frozen player follows its body by teleport.
 *  A frozen entity that is also Absent stays where it vanished. */
object FrozenBodies {

    private class Frozen(val bodyId: Long, val initial: Quaterniond)

    // Game thread only.
    private val frozen = IdentityHashMap<LivingEntity, Frozen>()

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
        VsBodies.delete(level, state.bodyId)
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
        VsBodies.unregisterPreemptiveStandaloneBody(shipWorld, body.id)
        return body.id
    }

    // A dead or unloaded entity loses its body but keeps its SpellHolds hold.
    // An entity that lost the effect without a removal call (milk, `/effect clear`) thaws here.
    private fun follow(level: ServerLevel) {
        if (frozen.isEmpty()) return
        for ((mob, state) in frozen.entries.filter { it.key.level() === level }.map { it.key to it.value }) {
            if (mob.isRemoved || !mob.isAlive) {
                frozen.remove(mob)
                VsBodies.delete(level, state.bodyId)
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
            val tumble = Quaterniond(body.kinematics.rotation).mul(Quaterniond(state.initial).invert())
            SpellNetwork.broadcastFrozenPose(level, mob, tumble.x.toFloat(), tumble.y.toFloat(), tumble.z.toFloat(), tumble.w.toFloat())
        }
    }

    // Each entity keeps its hold, so it freezes again when it next loads.
    private fun dropAll() {
        for ((mob, state) in frozen) (mob.level() as? ServerLevel)?.let { VsBodies.delete(it, state.bodyId) }
        frozen.clear()
    }
}
