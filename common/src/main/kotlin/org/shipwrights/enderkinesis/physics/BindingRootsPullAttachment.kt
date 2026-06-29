package org.shipwrights.enderkinesis.physics

import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel

/**
 * Per-ship attachment that applies the Binding-Roots merger's spring pull AND cardinal
 * alignment torque during the physics tick.
 *
 * `BindingRootsMerger` (game thread) computes the desired pull force + application point +
 * orientation-alignment torque each server tick and pushes them through [update]. This
 * object — attached to the ship — gets that value via volatile fields and applies it to
 * [PhysShip] every physics tick (typically 60 Hz vs the merger's 20 Hz). Applying every
 * physics tick gives correct continuous-force semantics — the integrator sees the same
 * force/torque for ~3 sub-ticks between merger updates.
 *
 * The merger writes zeros when no pair is active so the ship stops getting forced /
 * torqued the instant the pair breaks.
 */
class BindingRootsPullAttachment : ShipPhysicsListener {

    @Volatile private var forceX: Double = 0.0
    @Volatile private var forceY: Double = 0.0
    @Volatile private var forceZ: Double = 0.0
    @Volatile private var pointX: Double = 0.0
    @Volatile private var pointY: Double = 0.0
    @Volatile private var pointZ: Double = 0.0
    @Volatile private var torqueX: Double = 0.0
    @Volatile private var torqueY: Double = 0.0
    @Volatile private var torqueZ: Double = 0.0

    fun update(force: Vector3d, worldPoint: Vector3d, torque: Vector3d) {
        forceX = force.x; forceY = force.y; forceZ = force.z
        pointX = worldPoint.x; pointY = worldPoint.y; pointZ = worldPoint.z
        torqueX = torque.x; torqueY = torque.y; torqueZ = torque.z
    }

    fun clear() {
        forceX = 0.0; forceY = 0.0; forceZ = 0.0
        torqueX = 0.0; torqueY = 0.0; torqueZ = 0.0
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        // Block-sourced scale³ multiplier — matches all other block-sourced
        // appliers for scale-invariant ship-lengths-per-second feel.
        val s1 = physShip.transform.shipToWorldScaling.x()
        val s = s1 * s1 * s1
        val fx = forceX; val fy = forceY; val fz = forceZ
        if (fx != 0.0 || fy != 0.0 || fz != 0.0) {
            physShip.applyWorldForce(Vector3d(fx * s, fy * s, fz * s), Vector3d(pointX, pointY, pointZ))
        }
        val tx = torqueX; val ty = torqueY; val tz = torqueZ
        if (tx != 0.0 || ty != 0.0 || tz != 0.0) {
            physShip.applyWorldTorque(Vector3d(tx * s, ty * s, tz * s))
        }
    }
}
