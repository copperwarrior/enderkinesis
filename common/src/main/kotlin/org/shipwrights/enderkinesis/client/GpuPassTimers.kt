package org.shipwrights.enderkinesis.client

import java.util.Locale
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL33

/**
 * GPU time of the costly world passes, from GL_TIMESTAMP queries, shown on the F3 screen.
 * Off, and nothing turns it on yet; when off each hook is one boolean test. Render thread only.
 */
object GpuPassTimers {
    const val WATER_MURK = 0
    const val SSELITH_ECLIPSE_GLOW = 1
    const val WOHLON_PORTAL = 2
    const val WOHLON_RITUAL_TENDRILS = 3
    const val WOHLON_RITE_PEDESTAL = 4
    const val HEART_OF_THE_WILD_TENDRILS = 5
    const val FOUNTAIN_OF_BLESSING = 6
    const val WOHLON_TREE_GHOST = 7
    const val WOHLON_FOG_WISPS = 8
    const val SSELITH_VOID_FOG = 9
    private val NAMES = arrayOf(
        "Wohlon water murk", "Sselith eclipse glow", "Wohlon portals", "Wohlon ritual tendrils",
        "Wohlon rite pedestal", "Heart of the Wild tendrils", "Fountain of Blessing", "Wohlon tree ghost",
        "Wohlon fog wisps", "Sselith void fog",
    )
    private val PASSES = NAMES.size

    // Two query pairs for each pass: one is read while the GPU still works on the other, so a read never waits.
    private const val BUFFERS = 2
    // A start and an end timestamp. Vanilla F3 keeps a TIME_ELAPSED query open for the frame, and those cannot nest.
    private const val STAMPS = 2
    private const val SMOOTHING = 0.1
    // Each hook runs each frame, also when its pass draws nothing; such a pass gets no line.
    private const val MIN_SHOWN_MS = 0.005
    private const val NANOS_PER_MS = 1.0e6

    var enabled = false
        private set
    private var queries: IntArray? = null
    private val issued = BooleanArray(PASSES * BUFFERS)
    private val next = IntArray(PASSES)
    private val open = IntArray(PASSES) { -1 }
    private val smoothedMs = DoubleArray(PASSES)

    @JvmStatic
    fun begin(pass: Int) {
        if (!enabled) return
        val ids = queries ?: IntArray(PASSES * BUFFERS * STAMPS).also {
            GL15.glGenQueries(it)
            queries = it
        }
        val slot = pass * BUFFERS + next[pass]
        next[pass] = (next[pass] + 1) % BUFFERS
        if (issued[slot]) readResult(pass, ids[slot * STAMPS], ids[slot * STAMPS + 1])
        GL33.glQueryCounter(ids[slot * STAMPS], GL33.GL_TIMESTAMP)
        issued[slot] = false
        open[pass] = slot
    }

    @JvmStatic
    fun end(pass: Int) {
        val slot = open[pass]
        if (slot == -1) return
        val ids = checkNotNull(queries)
        GL33.glQueryCounter(ids[slot * STAMPS + 1], GL33.GL_TIMESTAMP)
        issued[slot] = true
        open[pass] = -1
    }

    /** The F3 lines; empty when off. Allocates only while F3 is open with the timers on. */
    fun appendDebugLines(lines: MutableList<String>) {
        if (!enabled) return
        lines.add("")
        lines.add("Enderkinesis GPU passes (ms):")
        for (pass in 0 until PASSES) {
            if (smoothedMs[pass] < MIN_SHOWN_MS) continue
            lines.add(String.format(Locale.ROOT, "  %s: %.2f", NAMES[pass], smoothedMs[pass]))
        }
    }

    // A result that is not ready yet is dropped, not waited for.
    private fun readResult(pass: Int, startId: Int, endId: Int) {
        if (GL15.glGetQueryObjecti(endId, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) return
        if (GL15.glGetQueryObjecti(startId, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) return
        val start = GL33.glGetQueryObjectui64(startId, GL15.GL_QUERY_RESULT)
        val nanos = GL33.glGetQueryObjectui64(endId, GL15.GL_QUERY_RESULT) - start
        smoothedMs[pass] += (nanos / NANOS_PER_MS - smoothedMs[pass]) * SMOOTHING
    }
}
