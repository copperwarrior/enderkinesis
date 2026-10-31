package org.shipwrights.enderkinesis.client

import net.minecraft.world.level.Level

/**
 * A Float of raw gameTime loses sub-tick precision after about 14 hours, so animations step.
 * Times count from a local base. Only [forget] or [REBASE_TICKS] moves the base, and both clear the visuals.
 */
object SpellClock {
    private const val UNSET = Long.MIN_VALUE
    private const val REBASE_TICKS = 1L shl 20

    // Render thread only.
    private var base = UNSET
    private var handTicks = 0L

    fun now(level: Level): Long {
        val t = level.gameTime
        if (base == UNSET) base = t
        else if (t - base !in 0..REBASE_TICKS) {
            // Visuals timed from the old base become wrong, so clear them.
            SpellVfxClient.reset()
            base = t
        }
        return t - base
    }

    fun time(level: Level, partialTick: Float): Float = now(level) + partialTick

    /** [gameTime] includes the partial tick. */
    fun of(gameTime: Double): Float {
        if (base == UNSET) base = gameTime.toLong()
        return (gameTime - base).toFloat()
    }

    fun forget() {
        base = UNSET
    }

    /** Call once for each client level tick, and not while paused. */
    fun tick() {
        handTicks++
    }

    /** Client ticks never go back, so first-person hand motions do not jump when the world time changes. */
    fun hand(partialTick: Float): Double = handTicks + partialTick.toDouble()
}
