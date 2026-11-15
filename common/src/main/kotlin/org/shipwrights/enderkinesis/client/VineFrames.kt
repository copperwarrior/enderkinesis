package org.shipwrights.enderkinesis.client

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import org.valkyrienskies.core.api.chains.ClientChain
import org.valkyrienskies.core.api.particles.ClientVsParticle
import org.valkyrienskies.core.api.particles.ParticleId
import org.valkyrienskies.core.api.particles.QueryableParticleData
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** [VineFrameDebug] counts since the last log line. */
internal class VineFrameStats {
    var gapSum = 0.0
    var gapCount = 0
    var gapMax = 0.0
    var drawFrames = 0
    var underruns = 0
    var remaps = 0
    var resets = 0

    fun clear() {
        gapSum = 0.0; gapCount = 0; gapMax = 0.0; drawFrames = 0; underruns = 0; remaps = 0; resets = 0
    }
}

/**
 * Maps client wall time (System.nanoTime) to the VS physics tick that the vines show. The physics tick of each frame
 * is the server's own time stamp; VS sends a chain frame over one or more game ticks, so arrival times are uneven but
 * the stamps are not.
 */
internal class VineFrameClock(private val stats: VineFrameStats) {
    private var ticksPerNano = NOMINAL_TICKS_PER_NANO
    private var epoch = 0L
    // Smoothed (physics tick - wall since epoch * rate) at frame arrival.
    private var offset = 0.0
    private var jitter = 0.0
    private var delay = MIN_DELAY_NANOS * NOMINAL_TICKS_PER_NANO
    private val gaps = DoubleArray(GAP_HISTORY)
    private var gapCount = 0
    private var gapNext = 0
    private var newestTick = -1L
    private var rateTick = 0L
    private var rateWall = 0L
    private var play = 0.0
    private var playWall = 0L

    val isStarted get() = newestTick >= 0
    val newest: Long get() = newestTick
    val delayTicks: Double get() = delay
    val ticksPerSecond: Double get() = ticksPerNano * 1e9

    /** The largest recent frame gap in physics ticks: how far a sample may run on past the newest frame. */
    val horizon: Double get() = max(recentMaxGap(), 1.0)

    /** Notes that frame [tick] was first seen at [wall]. */
    fun observe(tick: Long, wall: Long) {
        if (tick == newestTick) return
        if (newestTick < 0 || newestTick - tick > snapTicks()) {
            start(tick, wall)
            return
        }
        // A server slower than the clock can resend an older frame; it holds nothing new.
        if (tick < newestTick) return
        if (wall - rateWall >= RATE_WINDOW_NANOS) estimateRate(tick, wall)
        val gap = (tick - newestTick).toDouble()
        val err = tick - since(wall) * ticksPerNano - offset
        newestTick = tick
        // A pause or a long stall: line up again, but keep the rate and the gap history.
        if (abs(err) > snapTicks()) {
            resync(tick, wall)
            return
        }
        recordGap(gap)
        offset += err * OFFSET_GAIN
        jitter += (abs(err) - jitter) * JITTER_GAIN
        val target = (recentMaxGap() + JITTER_MARGIN * jitter).coerceIn(minDelay(), maxDelay())
        delay += (target - delay) * if (target > delay) DELAY_RISE_GAIN else DELAY_FALL_GAIN
    }

    /** Moves the shown tick on to [wall], slewing toward the target delay; returns the shown physics tick. */
    fun advance(wall: Long): Double {
        if (!isStarted || wall <= playWall) return play
        val target = offset + since(wall) * ticksPerNano - delay
        val errSeconds = (target - play) / ticksPerNano / 1e9
        val speed = 1.0 + (errSeconds * SLEW_PER_SECOND).coerceIn(-MAX_SLEW, MAX_SLEW)
        play += (wall - playWall) * ticksPerNano * speed
        playWall = wall
        if (abs(target - play) > snapTicks()) play = target
        return play
    }

    /** The physics tick shown at [wall], on from the last [advance] at the current rate; it changes no state. */
    fun at(wall: Long): Double = if (isStarted) play + (wall - playWall) * ticksPerNano else play

    fun clear() {
        newestTick = -1L
        ticksPerNano = NOMINAL_TICKS_PER_NANO
        delay = MIN_DELAY_NANOS * NOMINAL_TICKS_PER_NANO
        gapCount = 0
    }

