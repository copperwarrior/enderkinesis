package org.shipwrights.enderkinesis.client.volume

import com.mojang.logging.LogUtils
import java.util.Locale
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL33

/**
 * GPU time of the volume passes from GL_TIME_ELAPSED queries, read when their results are ready, so nothing stalls,
 * and CPU time of their frame work from [System.nanoTime]. The timers run only while F3 is open or the JVM flag
 * `-D[LOG_PROPERTY]=true|<seconds>` is set. Render thread only.
 */
object VolumePerf {
    const val LOG_PROPERTY = "enderkinesis.volumetricPerfLog"

    private val LOG = LogUtils.getLogger()
    private val logIntervalNanos = logInterval(System.getProperty(LOG_PROPERTY) ?: System.getenv(TEMP_LOG_ENV))

    // TEMP for the gas test runs, remove after: gradle runClient passes the shell environment to the game.
    private const val TEMP_LOG_ENV = "ENDERKINESIS_VOLUMETRIC_PERF_LOG"

    // Filled only while the pass objects construct, never per frame.
    private val groups = ArrayList<PerfGroup>()

    private var supported: Boolean? = null
    private var nextWindowNanos = 0L
    private var nextLogNanos = 0L
    internal var running = false

    val eclipseGlow = group("eclipse glow").timer("pass")
    val heartFog = group("heart portal fog").timer("pass")
    val voidFog = group("sselith void fog").timer("pass")

    /** Logs the flag state one time, at client init. */
    fun init() {
        if (logIntervalNanos > 0) LOG.info("Volumetric perf log on, every {} s", logIntervalNanos / NANOS_PER_SECOND)
    }

    fun group(name: String, details: (() -> String)? = null): PerfGroup = PerfGroup(name, details).also { groups.add(it) }

    internal val active: Boolean
        get() = cpuActive && timersSupported()

    internal val cpuActive: Boolean
        get() = logIntervalNanos > 0 || Minecraft.getInstance().options.renderDebug

    /** Call one time each frame, before the passes. */
    fun beginFrame() {
        val now = System.nanoTime()
        val window = now >= nextWindowNanos
        if (window) nextWindowNanos = now + NANOS_PER_SECOND
        // Index loops: no iterator per frame.
        for (i in 0 until groups.size) {
            val timers = groups[i].timers
            for (j in 0 until timers.size) {
                timers[j].collect()
                if (window) timers[j].closeWindow()
            }
        }
        if (logIntervalNanos > 0 && now >= nextLogNanos) {
            if (nextLogNanos != 0L) writeLog()
            nextLogNanos = now + logIntervalNanos
        }
    }

    /** Lines for the right side of F3: one per pass group that ran in the last second. */
    fun debugLines(out: MutableList<String>) {
        // GPU timers without query support never run, so only the CPU groups show then.
        out.add(
            if (timersSupported()) "Volumes ms (1 s avg; cpu groups are CPU, the rest GPU)"
            else "Volumes CPU ms (1 s avg; no GPU timer queries)",
        )
        for (g in groups) {
            if (g.timers.none { it.shownMs >= IDLE_MS }) continue
            val sb = StringBuilder(g.name).append(':')
            for (t in g.timers) if (t.shownMs >= 0.0) sb.append(' ').append(t.part).append(' ').append(fmt(t.shownMs))
            g.details?.let { sb.append(" | ").append(it()) }
            out.add(sb.toString())
        }
    }

    private fun writeLog() {
        for (g in groups) {
            val details = g.details?.invoke()
            for (t in g.timers) {
                val s = t.takeLogStats() ?: continue
                if (s.max < IDLE_MS) continue
                LOG.info(
                    "Volume perf {}/{}: avg {} ms, p95 {} ms, max {} ms, {} frames{}",
                    g.name, t.part, fmt(s.avg), fmt(s.p95), fmt(s.max), s.frames, if (details != null) " | $details" else "",
                )
            }
        }
    }

    private fun timersSupported(): Boolean = supported ?: GL.getCapabilities().let { it.OpenGL33 || it.GL_ARB_timer_query }
        .also { supported = it }

    private fun fmt(ms: Double) = String.format(Locale.ROOT, "%.3f", ms)

