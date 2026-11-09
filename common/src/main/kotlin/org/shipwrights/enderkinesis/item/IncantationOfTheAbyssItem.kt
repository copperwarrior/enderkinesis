package org.shipwrights.enderkinesis.item

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.joml.primitives.AABBdc
import org.shipwrights.enderkinesis.physics.HeldPushes
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.util.pickStandaloneBody
import org.shipwrights.enderkinesis.util.standaloneBodies
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.core.api.util.GameTickOnly

/** Incantation of the Abyss: hold right-click to open a sphere of deep-sea pressure where the caster looks.
 *  Mobs in it drown and get Slowness and Darkness. Ships in it sink at a steady speed. */
@OptIn(GameTickOnly::class)
class IncantationOfTheAbyssItem(properties: Properties) : IncantationItem(
    properties,
    HAND_COLOUR,
    EKSounds.INCANTATION_OF_THE_ABYSS_START,
    EKSounds.INCANTATION_OF_THE_ABYSS_STOP,
    EKSounds.INCANTATION_OF_THE_ABYSS_LOOP,
) {

    override fun cast(level: ServerLevel, player: Player) {
        val centre = focus(level, player, 1f)
        crush(level, player, centre)
        sinkShips(level, player, centre)
    }

    private fun crush(level: ServerLevel, player: Player, centre: Vec3) {
        val box = AABB.ofSize(centre, RADIUS * 2, RADIUS * 2, RADIUS * 2)
        val victims = level.getEntitiesOfClass(LivingEntity::class.java, box) { e ->
            e !== player && e.isAlive && !e.isSpectator &&
                e.boundingBox.center.distanceToSqr(centre) <= RADIUS * RADIUS
        }
        for (victim in victims) {
            refresh(victim, MobEffects.MOVEMENT_SLOWDOWN, SLOWNESS_AMPLIFIER)
            refresh(victim, MobEffects.DARKNESS, 0)
            drown(victim)
        }
    }

    /** Re-adds an effect only when it is about to end, so a held cast does not send an effect packet each tick. */
    private fun refresh(victim: LivingEntity, effect: MobEffect, amplifier: Int) {
        val current = victim.getEffect(effect)
        if (current != null && current.amplifier >= amplifier && current.duration > EFFECT_REFRESH_TICKS) return
        victim.addEffect(MobEffectInstance(effect, EFFECT_TICKS, amplifier, false, false, true))
    }

    /** Vanilla gives back 4 air each tick out of water, so the net drain is [AIR_DRAIN] - 4. */
    private fun drown(victim: LivingEntity) {
        if (victim.canBreatheUnderwater() || victim.hasEffect(MobEffects.WATER_BREATHING)) return
        if (victim is Player && victim.abilities.invulnerable) return
        var air = victim.airSupply - AIR_DRAIN
        if (air <= 0) {
            air = 0
            if (victim.tickCount % DROWN_INTERVAL_TICKS == 0) victim.hurt(victim.damageSources().drown(), DROWN_DAMAGE)
        }
        victim.airSupply = air
    }

    @OptIn(VsBeta::class)
    private fun sinkShips(level: ServerLevel, player: Player, centre: Vec3) {
        val source = this to player.uuid
        val dim = level.dimensionId
        for (ship in level.shipObjectWorld.loadedShips) {
            val srv = ship as? LoadedServerShip ?: continue
            if (srv.chunkClaimDimension != dim) continue
            val force = sinkForce(centre, srv.worldAABB, srv.velocity.y(), srv.inertiaData.mass) ?: continue
            HeldPushes.holdAtBodyPos(source, srv.id, force)
        }
        val reach = AABBd(centre.x - RADIUS, centre.y - RADIUS, centre.z - RADIUS, centre.x + RADIUS, centre.y + RADIUS, centre.z + RADIUS)
        for (body in level.standaloneBodies(reach)) {
            val force = sinkForce(centre, body.aabb, body.velocity.y(), body.inertiaData.mass) ?: continue
            HeldPushes.holdAtBodyPos(source, body.id, force)
        }
    }

    private fun sinkForce(centre: Vec3, box: AABBdc, velocityY: Double, mass: Double): Vector3d? {
        val dx = centre.x.coerceIn(box.minX(), box.maxX()) - centre.x
        val dy = centre.y.coerceIn(box.minY(), box.maxY()) - centre.y
        val dz = centre.z.coerceIn(box.minZ(), box.maxZ()) - centre.z
        if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) return null
        // Speed-limited, so the push stops at the sink speed instead of driving the body into the ground.
        val shortfall = SINK_SPEED + velocityY
        if (shortfall <= 0.0) return null
        val accel = minOf(shortfall * SINK_GAIN, SINK_MAX_ACCEL)
        return Vector3d(0.0, -accel * mass.coerceAtLeast(1.0), 0.0)
    }

    companion object {
        /** Reach of the aim, blocks. */
        const val RANGE = 24.0

        /** Sphere radius, blocks. The client volume reads it too. */
        const val RADIUS = 4.0

        private const val HAND_COLOUR = 0x7A4AD0

        private const val EFFECT_TICKS = 40
        private const val EFFECT_REFRESH_TICKS = 10
        private const val SLOWNESS_AMPLIFIER = 2

        /** Air taken each tick; with vanilla's +4 a full 300 air is gone in about 2.5 seconds. */
        private const val AIR_DRAIN = 10
        private const val DROWN_INTERVAL_TICKS = 20

        /** Vanilla drowning damage. */
        private const val DROWN_DAMAGE = 2.0f

        /** Target fall speed, blocks/s. */
        private const val SINK_SPEED = 3.0

        /** m/s² of push for each block/s the ship is short of [SINK_SPEED]. */
        private const val SINK_GAIN = 4.0

        /** About gravity, so a floating ship sinks. */
        private const val SINK_MAX_ACCEL = 10.0

        /** Shared by the server cast and the client visuals. The sphere centres on the first entity on the aim. On a block
         *  it backs off toward the caster, so it is not half buried in the wall. */
        fun focus(level: Level, player: Player, partialTick: Float): Vec3 {
            val eye = player.getEyePosition(partialTick)
            val look = player.getViewVector(partialTick)
            val far = eye.add(look.scale(RANGE))
            val hit = level.clip(ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            val blockEnd = if (hit.type == HitResult.Type.MISS) far else hit.location
            val body = level.pickStandaloneBody(eye, look, eye.distanceTo(blockEnd))
            val end = body?.let { eye.add(look.scale(it.distance)) } ?: blockEnd
            val candidates = level.getEntities(player, AABB(eye, end).inflate(1.0)) { e ->
                e.isAlive && !e.isSpectator && e !== player.vehicle
            }
            var bestSqr = eye.distanceToSqr(end)
            var entityCentre: Vec3? = null
            for (e in candidates) {
                val point = e.boundingBox.clip(eye, end).orElse(null) ?: continue
                val d = eye.distanceToSqr(point)
                if (d < bestSqr) {
                    bestSqr = d
                    entityCentre = e.boundingBox.center
                }
            }
            if (entityCentre != null) return entityCentre
            if (body == null && hit.type == HitResult.Type.MISS) return far
            return end.subtract(look.scale(minOf(RADIUS * 0.5, eye.distanceTo(end))))
        }
    }
}