    private fun since(wall: Long) = (wall - epoch).toDouble()

    private fun minDelay() = MIN_DELAY_NANOS * ticksPerNano
    private fun maxDelay() = MAX_DELAY_NANOS * ticksPerNano
    private fun snapTicks() = max(SNAP_NANOS * ticksPerNano, MIN_SNAP_TICKS)

    private fun recordGap(gap: Double) {
        gaps[gapNext] = gap
        gapNext = (gapNext + 1) % GAP_HISTORY
        if (gapCount < GAP_HISTORY) gapCount++
        stats.gapSum += gap
        stats.gapCount++
        stats.gapMax = max(stats.gapMax, gap)
    }

    private fun recentMaxGap(): Double {
        if (gapCount == 0) return NOMINAL_GAP
        var m = 0.0
        for (i in 0 until gapCount) m = max(m, gaps[i])
        return m
    }

    // A server that runs slow publishes fewer physics ticks a second; follow it, or the buffer runs dry.
    private fun estimateRate(tick: Long, wall: Long) {
        val measured = ((tick - rateTick) / (wall - rateWall).toDouble()).coerceIn(MIN_TICKS_PER_NANO, MAX_TICKS_PER_NANO)
        val ratio = measured / ticksPerNano
        // A rate far off the estimate (physics at a few steps a second) is taken at once, not over many windows.
        val gain = if (ratio > RATE_JUMP_RATIO || ratio < 1.0 / RATE_JUMP_RATIO) 1.0 else RATE_GAIN
        val next = ticksPerNano + (measured - ticksPerNano) * gain
        offset += since(wall) * (ticksPerNano - next)
        delay *= next / ticksPerNano
        ticksPerNano = next
        rateTick = tick
        rateWall = wall
    }

    private fun start(tick: Long, wall: Long) {
        gapCount = 0
        rateTick = tick
        rateWall = wall
        resync(tick, wall)
    }

    private fun resync(tick: Long, wall: Long) {
        stats.resets++
        epoch = wall
        offset = tick.toDouble()
        jitter = 0.0
        newestTick = tick
        play = tick - delay
        playWall = wall
    }

    private companion object {
        /** VS default: 3 physics ticks a game tick at 20 game ticks a second. */
        const val NOMINAL_TICKS_PER_NANO = 60.0 / 1e9
        const val MIN_TICKS_PER_NANO = 0.5 / 1e9
        const val MAX_TICKS_PER_NANO = 120.0 / 1e9
        const val NOMINAL_GAP = 3.0
        const val GAP_HISTORY = 16
        /** One game tick at the least, as vanilla entity interpolation. */
        const val MIN_DELAY_NANOS = 50e6
        const val MAX_DELAY_NANOS = 500e6
        const val SNAP_NANOS = 1.5e9
        const val MIN_SNAP_TICKS = 6.0
        const val JITTER_MARGIN = 2.0
        const val OFFSET_GAIN = 0.1
        const val JITTER_GAIN = 0.1
        const val DELAY_RISE_GAIN = 0.5
        const val DELAY_FALL_GAIN = 0.05
        const val RATE_GAIN = 0.5
        const val RATE_JUMP_RATIO = 2.0
        const val RATE_WINDOW_NANOS = 1_000_000_000L
        /** Speed change per second of error; MAX_SLEW keeps the shown motion within 10 % of real speed. */
        const val SLEW_PER_SECOND = 3.0
        const val MAX_SLEW = 0.1
    }
}

/**
 * A ring of one vine's last chain frames, stamped with their physics ticks. All kept frames use the node list of the
 * newest frame, so a reel that adds or takes a node keeps the history.
 */
internal class VineFrames {
    private val ticks = LongArray(CAPACITY)
    private val hasVelocity = BooleanArray(CAPACITY)
    private var positions = DoubleArray(0)
    // In blocks per physics tick.
    private var velocities = DoubleArray(0)
    private var spare = DoubleArray(0)
    private var spareVelocities = DoubleArray(0)
    private var nodeIds = LongArray(0)
    private var mapping = IntArray(0)
    private var prevMapped = IntArray(0)
    private var nextMapped = IntArray(0)
    private val oldIndex = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
    private var revision = Long.MIN_VALUE
    private var head = 0
    private var size = 0

