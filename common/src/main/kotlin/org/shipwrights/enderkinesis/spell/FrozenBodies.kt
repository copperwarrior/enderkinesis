package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.MinecraftServer
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
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore
import java.util.EnumSet
import java.util.IdentityHashMap

/**
 * Frozen mobs and players as VS2 physics bodies. Freezing gives the entity a box body the
 * size of its hitbox, launched with its own velocity; while frozen its AI and gravity are off
 * and it follows the body each tick, sliding and being knocked about like any physics object.
 * Clients turn the model by the body's rotation (see [SpellNetwork.broadcastFrozenPose]), and
 * other entities collide with it as with a boat. Thawing deletes the body and hands its
 * velocity back.
 *
 * A player's own client moves them, so a frozen player is put on the body by teleporting
 * them there each tick it moves (keeping where they look); they can't act meanwhile
 * ([SpellHolds]). A frozen entity that's also Absent stays where it vanished, out of the
 * world, until it's back.
 */
object FrozenBodies {

    /** [initial] is the body's rotation when it froze: the mob's facing. */
    private class Frozen(val bodyId: Long, val initial: Quaterniond)

    // Game thread only.
    private val frozen = IdentityHashMap<LivingEntity, Frozen>()

    /** Body density, kg per cubic block of hitbox: about ice. */
    private const val DENSITY = 900.0
    private const val MIN_MASS = 10.0
    /** A frozen player is moved onto the body once it's farther off than this. */
    private const val PLAYER_SLACK = 0.01

    fun init() {
        TickEvent.SERVER_POST.register(TickEvent.Server(::follow))
        LifecycleEvent.SERVER_STOPPING.register { dropAll() }
    }

    fun freeze(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        SpellHolds.hold(entity, SpellHolds.By.FROZEN)
        if (entity in frozen) return
        // The facing its model is drawn with, which it keeps while frozen.
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
                // Ice: slippery.
                staticFrictionCoefficient = 0.05,
                dynamicFrictionCoefficient = 0.02,
                restitutionCoefficient = 0.1,
            ),
        )
        VsBodies.unregisterPreemptiveStandaloneBody(shipWorld, body.id)
        return body.id
    }

    /** Moves each frozen entity onto its body. One that died or was unloaded loses its body (its
     *  hold is kept with it, see [SpellHolds]); one that lost the effect without being told
     *  (milk, `/effect clear`) thaws. */
    private fun follow(server: MinecraftServer) {
        if (frozen.isEmpty()) return
        for ((mob, state) in frozen.entries.map { it.key to it.value }) {
            val level = mob.level() as? ServerLevel
            if (level == null || mob.isRemoved || !mob.isAlive) {
                frozen.remove(mob)
                if (level != null) VsBodies.delete(level, state.bodyId)
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
            // The mob keeps the facing it froze with; clients turn its model by the body's
            // rotation since then, so it tumbles with the ice.
            val tumble = Quaterniond(body.kinematics.rotation).mul(Quaterniond(state.initial).invert())
            SpellNetwork.broadcastFrozenPose(level, mob, tumble.x.toFloat(), tumble.y.toFloat(), tumble.z.toFloat(), tumble.w.toFloat())
        }
    }

    /** The server is stopping: every body goes, and each entity keeps its hold to be frozen again
     *  when it's next loaded. */
    private fun dropAll() {
        for ((mob, state) in frozen) (mob.level() as? ServerLevel)?.let { VsBodies.delete(it, state.bodyId) }
        frozen.clear()
    }
}
