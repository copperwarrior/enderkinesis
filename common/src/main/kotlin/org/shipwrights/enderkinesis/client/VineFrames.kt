package org.shipwrights.enderkinesis.client

import org.valkyrienskies.core.api.chains.ClientChain
import kotlin.math.abs

/**
 * Maps client wall time (System.nanoTime) to the VS physics tick that the vines show. The physics tick of each frame
 * is the server's own time stamp; the game tick publishes a varying count of physics ticks, and the client applies
 * frames only at its own tick end, so arrival times are uneven but the stamps are not.
 */
internal class VineFrameClock {
    private var ticksPerNano = NOMINAL_TICKS_PER_NANO
    private var epoch = 0L
    // Smoothed (physics tick - wall since epoch * rate) at frame arrival.
    private var offset = 0.0
    private var jitter = 0.0
    private var gap = NOMINAL_GAP
    private var delay = MIN_DELAY
    private var newestTick = -1L
    private var rateTick = 0L
    private var rateWall = 0L
    private var play = 0.0
    private var playWall = 0L

    val isStarted get() = newestTick >= 0

    /** Notes that frame [tick] was first seen at [wall]. */
    fun observe(tick: Long, wall: Long) {
        if (tick == newestTick) return
        if (newestTick < 0 || newestTick - tick > SNAP_TICKS) {
            reset(tick, wall)
            return
        }
        // A server slower than the clock can resend an older frame; it holds nothing new.
        if (tick < newestTick) return
        val err = tick - since(wall) * ticksPerNano - offset
        // A world change, a pause or a long stall: start again instead of a long catch-up.
        if (abs(err) > SNAP_TICKS) {
            reset(tick, wall)
            return
        }
        gap += ((tick - newestTick).toDouble().coerceAtMost(MAX_DELAY) - gap) * GAP_GAIN
        newestTick = tick
        offset += err * OFFSET_GAIN
        jitter += (abs(err) - jitter) * JITTER_GAIN
        delay = (gap + JITTER_MARGIN * jitter).coerceIn(MIN_DELAY, MAX_DELAY)
        if (wall - rateWall >= RATE_WINDOW_NANOS) estimateRate(tick, wall)
    }

    /** Moves the shown tick on to [wall], slewing toward the target delay; returns the shown physics tick. */
    fun advance(wall: Long): Double {
        if (!isStarted || wall <= playWall) return play
        val target = offset + since(wall) * ticksPerNano - delay
        val speed = 1.0 + ((target - play) * SLEW_GAIN).coerceIn(-MAX_SLEW, MAX_SLEW)
        play += (wall - playWall) * ticksPerNano * speed
        playWall = wall
        if (abs(target - play) > SNAP_TICKS) play = target
        return play
    }

    /** The physics tick shown at [wall], on from the last [advance] at the current rate; it changes no state. */
    fun at(wall: Long): Double = if (isStarted) play + (wall - playWall) * ticksPerNano else play

    fun clear() {
        newestTick = -1L
        ticksPerNano = NOMINAL_TICKS_PER_NANO
        gap = NOMINAL_GAP
        delay = MIN_DELAY
    }

    private fun since(wall: Long) = (wall - epoch).toDouble()

    // A server that runs slow publishes fewer physics ticks a second; follow it, or the buffer runs dry.
    private fun estimateRate(tick: Long, wall: Long) {
        val measured = (tick - rateTick) / (wall - rateWall).toDouble()
        val next = (ticksPerNano + (measured - ticksPerNano) * RATE_GAIN).coerceIn(MIN_TICKS_PER_NANO, MAX_TICKS_PER_NANO)
        offset += since(wall) * (ticksPerNano - next)
        ticksPerNano = next
        rateTick = tick
        rateWall = wall
    }

    private fun reset(tick: Long, wall: Long) {
        epoch = wall
        offset = tick.toDouble()
        jitter = 0.0
        newestTick = tick
        rateTick = tick
        rateWall = wall
        play = tick - delay
        playWall = wall
    }

