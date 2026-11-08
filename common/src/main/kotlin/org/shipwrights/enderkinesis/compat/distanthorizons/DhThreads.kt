package org.shipwrights.enderkinesis.compat.distanthorizons

import com.seibel.distanthorizons.api.DhApi

// Server-safe: worldgen reaches this. Only DistantHorizonsCompat may reach it, after its isModLoaded check.
internal object DhThreads {
    fun isDhThread(): Boolean = DhApi.isDhThread()
}
