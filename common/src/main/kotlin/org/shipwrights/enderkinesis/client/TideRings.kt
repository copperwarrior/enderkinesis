package org.shipwrights.enderkinesis.client

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/** One ring wave that a Shell of Tides call sends out from a hull. [sign] is +1 crest-first, -1 trough-first. */
data class TideRing(val centerX: Double, val centerZ: Double, val startTick: Double, val sign: Double)

/** Visual-only ring waves on the lattice mesh. Buoyancy and the fluid probe do not see them. */
object TideRings {

    // Client thread only: the shell calls from the client tick, and the renderer snapshots in the frame.
    private val live = ArrayDeque<TideRing>()

    private const val MAX_RINGS = 8
    private const val SPEED_BLOCKS_PER_TICK = 0.6
    private const val LIFE_TICKS = 60.0
    private const val HALF_WIDTH = 2.5
    private const val AMPLITUDE = 0.9
    /** Share of the ring height and slope that goes to the shading. The full ring would over-light the crests. */
    const val SHADE_SCALE = 0.35 / AMPLITUDE
    private const val WAVE_NUMBER = 2.0 * Math.PI / 6.0
    private const val REACH_HALF_WIDTHS = 2.5
    private const val SLOPE_STEP = 0.25

    fun call(centerX: Double, centerZ: Double, tick: Double, sign: Double) {
        if (live.size >= MAX_RINGS) live.removeFirst()
        live.addLast(TideRing(centerX, centerZ, tick, sign))
    }

    /** A copy for the mesh worker, because the worker can still read the last one. */
    fun snapshot(tick: Double): List<TideRing> {
        while (live.isNotEmpty() && tick - live.first().startTick > LIFE_TICKS) live.removeFirst()
        return if (live.isEmpty()) emptyList() else live.toList()
    }

    /** Central-difference height gradient into [out] as (dh/dx, dh/dz). */
    fun slopeAt(x: Double, z: Double, tick: Double, rings: List<TideRing>, out: DoubleArray) {
        out[0] = (heightAt(x + SLOPE_STEP, z, tick, rings) - heightAt(x - SLOPE_STEP, z, tick, rings)) / (2.0 * SLOPE_STEP)
        out[1] = (heightAt(x, z + SLOPE_STEP, tick, rings) - heightAt(x, z - SLOPE_STEP, tick, rings)) / (2.0 * SLOPE_STEP)
    }

    fun heightAt(x: Double, z: Double, tick: Double, rings: List<TideRing>): Double {
        if (rings.isEmpty()) return 0.0
        var sum = 0.0
        for (i in rings.indices) {
            val ring = rings[i]
            val age = tick - ring.startTick
            if (age < 0.0 || age > LIFE_TICKS) continue
            val dx = x - ring.centerX
            val dz = z - ring.centerZ
            val d = sqrt(dx * dx + dz * dz) - age * SPEED_BLOCKS_PER_TICK
            if (d > HALF_WIDTH * REACH_HALF_WIDTHS || d < -HALF_WIDTH * REACH_HALF_WIDTHS) continue
            val envelope = exp(-(d * d) / (HALF_WIDTH * HALF_WIDTH)) * (1.0 - age / LIFE_TICKS)
            sum += ring.sign * AMPLITUDE * envelope * cos(WAVE_NUMBER * d)
        }
        return sum
    }
}
