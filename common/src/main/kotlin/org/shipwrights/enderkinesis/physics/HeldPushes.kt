package org.shipwrights.enderkinesis.physics

import java.util.concurrent.ConcurrentHashMap
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.util.ServerTicks
import org.shipwrights.enderkinesis.util.dynamicBody
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.api.vsApi

/**
 * A force that acts on every physics tick of one game tick, on a ship or a standalone VS body. VS2 runs several
 * physics ticks for each game tick, and its one-shot queue acts on only one of them.
 * Publish again each game tick to hold a push. A push that is not published again ends one game tick later.
 */
@OptIn(PhysTickOnly::class, VsBeta::class)
object HeldPushes {

    /** [atBodyPos] true: [x], [y], [z] are an offset from the centre of mass in body axes; false: a world point. */
    private class Push(
        val fx: Double, val fy: Double, val fz: Double,
        val x: Double, val y: Double, val z: Double,
        val atBodyPos: Boolean,
        val tick: Int,
    ) {
        // Physics thread only.
        var spentSeconds = 0.0
    }

    private data class Key(val source: Any, val bodyId: Long)

    private const val GAME_TICK_SECONDS = 0.05
    // Float sums of steps fall just short of a whole game tick, which would run one extra step.
    private const val SPENT_TOLERANCE = 1e-9
    // A push for a body that no physics level finds is dropped after this many game ticks.
    private const val STALE_TICKS = 20

    // Written on the server thread, read on the physics thread.
    private val pushes = ConcurrentHashMap<Key, Push>()

    @Volatile private var registered = false

    fun register() {
        if (registered) return
        registered = true
        vsApi.physTickEvent.on { event, _ -> tick(event.world, physicsStepSeconds(event.delta)) }
    }

    fun clearSession() = pushes.clear()

    /**
     * Server thread. Holds [force] (world axes) at [posInBody], an offset from the centre of mass in body axes, for
     * one game tick. [source] tells pushes apart, so two sources on one body add up; one source replaces its own push.
     * Use one source for each feature and caster, for example `feature to player.uuid`.
     */
    fun holdAtBodyPos(source: Any, bodyId: Long, force: Vector3dc, posInBody: Vector3dc = ZERO) {
        pushes[Key(source, bodyId)] = Push(
            force.x(), force.y(), force.z(), posInBody.x(), posInBody.y(), posInBody.z(), true, ServerTicks.now,
        )
    }

    /** Server thread. Like [holdAtBodyPos], at a world point. */
    fun holdAtWorldPos(source: Any, bodyId: Long, force: Vector3dc, worldPos: Vector3dc) {
        pushes[Key(source, bodyId)] = Push(
            force.x(), force.y(), force.z(), worldPos.x(), worldPos.y(), worldPos.z(), false, ServerTicks.now,
        )
    }

    // The event fires once per dimension, so a body that is not in this level is skipped, not spent.
    private fun tick(physLevel: PhysLevel, stepSeconds: Double) {
        if (pushes.isEmpty()) return
        val it = pushes.entries.iterator()
        while (it.hasNext()) {
            val (key, push) = it.next()
            if (push.spentSeconds >= GAME_TICK_SECONDS - SPENT_TOLERANCE || ServerTicks.now - push.tick > STALE_TICKS) {
                pushes.remove(key, push)
                continue
            }
            val force = Vector3d(push.fx, push.fy, push.fz)
            // VS2 throws on a non-finite wrench, and that ends the server.
            if (!force.isFinite || !Vector3d(push.x, push.y, push.z).isFinite) {
                pushes.remove(key, push)
                continue
            }
            val ship = physLevel.getShipById(key.bodyId)
            if (ship != null) {
                if (ship.isStatic || ship.mass <= 0.0) continue
                if (push.atBodyPos) ship.applyWorldForceToBodyPos(force, Vector3d(push.x, push.y, push.z))
                else ship.applyWorldForce(force, Vector3d(push.x, push.y, push.z))
            } else {
                val body = physLevel.dynamicBody(key.bodyId) ?: continue
                if (push.atBodyPos) body.applyWorldForceToBodyPos(force, Vector3d(push.x, push.y, push.z))
                else body.applyWorldForce(force, Vector3d(push.x, push.y, push.z))
            }
            push.spentSeconds += stepSeconds
        }
    }

    private val ZERO: Vector3dc = Vector3d()
}
