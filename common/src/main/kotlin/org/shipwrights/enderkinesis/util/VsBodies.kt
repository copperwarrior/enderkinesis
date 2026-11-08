package org.shipwrights.enderkinesis.util

import com.mojang.logging.LogUtils
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.api.vsApi

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
