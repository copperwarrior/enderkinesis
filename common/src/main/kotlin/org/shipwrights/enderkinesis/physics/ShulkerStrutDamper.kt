package org.shipwrights.enderkinesis.physics

import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel

/**
 * Viscous damper between a Shulker Strut lid ship and its base, along the slide axis.
 * Konstant does not damp the prismatic limit spring as the joint's damping value asks, so the lid bounces.
 */
class ShulkerStrutDamper : ShipPhysicsListener {

    @Volatile private var active = false
    @Volatile private var baseShipId: Long = NO_BASE
    @Volatile private var axisX = 0.0
    @Volatile private var axisY = 0.0
    @Volatile private var axisZ = 0.0
    @Volatile private var lidX = 0.0
    @Volatile private var lidY = 0.0
    @Volatile private var lidZ = 0.0
    @Volatile private var baseX = 0.0
    @Volatile private var baseY = 0.0
    @Volatile private var baseZ = 0.0

    private val scratchAxis = Vector3d()
    private val scratchPoint = Vector3d()
    private val scratchVel = Vector3d()
    private val scratchArm = Vector3d()

    /** [axis] is FACING in the base frame. The positions are block centres in shipyard coords (world for no base). */
    fun update(baseShipId: Long?, axis: Vector3d, lidModelPos: Vector3d, baseModelPos: Vector3d) {
        this.baseShipId = baseShipId ?: NO_BASE
        axisX = axis.x; axisY = axis.y; axisZ = axis.z
        lidX = lidModelPos.x; lidY = lidModelPos.y; lidZ = lidModelPos.z
        baseX = baseModelPos.x; baseY = baseModelPos.y; baseZ = baseModelPos.z
        active = true
    }

    fun clear() {
        active = false
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        if (!active) return
        val baseId = baseShipId
        val base = if (baseId == NO_BASE) null else physLevel.getShipById(baseId) ?: return

        val axis = scratchAxis.set(axisX, axisY, axisZ)
        base?.transform?.shipToWorldRotation?.transform(axis)
        val lidPoint = physShip.transform.shipToWorld.transformPosition(scratchPoint.set(lidX, lidY, lidZ))
        var relSpeed = pointVelocity(physShip, lidPoint).dot(axis)
        if (base != null) relSpeed -= pointVelocity(base, lidPoint).dot(axis)
        if (relSpeed == 0.0) return

        val lidMass = physShip.mass
        val effectiveMass = if (base == null) lidMass else lidMass * base.mass / (lidMass + base.mass)
        // An explicit force damper overshoots past a gain of m/dt, so remove only part of the error each tick.
        val force = -DAMP_FRACTION_PER_TICK * effectiveMass * relSpeed * PHYS_TPS
        // VS2 keeps the force vector by reference: give each call a new one.
        physShip.applyWorldForceToModelPos(Vector3d(axis).mul(force), Vector3d(lidX, lidY, lidZ))
        base?.applyWorldForceToModelPos(Vector3d(axis).mul(-force), Vector3d(baseX, baseY, baseZ))
    }

    private fun pointVelocity(ship: PhysShip, worldPoint: Vector3d): Vector3d {
        val arm = scratchArm.set(worldPoint).sub(ship.transform.positionInWorld)
        return scratchVel.set(ship.angularVelocity).cross(arm).add(ship.velocity)
    }

    companion object {
        private const val NO_BASE = Long.MIN_VALUE
        private const val PHYS_TPS = 60.0
        private const val DAMP_FRACTION_PER_TICK = 0.8
    }
}
