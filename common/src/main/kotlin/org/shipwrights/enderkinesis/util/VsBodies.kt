package org.shipwrights.enderkinesis.util

import com.mojang.logging.LogUtils
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.mod.api.vsApi

/**
 * vs-core 2.4.13 bug: `createBody` adds the id to `loadedStandaloneBodies` too early, so the tick never wraps the body.
 * Then `setTransform` does nothing and `deleteBody` throws, which leaves the hull in physics. Remove the id to fix it.
 */
object VsBodies {
    private val LOG = LogUtils.getLogger()

    /** Call right after `createBody`. A no-op if the field can't be found (VS2 changed). */
    fun unregisterPreemptiveStandaloneBody(shipWorld: Any, bodyId: Long) {
        try {
            val field = findFieldInHierarchy(shipWorld.javaClass, "loadedStandaloneBodies") ?: return
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST") // The private vs-core field is a MutableSet<Long>; generics are erased.
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
