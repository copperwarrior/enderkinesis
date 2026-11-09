package org.shipwrights.enderkinesis.physics

import java.util.concurrent.ConcurrentHashMap
import org.joml.Vector3d
import org.joml.primitives.AABBdc
import org.shipwrights.enderkinesis.util.ServerTicks
import org.shipwrights.enderkinesis.util.dynamicBody
import org.shipwrights.enderkinesis.util.gravity
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.api.vsApi

/**
 * Archimedes buoyancy of the lattice sea on standalone VS bodies. The 8 corners of the oriented body box
 * each carry an eighth of the box volume; wet corners lift and drag at their own point.
 * Ships float through [CrepusculiteLatticeForceInducer] instead.
 */
@OptIn(PhysTickOnly::class, VsBeta::class)
object BodyBuoyancy {

    /** The sea of one lattice, with the lattice drag tuning, and the model-frame box of the body's collision shape. */
    class Sea(
        val waterLineY: Double, val fluidDensity: Double, val waveTime: Double, val waveIntensity: Double,
        val dragCoefficient: Double, val angularDragCoefficient: Double,
        val box: AABBdc,
    ) {
        internal val touchTick: Int = ServerTicks.now
    }

    // Written on the server thread, read on the physics thread.
    private val seas = ConcurrentHashMap<Long, Sea>()

    @Volatile private var registered = false

    /** Call each game tick while the body is in the sea. */
    fun touch(bodyId: Long, sea: Sea) {
        seas[bodyId] = sea
    }

    fun register() {
        if (registered) return
        registered = true
        vsApi.physTickEvent.on { event, _ -> tick(event.world) }
    }

    fun clearSession() = seas.clear()

    // The event fires once per dimension, so a body that is not in this level is skipped, not dropped.
    private fun tick(physLevel: PhysLevel) {
        if (seas.isEmpty()) return
        val gravity = physLevel.gravity()
        val iter = seas.entries.iterator()
        while (iter.hasNext()) {
            val (id, sea) = iter.next()
            if (RodLevitationAttachment.isStale(sea.touchTick)) { iter.remove(); continue }
            val body = physLevel.dynamicBody(id) ?: continue
            applyCornerForces(body, sea, gravity)
        }
    }

    private fun applyCornerForces(body: PhysVsBody, sea: Sea, gravity: Double) {
        // PhysVsBodyImpl.collisionShape is always null, so the server thread sends the shape box.
        val box = sea.box
        val transform = body.kinematics.transform
        val com = body.kinematics.position
        val vel = body.kinematics.velocity
        val omega = body.kinematics.angularVelocity
        val scale = transform.scaling.x()
        val edgeX = (box.maxX() - box.minX()) * scale
        val edgeY = (box.maxY() - box.minY()) * scale
        val edgeZ = (box.maxZ() - box.minZ()) * scale
        // Soft wet ramp over half the shortest edge, so a corner crossing the surface does not step the force.
        val ramp = 0.5 * minOf(edgeX, edgeY, edgeZ)
        if (ramp <= 0.0) return
        val volume = edgeX * edgeY * edgeZ
        val cornerLift = sea.fluidDensity * gravity * volume / CORNERS
        // Lattice per-block drag per cubic metre, split over the corners. The clamp keeps lambda*dt <= 0.5 in the explicit 1/60 s step.
        val mass = body.inertiaData.mass
        val cornerLin = minOf(sea.dragCoefficient / CORNERS * volume, MAX_LIN_PER_MASS * mass)
        val cornerAng = minOf(sea.angularDragCoefficient / CORNERS * volume, MAX_ANG_PER_MASS * mass)
        val world = Vector3d()
        for (i in 0 until CORNERS) {
            world.set(
                if (i and 1 == 0) box.minX() else box.maxX(),
                if (i and 2 == 0) box.minY() else box.maxY(),
                if (i and 4 == 0) box.minZ() else box.maxZ(),
            )
            transform.toWorld.transformPosition(world)
            val surfaceY = sea.waterLineY + GerstnerOcean.heightAt(world.x, world.z, sea.waveTime, sea.waveIntensity)
            val wet = ((surfaceY - world.y) / ramp).coerceIn(0.0, 1.0)
            if (wet <= 0.0) continue
            val rx = world.x - com.x()
            val ry = world.y - com.y()
            val rz = world.z - com.z()
            val dLin = -cornerLin * wet
            val dAng = -cornerAng * wet
            // VS2 queues these two vectors by reference, so each call gets new ones.
            val force = Vector3d(
                vel.x() * dLin + (omega.y() * rz - omega.z() * ry) * dAng,
                cornerLift * wet + vel.y() * dLin + (omega.z() * rx - omega.x() * rz) * dAng,
                vel.z() * dLin + (omega.x() * ry - omega.y() * rx) * dAng,
            )
            body.applyWorldForce(force, Vector3d(world))
        }
    }

    private const val CORNERS = 8
    private const val MAX_LIN_PER_MASS = 3.75
    private const val MAX_ANG_PER_MASS = 1.25
}