    private companion object {
        /** VS default: 3 physics ticks a game tick at 20 game ticks a second. */
        const val NOMINAL_TICKS_PER_NANO = 60.0 / 1e9
        const val MIN_TICKS_PER_NANO = 10.0 / 1e9
        const val MAX_TICKS_PER_NANO = 120.0 / 1e9
        const val NOMINAL_GAP = 3.0
        /** In physics ticks: one game tick at the least, as vanilla entity interpolation. */
        const val MIN_DELAY = 3.0
        const val MAX_DELAY = 18.0
        const val SNAP_TICKS = 60.0
        const val JITTER_MARGIN = 2.0
        const val GAP_GAIN = 0.1
        const val OFFSET_GAIN = 0.1
        const val JITTER_GAIN = 0.1
        const val RATE_GAIN = 0.5
        const val RATE_WINDOW_NANOS = 2_000_000_000L
        /** Speed change per physics tick of error; MAX_SLEW keeps the shown motion within 10 % of real speed. */
        const val SLEW_GAIN = 0.05
        const val MAX_SLEW = 0.1
    }
}

/** A ring of one vine's last chain frames, stamped with their physics ticks. */
internal class VineFrames {
    private val ticks = LongArray(CAPACITY)
    private var positions = DoubleArray(0)
    private var revision = Long.MIN_VALUE
    private var head = 0
    private var size = 0

    var nodeCount = 0
        private set

    /** Keeps [chain]'s current frame when it is newer than the newest kept; returns its tick, or -1 when not new. */
    fun push(chain: ClientChain): Long {
        val status = chain.status
        val tick = status.physicsTick
        if (size > 0 && tick <= ticks[head]) return -1L
        val slot = if (size == 0) 0 else (head + 1) % CAPACITY
        val count = chain.copyNodePositions(positions, slot * nodeCount * 3)
        // A new node list does not blend with the old one; VS2 also snaps it.
        if (count != nodeCount || status.topologyRevision != revision || size == 0) {
            nodeCount = count
            revision = status.topologyRevision
            if (positions.size < CAPACITY * count * 3) positions = DoubleArray(CAPACITY * count * 3)
            if (chain.copyNodePositions(positions, 0) != count) return -1L
            head = 0
            size = 1
        } else {
            head = slot
            if (size < CAPACITY) size++
        }
        ticks[head] = tick
        return tick
    }

    /**
     * Writes the nodes at physics tick [at] into [out]: blended between the two frames around it, or on from the
     * newest frame by its velocity for at most [extrapolate] ticks. Returns the node count; [out] must hold them.
     */
    fun sample(at: Double, extrapolate: Double, out: DoubleArray): Int {
        if (size == 0) return 0
        val n = nodeCount * 3
        require(out.size >= n) { "Vine output holds fewer than $nodeCount nodes" }
        var newer = head
        for (i in 0 until size) {
            val slot = (head - i + CAPACITY) % CAPACITY
            val t = ticks[slot]
            if (t <= at) {
                if (i == 0) return extrapolateFrom(slot, at, extrapolate, out)
                val f = (at - t) / (ticks[newer] - t)
                blend(slot, newer, f, out)
                return nodeCount
            }
            newer = slot
        }
        positions.copyInto(out, 0, newer * n, newer * n + n)
        return nodeCount
    }

    private fun extrapolateFrom(slot: Int, at: Double, extrapolate: Double, out: DoubleArray): Int {
        val n = nodeCount * 3
        if (size < 2) {
            positions.copyInto(out, 0, slot * n, slot * n + n)
            return nodeCount
        }
        val prev = (slot - 1 + CAPACITY) % CAPACITY
        val f = 1.0 + (at - ticks[slot]).coerceAtMost(extrapolate) / (ticks[slot] - ticks[prev])
        blend(prev, slot, f, out)
        return nodeCount
    }

    private fun blend(a: Int, b: Int, f: Double, out: DoubleArray) {
        val n = nodeCount * 3
        val oa = a * n
        val ob = b * n
        for (i in 0 until n) out[i] = positions[oa + i] + (positions[ob + i] - positions[oa + i]) * f
    }

    private companion object {
        /** Holds MAX_DELAY of frames at one frame a game tick, with room for a burst. */
        const val CAPACITY = 12
    }
}