    var nodeCount = 0
        private set
    /** True while the only frame is a [freeze] shape, before any chain frame. */
    val frozen: Boolean get() = size == 1 && ticks[head] == Long.MIN_VALUE
    /** The physics tick of the newest chain frame, or -1 when there is none. */
    val newestTick: Long get() = if (size == 0 || frozen) -1L else ticks[head]

    /** Keeps [chain]'s current frame when it is newer than the newest kept; returns its tick, or -1 when not new. */
    fun push(chain: ClientChain, particles: QueryableParticleData<ClientVsParticle>?, stats: VineFrameStats): Long {
        val status = chain.status
        val tick = status.physicsTick
        if (size > 0 && tick <= ticks[head]) return -1L
        val ids = chain.nodeIds
        val count = ids.size
        if (count == 0) return -1L
        if (size == 0 || frozen || count != nodeCount || status.topologyRevision != revision) {
            if (size > 0 && !frozen && remap(ids, count)) stats.remaps++ else size = 0
            revision = status.topologyRevision
            nodeCount = count
            ensureCapacity(count)
            for (i in 0 until count) nodeIds[i] = ids[i].value
        }
        val slot = if (size == 0) 0 else (head + 1) % CAPACITY
        if (chain.copyNodePositions(positions, slot * count * 3) != count) return -1L
        hasVelocity[slot] = particles != null && copyVelocities(ids, particles, slot * count * 3)
        head = slot
        if (size < CAPACITY) size++
        ticks[head] = tick
        return tick
    }

    /** Keeps only the [count] nodes of [nodes] as one frame, which every later sample returns. */
    fun freeze(nodes: DoubleArray, count: Int) {
        require(count >= 2 && nodes.size >= count * 3) { "A frozen vine needs at least 2 of its nodes" }
        nodeCount = count
        ensureCapacity(count)
        nodes.copyInto(positions, 0, 0, count * 3)
        revision = Long.MIN_VALUE
        head = 0
        size = 1
        ticks[0] = Long.MIN_VALUE
        hasVelocity[0] = false
    }

    /**
     * Writes the nodes at physics tick [at] into [out]: a cubic Hermite blend between the two frames around it, or on
     * from the newest frame by its velocity, which fades over about [horizon] ticks. Returns the node count.
     */
    fun sample(at: Double, horizon: Double, out: DoubleArray): Int {
        if (size == 0) return 0
        val n = nodeCount * 3
        require(out.size >= n) { "Vine output holds fewer than $nodeCount nodes" }
        if (frozen) {
            positions.copyInto(out, 0, 0, n)
            return nodeCount
        }
        for (age in 0 until size) {
            val slot = slotOf(age)
            if (ticks[slot] <= at) {
                if (age == 0) extrapolate(at - ticks[slot], horizon, out) else hermite(age, at, out)
                return nodeCount
            }
        }
        val oldest = slotOf(size - 1)
        positions.copyInto(out, 0, oldest * n, oldest * n + n)
        return nodeCount
    }

    private fun slotOf(age: Int) = (head - age + CAPACITY) % CAPACITY

    private fun ensureCapacity(count: Int) {
        val need = CAPACITY * count * 3
        if (positions.size < need) {
            positions = DoubleArray(need)
            velocities = DoubleArray(need)
        }
        if (nodeIds.size < count) nodeIds = LongArray(count)
    }

    // VS publishes each node particle's velocity with the frame, in blocks per second.
    private fun copyVelocities(ids: List<ParticleId>, particles: QueryableParticleData<ClientVsParticle>, base: Int): Boolean {
        for (i in ids.indices) {
            val v = particles.getById(ids[i])?.velocity ?: return false
            val k = base + i * 3
            velocities[k] = v.x() * SECONDS_PER_PHYSICS_TICK
            velocities[k + 1] = v.y() * SECONDS_PER_PHYSICS_TICK
            velocities[k + 2] = v.z() * SECONDS_PER_PHYSICS_TICK
        }
        return true
    }

