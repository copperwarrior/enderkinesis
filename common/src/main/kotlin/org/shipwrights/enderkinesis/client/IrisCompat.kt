package org.shipwrights.enderkinesis.client

import java.lang.reflect.Method
import net.minecraft.Util

/**
 * Detect Iris / Oculus shader-pack activity via reflection — no compile-time dep.
 *
 * Both Iris (Fabric) and Oculus (Forge, an Iris fork) expose
 * `net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()`. When the class
 * is absent (no shader-compat mod installed) or the call throws for any reason, we report
 * "no shader pack" so callers degrade to the no-shaders path. Cached after first probe;
 * the reflection cost is paid once per JVM. Per-frame callers can call [isShaderPackInUse]
 * freely without worrying about reflection overhead.
 *
 * Why reflective and not a `compileOnly` Iris dep: keeps the buildscript free of an Iris
 * jar (and the Forge-side equivalent), works across the API-stable Iris versions without
 * pinning, and avoids accidentally calling any Iris-internal method that might rename
 * between minor versions.
 */
object IrisCompat {

    private const val IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi"

    /** Wall-clock cache TTL. Iris pack status only changes on
     *  resource reload (user picks a new shader pack in the UI →
     *  Iris triggers a reload), which is a user-driven action
     *  measured in seconds. 50 ms staleness is imperceptible. */
    private const val CACHE_TTL_MS: Long = 50L

    // Null means "tried to find, doesn't exist" — distinct from "haven't tried yet" via the
    // [probed] flag. Both irisInstance and isShaderPackInUseMethod are populated together.
    @Volatile private var probed: Boolean = false
    @Volatile private var irisInstance: Any? = null
    @Volatile private var isShaderPackInUseMethod: Method? = null

    /** Cached query result + wall-clock millis at which it expires.
     *  Two fields, 9 bytes total (1 byte Boolean + 8 byte Long).
     *  Per-frame callers (e.g. Sselith / Wohlon sky-pass branches)
     *  pay one [Util.getMillis] call + a long compare to read the
     *  cache instead of a full reflective `Method.invoke` per frame.
     *  Spark v8 had this function self-attributed at 20 ms with
     *  108 ms cumulative; the live invoke is microseconds per call,
     *  and at 60+ FPS over a 160 s sample that's where the time
     *  goes. The cached read is single-digit ns. */
    @Volatile private var cachedInUse: Boolean = false
    @Volatile private var cacheExpiryMs: Long = 0L

    fun isShaderPackInUse(): Boolean {
        val nowMs = Util.getMillis()
        if (nowMs < cacheExpiryMs) return cachedInUse
        if (!probed) probe()
        val instance = irisInstance
        val method = isShaderPackInUseMethod
        val live = if (instance == null || method == null) {
            false
        } else {
            try {
                method.invoke(instance) as Boolean
            } catch (_: Throwable) {
                false
            }
        }
        cachedInUse = live
        cacheExpiryMs = nowMs + CACHE_TTL_MS
        return live
    }

    @Synchronized
    private fun probe() {
        if (probed) return
        try {
            val apiClass = Class.forName(IRIS_API_CLASS)
            irisInstance = apiClass.getMethod("getInstance").invoke(null)
            isShaderPackInUseMethod = apiClass.getMethod("isShaderPackInUse")
        } catch (_: Throwable) {
            // Iris / Oculus not installed, or API surface changed — treat as no shaders.
            irisInstance = null
            isShaderPackInUseMethod = null
        }
        probed = true
    }
}
