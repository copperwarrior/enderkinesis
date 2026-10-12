package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.events.common.LifecycleEvent

/**
 * World-seed salt for each Wohlon worldgen hash. The hashes are static functions that runtime
 * code calls too ([WohlonRootGraph], the world-root grower), so the salt cannot live on the
 * chunk generator instance.
 */
object WohlonWorldSeed {

    // Not volatile: the hashes read it in the hottest worldgen loops. The one write is on the
    // server thread before the first chunk task is queued, and the queue hand-off publishes it.
    @JvmField var salt: Int = 0

    fun init() {
        LifecycleEvent.SERVER_BEFORE_START.register { server ->
            val seed = server.worldData.worldGenOptions().seed()
            salt = (seed xor (seed ushr 32)).toInt() * 0x9E3779B1.toInt()
        }
        LifecycleEvent.SERVER_STOPPED.register { _ -> salt = 0 }
    }
}