    /** Puts every kept frame into the node order of [ids]; false when too few nodes are shared to keep the history. */
    private fun remap(ids: List<ParticleId>, count: Int): Boolean {
        oldIndex.clear()
        for (i in 0 until nodeCount) oldIndex.put(nodeIds[i], i)
        if (mapping.size < count) {
            mapping = IntArray(count); prevMapped = IntArray(count); nextMapped = IntArray(count)
        }
        var shared = 0
        for (j in 0 until count) {
            mapping[j] = oldIndex.get(ids[j].value)
            if (mapping[j] >= 0) shared++
        }
        if (shared * 2 < minOf(count, nodeCount)) return false
        var last = -1
        for (j in 0 until count) { if (mapping[j] >= 0) last = j; prevMapped[j] = last }
        last = -1
        for (j in count - 1 downTo 0) { if (mapping[j] >= 0) last = j; nextMapped[j] = last }
        val need = CAPACITY * count * 3
        if (spare.size < need) {
            spare = DoubleArray(need)
            spareVelocities = DoubleArray(need)
        }
        for (age in 0 until size) remapFrame(slotOf(age), count)
        val p = positions; positions = spare; spare = p
        val v = velocities; velocities = spareVelocities; spareVelocities = v
        return true
    }

    // A new node takes the blend of its kept neighbours, by node order.
    private fun remapFrame(slot: Int, count: Int) {
        val from = slot * nodeCount * 3
        val to = slot * count * 3
        for (j in 0 until count) {
            val lo = prevMapped[j]
            val hi = nextMapped[j]
            val a = mapping[if (mapping[j] >= 0) j else if (lo >= 0) lo else hi]
            val b = mapping[if (mapping[j] >= 0) j else if (hi >= 0) hi else lo]
            val f = if (mapping[j] < 0 && lo >= 0 && hi >= 0) (j - lo).toDouble() / (hi - lo) else 0.0
            val i0 = from + a * 3
            val i1 = from + b * 3
            for (c in 0 until 3) {
                spare[to + j * 3 + c] = positions[i0 + c] + (positions[i1 + c] - positions[i0 + c]) * f
                spareVelocities[to + j * 3 + c] = velocities[i0 + c] + (velocities[i1 + c] - velocities[i0 + c]) * f
            }
        }
    }

    private fun hermite(olderAge: Int, at: Double, out: DoubleArray) {
        val a = slotOf(olderAge)
        val b = slotOf(olderAge - 1)
        val h = (ticks[b] - ticks[a]).toDouble()
        val s = (at - ticks[a]) / h
        val s2 = s * s; val s3 = s2 * s
        val h00 = 2 * s3 - 3 * s2 + 1
        val h10 = (s3 - 2 * s2 + s) * h
        val h01 = -2 * s3 + 3 * s2
        val h11 = (s3 - s2) * h
        val oa = a * nodeCount * 3
        val ob = b * nodeCount * 3
        for (i in 0 until nodeCount * 3) {
            out[i] = h00 * positions[oa + i] + h10 * tangent(olderAge, i) +
                h01 * positions[ob + i] + h11 * tangent(olderAge - 1, i)
        }
    }

    private fun extrapolate(dt: Double, horizon: Double, out: DoubleArray) {
        val tau = horizon * EXTRAPOLATE_FADE
        val run = tau * (1.0 - exp(-dt / tau))
        val o = head * nodeCount * 3
        for (i in 0 until nodeCount * 3) out[i] = positions[o + i] + tangent(0, i) * run
    }

    /** Value [i] of the frame at [age]'s velocity: VS's own, or a difference of its kept neighbour frames. */
    private fun tangent(age: Int, i: Int): Double {
        val slot = slotOf(age)
        if (hasVelocity[slot]) return velocities[slot * nodeCount * 3 + i]
        val newer = if (age > 0) slotOf(age - 1) else slot
        val older = if (age + 1 < size) slotOf(age + 1) else slot
        if (newer == older) return 0.0
        val n = nodeCount * 3
        return (positions[newer * n + i] - positions[older * n + i]) / (ticks[newer] - ticks[older])
    }

    private companion object {
        /** Holds MAX_DELAY_NANOS of frames at one frame a game tick, with room for a burst. */
        const val CAPACITY = 16
        /** VS default physics step: 3 a game tick. */
        const val SECONDS_PER_PHYSICS_TICK = 1.0 / 60.0
        /** Extrapolation time constant, in horizons: the run goes on nearly straight for a gap, then levels off. */
        const val EXTRAPOLATE_FADE = 2.0
    }
}
