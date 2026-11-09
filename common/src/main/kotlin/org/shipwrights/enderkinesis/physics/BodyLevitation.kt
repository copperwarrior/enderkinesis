package org.shipwrights.enderkinesis.physics

import java.util.concurrent.ConcurrentHashMap
import org.joml.Vector3d
import org.shipwrights.enderkinesis.util.ServerTicks
import org.shipwrights.enderkinesis.util.dynamicBody
import org.shipwrights.enderkinesis.util.gravity
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.api.vsApi

/** The [RodLevitationAttachment] lift for standalone VS bodies, which cannot hold a ship attachment. The nudge goes through [BodyForceQueue]. */
@OptIn(PhysTickOnly::class, VsBeta::class)
object BodyLevitation {

    private class Lift(val targetY: Double, val cancelGravity: Boolean, val touchTick: Int)

    // Written on the server thread, read on the physics thread.
    private val lifts = ConcurrentHashMap<Long, Lift>()

    @Volatile private var registered = false

    /** Call each game tick while the lift lasts. [cancelGravity] is false when the body's own controller cancels it. */
    fun touch(bodyId: Long, targetY: Double, cancelGravity: Boolean) {
        lifts[bodyId] = Lift(targetY, cancelGravity, ServerTicks.now)
    }

    fun register() {
        if (registered) return
        registered = true
        vsApi.physTickEvent.on { event, _ -> tick(event.world, physicsStepSeconds(event.delta)) }
    }

    fun clearSession() = lifts.clear()

    // The event fires once per dimension, so a body that is not in this level is skipped, not dropped.
    private fun tick(physLevel: PhysLevel, stepSeconds: Double) {
        if (lifts.isEmpty()) return
        val gravity = physLevel.gravity()
        val liftIt = lifts.entries.iterator()
        while (liftIt.hasNext()) {
            val (id, lift) = liftIt.next()
            if (RodLevitationAttachment.isStale(lift.touchTick)) { liftIt.remove(); continue }
            val body = physLevel.dynamicBody(id) ?: continue
            val mass = body.inertiaData.mass
            var forceY = RodLevitationAttachment.liftForceY(mass, body.kinematics.position.y(), body.velocity.y(), lift.targetY, stepSeconds)
            // Ships get this from [ShipGravityHold]; a body has no gravity attachment.
            if (lift.cancelGravity) forceY += mass * gravity
            body.applyWorldForceToBodyPos(Vector3d(0.0, forceY, 0.0))
        }
    }
}
