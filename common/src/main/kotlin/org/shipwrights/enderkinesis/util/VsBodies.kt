package org.shipwrights.enderkinesis.util

import com.mojang.logging.LogUtils
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBdc
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.ServerBaseVsBody
import org.valkyrienskies.core.api.bodies.VsBody
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.config.DimensionParametersResolver
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.util.DEFAULT_WORLD_GRAVITY

private val LOG = LogUtils.getLogger()

/** Deletes standalone body [bodyId] from the server-scoped ship world, if it still exists. */
@OptIn(GameTickOnly::class, VsBeta::class)
fun ServerLevel.deleteStandaloneBody(bodyId: Long) {
    val shipWorld = vsApi.getServerShipWorld(server) ?: return
    val body = shipWorld.allBodies.getById(bodyId) ?: return
    try {
        shipWorld.deleteBody(body)
    } catch (t: Throwable) {
        LOG.warn("deleteBody({}) failed", bodyId, t)
    }
}

/** Ids of the standalone VS bodies whose world AABB meets [aabb]. Ships are left out, because they have their own paths. */
@OptIn(GameTickOnly::class, VsBeta::class)
fun ServerLevel.standaloneBodyIds(aabb: AABBdc, accept: (VsBody) -> Boolean = { true }): LongArray {
    val world = shipObjectWorld
    val ids = LongArrayList()
    for (body in world.allBodies.getIntersecting(aabb, dimensionId)) {
        if (world.allShips.getById(body.id) == null && accept(body)) ids.add(body.id)
    }
    return ids.toLongArray()
}

/** The standalone VS bodies whose world AABB meets [aabb]. Ships are left out, because they have their own paths. */
@OptIn(GameTickOnly::class, VsBeta::class)
fun ServerLevel.standaloneBodies(aabb: AABBdc): List<ServerBaseVsBody> {
    val world = shipObjectWorld
    return world.allBodies.getIntersecting(aabb, dimensionId)
        .filter { world.allShips.getById(it.id) == null }
        .filterIsInstance<ServerBaseVsBody>()
}

/** Gravity magnitude (m/s²) of this dimension, the value VS2 applies to ships and bodies. */
@OptIn(PhysTickOnly::class, VsBeta::class)
fun PhysLevel.gravity(): Double = aerodynamicUtils.getAtmosphereForDimension(dimension).third

/**
 * The same gravity magnitude (m/s²) on the game thread or a client. A client reads VS2's dimension parameters,
 * which only an integrated server fills; a remote client gets the VS2 default.
 */
@OptIn(GameTickOnly::class, VsBeta::class)
fun Level.gravity(): Double =
    if (this is ServerLevel) {
        shipObjectWorld.aerodynamicUtils.getAtmosphereForDimension(dimensionId).third
    } else {
        -(DimensionParametersResolver.dimensionMap[dimensionId]?.gravity ?: DEFAULT_WORLD_GRAVITY).y()
    }

/** The body [bodyId] if forces can move it. */
@OptIn(PhysTickOnly::class, VsBeta::class)
fun PhysLevel.dynamicBody(bodyId: Long): PhysVsBody? =
    getBodyById(bodyId)?.takeIf { !it.isStatic && it.inertiaData.mass > 0.0 }

/** World velocity (m/s) of this ship's material point at [worldPoint]. Any thread; writes [dest] only. */
fun Ship.pointVelocity(worldPoint: Vector3dc, dest: Vector3d): Vector3d =
    pointVelocity(velocity, angularVelocity, transform.positionInWorld, worldPoint, dest)

/** v + ω × (point − centre): the world velocity of a rigid body's point. [centre] is the centre of mass. */
fun pointVelocity(velocity: Vector3dc, omega: Vector3dc, centre: Vector3dc, worldPoint: Vector3dc, dest: Vector3d): Vector3d {
    dest.set(worldPoint).sub(centre)
    return omega.cross(dest, dest).add(velocity)
}

/** Mass (kg) a hit can move. A static body reports a finite mass (VS2 terrain reports 10 kg) but cannot move, so it gives 0. */
@OptIn(PhysTickOnly::class, VsBeta::class)
fun PhysLevel.movableMass(id: Long): Double {
    getShipById(id)?.let { return if (it.isStatic) 0.0 else it.mass }
    val body = getBodyById(id) ?: return 0.0
    return if (body.isStatic) 0.0 else body.inertiaData.mass
}

/** Reduced mass of a two-body hit. A movable mass of 0 is immovable (infinite mass), so the other side counts in full. */
fun reducedMass(movableA: Double, movableB: Double): Double =
    if (movableA > 0.0 && movableB > 0.0) movableA * movableB / (movableA + movableB) else maxOf(movableA, movableB)
