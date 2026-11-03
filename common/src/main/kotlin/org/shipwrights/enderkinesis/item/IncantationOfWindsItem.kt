package org.shipwrights.enderkinesis.item

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile
import net.minecraft.world.entity.projectile.Projectile
import org.joml.Vector3d
import org.shipwrights.enderkinesis.compat.create.CreateWindmillWind
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/** Incantation of Winds: hold right-click to blast a gale along a cone from the caster's view.
 *
 *  Ships and entities in the cone get a push along the ray from the eye through their contact point,
 *  weaker with distance; on ships it is applied at that point. The wind also turns fireballs, blows smoke off
 *  fire (and sometimes puts it out), puts out candles, strips snow ([WindBlockEffects]) and speeds up Create windmills. The stream visuals are client-side, in
 *  [org.shipwrights.enderkinesis.client.IncantationOfWindsClient]. */
class IncantationOfWindsItem(properties: Properties) : IncantationItem(
    properties,
    HAND_COLOUR,
    EKSounds.INCANTATION_OF_WINDS_START,
    EKSounds.INCANTATION_OF_WINDS_STOP,
    EKSounds.INCANTATION_OF_WINDS_LOOP,
) {

    override fun cast(level: ServerLevel, player: Player) {
        val look = player.lookAngle
        val eye = player.eyePosition
        val radius = RANGE * TAN_HALF_ANGLE
        val bounds = AABB(eye, eye.add(look.scale(RANGE))).inflate(radius)
        pushShips(level, bounds, eye, look)
        pushEntities(level, player, bounds, eye, look)
        WindBlockEffects.blow(level, player, eye, look)
    }

    private fun pushShips(
        level: ServerLevel,
        bounds: AABB,
        eye: net.minecraft.world.phys.Vec3,
        look: net.minecraft.world.phys.Vec3,
    ) {
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        val dim = level.dimensionId
        for (ship in level.shipObjectWorld.loadedShips) {
            val srv = ship as? LoadedServerShip ?: continue
            if (srv.chunkClaimDimension != dim) continue
            val sa = srv.worldAABB
            if (sa.maxX() < bounds.minX || sa.minX() > bounds.maxX) continue
            if (sa.maxY() < bounds.minY || sa.minY() > bounds.maxY) continue
            if (sa.maxZ() < bounds.minZ || sa.minZ() > bounds.maxZ) continue
            val contact = coneContact(eye, look, sa.minX(), sa.minY(), sa.minZ(), sa.maxX(), sa.maxY(), sa.maxZ())
                ?: continue
            val wind = windAt(eye, look, contact)
            val mag = (PUSH_FORCE_PER_MASS * srv.inertiaData.mass.coerceAtLeast(1.0)).coerceAtLeast(PUSH_MIN_FORCE)
            gtpa.applyWorldForce(srv.id, wind.mul(mag), contact)
        }
    }

    private fun pushEntities(
        level: ServerLevel,
        player: Player,
        bounds: AABB,
        eye: net.minecraft.world.phys.Vec3,
        look: net.minecraft.world.phys.Vec3,
    ) {
        val entities = level.getEntities(player, bounds) { e ->
            e.isAlive && !e.isSpectator && e !== player.vehicle
        }
        for (entity in entities) {
            val b = entity.boundingBox
            val contact = coneContact(eye, look, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ) ?: continue
            val wind = windAt(eye, look, contact)
            if (entity.isOnFire) entity.clearFire()
            when (entity) {
                is AbstractHurtingProjectile -> turnFireball(entity, player, wind)
                is Projectile -> entity.deltaMovement = entity.deltaMovement.add(
                    wind.x * PROJECTILE_PUSH_SPEED, wind.y * PROJECTILE_PUSH_SPEED, wind.z * PROJECTILE_PUSH_SPEED,
                )
                else -> entity.deltaMovement = entity.deltaMovement.add(
                    wind.x * ENTITY_PUSH_SPEED, wind.y * ENTITY_PUSH_SPEED, wind.z * ENTITY_PUSH_SPEED,
                )
            }
            if (CREATE_LOADED) CreateWindmillWind.blow(entity, wind.length().toFloat(), level.gameTime)
            markPushed(entity)
        }
    }

    /** Same as a melee hit on a fireball in vanilla: it takes the new heading and the caster as owner. */
    private fun turnFireball(fireball: AbstractHurtingProjectile, player: Player, wind: Vector3d) {
        val len = wind.length()
        if (len < 1.0e-6) return
        val dx = wind.x / len
        val dy = wind.y / len
        val dz = wind.z / len
        fireball.deltaMovement = net.minecraft.world.phys.Vec3(dx, dy, dz)
        fireball.xPower = dx * FIREBALL_POWER
        fireball.yPower = dy * FIREBALL_POWER
        fireball.zPower = dz * FIREBALL_POWER
        fireball.owner = player
    }

    /** Wind at a contact point: a unit vector along the ray from the apex through the point, scaled by the
     *  distance falloff. */
    private fun windAt(
        apex: net.minecraft.world.phys.Vec3,
        axis: net.minecraft.world.phys.Vec3,
        contact: Vector3d,
    ): Vector3d {
        val ray = Vector3d(contact.x - apex.x, contact.y - apex.y, contact.z - apex.z)
        val along = ray.x * axis.x + ray.y * axis.y + ray.z * axis.z
        val falloff = 1.0 - (along / RANGE).coerceIn(0.0, 1.0) * (1.0 - FAR_FORCE_FRACTION)
        val dist = ray.length()
        if (dist < MIN_RAY_LENGTH) ray.set(axis.x, axis.y, axis.z) else ray.div(dist)
        return ray.mul(falloff)
    }

    /** Approximate cone-vs-box test: the box point nearest to the cone axis must lie inside the cone.
     *  Returns that point in world space, or null on a miss. Big ships that only graze the cone edge
     *  with a corner can miss; that is acceptable. */
    @Suppress("LongParameterList") // Raw bounds: no box allocation for each target.
    private fun coneContact(
        apex: net.minecraft.world.phys.Vec3,
        axis: net.minecraft.world.phys.Vec3,
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double,
    ): Vector3d? {
        val cx = (minX + maxX) * 0.5 - apex.x
        val cy = (minY + maxY) * 0.5 - apex.y
        val cz = (minZ + maxZ) * 0.5 - apex.z
        val t = (cx * axis.x + cy * axis.y + cz * axis.z).coerceIn(0.0, RANGE)
        val qx = (apex.x + axis.x * t).coerceIn(minX, maxX) - apex.x
        val qy = (apex.y + axis.y * t).coerceIn(minY, maxY) - apex.y
        val qz = (apex.z + axis.z * t).coerceIn(minZ, maxZ) - apex.z
        val along = qx * axis.x + qy * axis.y + qz * axis.z
        if (along < 0.0 || along > RANGE) return null
        val lateralSq = qx * qx + qy * qy + qz * qz - along * along
        val coneRadius = along * TAN_HALF_ANGLE + CONE_APEX_RADIUS
        if (lateralSq > coneRadius * coneRadius) return null
        return Vector3d(apex.x + qx, apex.y + qy, apex.z + qz)
    }

    companion object {
        /** Cone reach along the view, blocks. The client stream reads it too. */
        const val RANGE = 24.0

        private const val HALF_ANGLE_DEG = 25.0
        val TAN_HALF_ANGLE = kotlin.math.tan(Math.toRadians(HALF_ANGLE_DEG))

        /** Gives the cone a rounded tip so entities that touch the caster still catch the wind. */
        private const val CONE_APEX_RADIUS = 1.0

        private val CREATE_LOADED = dev.architectury.platform.Platform.isModLoaded("create")

        /** Blocks/tick of acceleration for projectiles, before the distance falloff; strong enough to bend an arrow. */
        private const val PROJECTILE_PUSH_SPEED = 0.25

        /** Vanilla's fireball acceleration after a melee hit. */
        private const val FIREBALL_POWER = 0.1

        /** Blocks/tick of acceleration for entities caught in the wind, before the distance falloff. */
        private const val ENTITY_PUSH_SPEED = 0.06

        /** N per kg of ship mass — mass-relative so every ship feels the
         *  same shove (~0.8 m/s² of acceleration while held). Same scale
         *  family as the Aegis ship push (6 N/kg). */
        private const val PUSH_FORCE_PER_MASS = 8.0

        /** Floor so near-massless ships still visibly move. */
        private const val PUSH_MIN_FORCE = 1_000.0

        /** Share of the full force left at [RANGE]; the force falls off linearly from the apex. */
        private const val FAR_FORCE_FRACTION = 0.25

        /** Below this apex-to-contact distance the ray direction is unstable, so the look vector is used. */
        private const val MIN_RAY_LENGTH = 0.1

        private const val HAND_COLOUR = 0xFFEB8C
    }
}
