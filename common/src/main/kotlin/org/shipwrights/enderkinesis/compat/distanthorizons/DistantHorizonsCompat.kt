package org.shipwrights.enderkinesis.compat.distanthorizons

import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.platform.Platform

object DistantHorizonsCompat {

    private val LOADED = Platform.isModLoaded("distanthorizons")

    /**
     * For our FogRenderer mixins. DH (priority 1000) pushes terrain fog out at setupFog RETURN; injectors from a
     * higher-priority mixin run after it at the same point, so our dimension fog wins.
     */
    const val FOG_MIXIN_PRIORITY = 1100

    /** After every FOG_MIXIN_PRIORITY mixin, so the snapshot holds the final terrain fog. */
    const val FOG_SNAPSHOT_MIXIN_PRIORITY = 1200

    /**
     * True on DH's LOD generation threads. DH runs our features and structures there through a
     * WorldGenRegion that wraps the live ServerLevel, but never saves the chunks.
     */
    @JvmStatic
    fun isLodGeneration(): Boolean = LOADED && DhApiAccess.isDhThread()

    fun initClient() {
        if (!LOADED) return
        DhApiAccess.registerFogMatch()
        ClientTickEvent.CLIENT_POST.register { _ -> DhApiAccess.updateRenderGate() }
    }
}