    private fun logInterval(value: String?): Long {
        if (value.isNullOrBlank() || value.equals("false", ignoreCase = true)) return 0L
        val seconds = if (value.equals("true", ignoreCase = true)) DEFAULT_LOG_SECONDS else value.toLongOrNull() ?: DEFAULT_LOG_SECONDS
        return seconds.coerceAtLeast(1L) * NANOS_PER_SECOND
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val DEFAULT_LOG_SECONDS = 5L

    // A wrapped pass that drew nothing still times a few microseconds.
    private const val IDLE_MS = 0.002
}

class PerfGroup internal constructor(val name: String, internal val details: (() -> String)?) {
    internal val timers = ArrayList<PassTimer>(4)

    fun timer(part: String): GpuPassTimer = GpuPassTimer(part).also { timers.add(it) }

    fun cpuTimer(part: String): CpuPassTimer = CpuPassTimer(part).also { timers.add(it) }
}

internal class LogStats(val avg: Double, val p95: Double, val max: Double, val frames: Int)

/** The per-frame samples of one timed part: a 1 s average for F3 and avg/p95/max for the log. */
abstract class PassTimer internal constructor(val part: String) {
    private var windowSum = 0.0
    private var windowCount = 0

    /** The average of the last full second, or -1 when the pass did not run in it. */
    internal var shownMs = -1.0
        private set

    private val logSamples = FloatArray(LOG_CAPACITY)
    private var logCount = 0
    private var logSum = 0.0
    private var logMax = 0.0
    private var logFrames = 0

    /** Called at the start of each frame. */
    internal abstract fun collect()

    internal fun closeWindow() {
        shownMs = if (windowCount > 0) windowSum / windowCount else -1.0
        windowSum = 0.0
        windowCount = 0
    }

    /** The stats since the last call, or null when the pass did not run. */
    internal fun takeLogStats(): LogStats? {
        if (logFrames == 0) return null
        val n = logCount
        logSamples.sort(0, n)
        val p95 = if (n > 0) logSamples[((n - 1) * P95).toInt()].toDouble() else logMax
        val stats = LogStats(logSum / logFrames, p95, logMax, logFrames)
        logCount = 0
        logSum = 0.0
        logMax = 0.0
        logFrames = 0
        return stats
    }

    protected fun add(ms: Double) {
        windowSum += ms
        windowCount++
        // Past the capacity the p95 comes from the first samples of the interval; avg and max stay exact.
        if (logCount < LOG_CAPACITY) logSamples[logCount++] = ms.toFloat()
        logSum += ms
        logMax = maxOf(logMax, ms)
        logFrames++
    }

    protected companion object {
        const val LOG_CAPACITY = 8192
        const val P95 = 0.95
        const val NANOS_PER_MS = 1.0e6
    }
}

/** One timed span per frame. GL_TIME_ELAPSED cannot nest, so a [begin] while another timer runs is skipped. */
class GpuPassTimer internal constructor(part: String) : PassTimer(part) {
    private val queries = IntArray(SLOTS)
    private val pending = BooleanArray(SLOTS)
    private var slot = 0
    private var mine = false

    fun begin() {
        if (VolumePerf.running || !VolumePerf.active) return
        if (queries[0] == 0) GL15.glGenQueries(queries)
        // A result not read by now is dropped; reusing the query never waits for it.
        pending[slot] = false
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[slot])
        mine = true
        VolumePerf.running = true
    }

    fun end() {
        if (!mine) return
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED)
        pending[slot] = true
        slot = (slot + 1) % SLOTS
        mine = false
        VolumePerf.running = false
    }

    override fun collect() {
        for (i in 0 until SLOTS) {
            if (!pending[i] || GL15.glGetQueryObjecti(queries[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue
            pending[i] = false
            add(GL33.glGetQueryObjecti64(queries[i], GL15.GL_QUERY_RESULT) / NANOS_PER_MS)
        }
    }

    private companion object {
        // Results come back 1-3 frames late.
        const val SLOTS = 4
    }
}

/**
 * CPU time of a part: the spans between [begin] and [end] add up over a frame, and the frame sum is one sample.
 * CPU timers can nest. A GL call times only its driver work on this thread, not the GPU.
 */
class CpuPassTimer internal constructor(part: String) : PassTimer(part) {
    private var startNanos = 0L
    private var frameNanos = 0L
    private var timing = false
    private var ran = false

    fun begin() {
        if (!VolumePerf.cpuActive) return
        startNanos = System.nanoTime()
        timing = true
    }

    fun end() {
        if (!timing) return
        frameNanos += System.nanoTime() - startNanos
        timing = false
        ran = true
    }

    override fun collect() {
        if (!ran) return
        add(frameNanos / NANOS_PER_MS)
        frameNanos = 0L
        ran = false
    }
}
