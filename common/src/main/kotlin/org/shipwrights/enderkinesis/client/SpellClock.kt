package org.shipwrights.enderkinesis.client

import net.minecraft.world.level.Level

/**
 * The clocks spell visuals keep time by, kept small enough that a Float still counts fractions of
 * a tick.
 *
 * A Float holds `gameTime + partialTick` to the nearest 1/8 tick once a world is about 14 hours
 * old, and to the nearest tick after a few days, so animations made from it start to step. Here
 * world time is counted from a base taken when visuals start on this client instead: [now] for
 * when things start and end (whole ticks), [time] for drawing them (with the partial tick).
 * Starts kept from [now] and frames drawn at [time] share the base, so `time - start` is exact.
 * The base moves on only when the visuals are cleared ([forget], on a world change) or after
 * [REBASE_TICKS], which clears them too.
 *
 * [hand] is the client's own tick count, for the first-person hand's motions: it never goes back,
 * whatever happens to the player or the world.
 */
object SpellClock {
    private const val UNSET = Long.MIN_VALUE
    /** About 14.5 hours of world time: past this a Float of it would step by an eighth of a tick. */
    private const val REBASE_TICKS = 1L shl 20

    // Render thread only.
    private var base = UNSET
    private var handTicks = 0L

    /** World ticks since the base, for when spell visuals start and end. */
    fun now(level: Level): Long {
        val t = level.gameTime
        if (base == UNSET) base = t
        else if (t - base !in 0..REBASE_TICKS) {
            // Everything timed from the old base goes with it.
            SpellVfxClient.reset()
            base = t
        }
        return t - base
    }

    /** [now] with the frame's partial tick: the time to draw spell visuals at. */
    fun time(level: Level, partialTick: Float): Float = now(level) + partialTick

    /** A world time with its partial tick ([gameTime], as callers outside these visuals keep it)
     *  on the same base as [time]. */
    fun of(gameTime: Double): Float {
        if (base == UNSET) base = gameTime.toLong()
        return (gameTime - base).toFloat()
    }

    /** The world forgot its visuals: the next [now] starts a new base. */
    fun forget() {
        base = UNSET
    }

    /** Once per client level tick (not while paused). */
    fun tick() {
        handTicks++
    }

    /** The client's ticks, with [partialTick]: for the first-person hand's motions. */
    fun hand(partialTick: Float): Double = handTicks + partialTick.toDouble()
}
