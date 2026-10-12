package org.shipwrights.enderkinesis.client

import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL33

/** GPU time of one pass per frame, averaged. Results are read some frames late so the CPU never waits on the GPU. */
class GpuTimer {

    private val queries = IntArray(RING_SIZE)
    private val pending = BooleanArray(RING_SIZE)
    private var next = 0
    private var supported: Boolean? = null

    // NaN until the first result.
    private var averageMs: Double = Double.NaN

    /** Smoothed GPU time, or why there is none yet. */
    val status: String
        get() = when {
            supported == false -> "timer unsupported"
            !averageMs.isNaN() -> "%.3f ms GPU".format(averageMs)
            else -> "timer waiting (%d pending, %d busy skips)".format(pending.count { it }, busySkips)
        }

    private var busySkips = 0

    /** Starts timing; call [end] only when this returns true. */
    fun begin(): Boolean {
        if (!isSupported()) return false
        collect()
        val slot = next
        if (pending[slot]) return false
        // GL allows one active TIME_ELAPSED query; a begin while another runs fails and never yields a result.
        if (GL15.glGetQueryi(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY) != 0) {
            busySkips++
            return false
        }
        GL33.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[slot])
        return GL15.glGetQueryi(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY) == queries[slot]
    }

    fun end() {
        GL33.glEndQuery(GL33.GL_TIME_ELAPSED)
        pending[next] = true
        next = (next + 1) % RING_SIZE
    }

    private fun collect() {
        for (i in 0 until RING_SIZE) {
            if (!pending[i]) continue
            if (GL15.glGetQueryObjecti(queries[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue
            val ms = GL33.glGetQueryObjecti64(queries[i], GL15.GL_QUERY_RESULT) / NANOS_PER_MS
            averageMs = if (averageMs.isNaN()) ms else averageMs + (ms - averageMs) * SMOOTHING
            pending[i] = false
        }
    }

    private fun isSupported(): Boolean {
        supported?.let { return it }
        val caps = GL.getCapabilities()
        val ok = caps.OpenGL33 || caps.GL_ARB_timer_query
        if (ok) GL15.glGenQueries(queries)
        supported = ok
        return ok
    }

    private companion object {
        const val RING_SIZE = 4
        const val NANOS_PER_MS = 1_000_000.0
        const val SMOOTHING = 0.05
    }
}
