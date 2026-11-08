package org.shipwrights.enderkinesis.client

import dev.architectury.platform.Platform
import net.irisshaders.iris.api.v0.IrisApi
import net.minecraft.Util

/** Iris (Fabric) and Oculus (Forge) both ship `net.irisshaders.iris.api.v0`. Without either, there is no shader pack. */
object IrisCompat {

    private val LOADED = Platform.isModLoaded("iris") || Platform.isModLoaded("oculus")

    // Per-frame callers read this cache; a pack changes only on a user-driven reload.
    private const val CACHE_TTL_MS: Long = 50L

    @Volatile private var cachedInUse: Boolean = false
    @Volatile private var cacheExpiryMs: Long = 0L

    fun isShaderPackInUse(): Boolean {
        if (!LOADED) return false
        val nowMs = Util.getMillis()
        if (nowMs < cacheExpiryMs) return cachedInUse
        val live = IrisApiAccess.isShaderPackInUse()
        cachedInUse = live
        cacheExpiryMs = nowMs + CACHE_TTL_MS
        return live
    }

    /** Asked live, because the value changes within a frame. */
    fun isRenderingShadowPass(): Boolean = isShaderPackInUse() && IrisApiAccess.isRenderingShadowPass()
}

// Loads Iris API classes, so only IrisCompat may reach it, after its isModLoaded check.
private object IrisApiAccess {
    fun isShaderPackInUse(): Boolean = IrisApi.getInstance().isShaderPackInUse
    fun isRenderingShadowPass(): Boolean = IrisApi.getInstance().isRenderingShadowPass
}
