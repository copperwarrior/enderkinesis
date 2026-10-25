package org.shipwrights.enderkinesis.util

import com.mojang.logging.LogUtils
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.mod.api.vsApi

/**
 * Workarounds for standalone VS2 bodies (`ServerShipWorld.createBody`), shared by the
 * Ga'Mylan cages and Frozen mobs.
 *
 * UPSTREAM VS CORE BUG (2.4.13): `createBody` adds the new id to a private
 * `loadedStandaloneBodies` set up front. The per-tick loop that wraps standalone bodies into
 * `_loadedBodies` is gated on adding the id to that set, so it never runs for our body:
 * `setTransform` updates are dropped, and `deleteBody` throws on the missing wrapper before
 * telling physics, leaving the collision hull behind forever. Removing the id again right
 * after creation lets the next tick wrap the body normally, after which both work.
 */
object VsBodies {
    private val LOG = LogUtils.getLogger()

    /** Call right after `createBody`. A no-op if the field can't be found (VS2 changed). */
    fun unregisterPreemptiveStandaloneBody(shipWorld: Any, bodyId: Long) {
        try {
            val field = findFieldInHierarchy(shipWorld.javaClass, "loadedStandaloneBodies") ?: return
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val set = field.get(shipWorld) as? MutableSet<Long> ?: return
            set.remove(bodyId)
        } catch (t: Throwable) {
            LOG.warn("Failed to unregister preemptive standalone body id={}", bodyId, t)
        }
    }

    /** Deletes standalone body [bodyId], tolerating the upstream bug above. */
    fun delete(level: ServerLevel, bodyId: Long) {
        val shipWorld = vsApi.getServerShipWorld(level.server) ?: return
        val body = shipWorld.allBodies.getById(bodyId) ?: return
        try {
            shipWorld.deleteBody(body)
        } catch (e: IllegalArgumentException) {
            // The upstream bug: the body stays in physics until vs-core is patched.
        } catch (t: Throwable) {
            LOG.warn("deleteBody({}) failed", bodyId, t)
        }
    }

    private fun findFieldInHierarchy(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }
}
