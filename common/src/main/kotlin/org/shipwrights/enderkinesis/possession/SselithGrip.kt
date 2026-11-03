package org.shipwrights.enderkinesis.possession

import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.dimension.SselithMadness

/**
 * Decides each server tick if the Sselith Madness holds one player's body. Server thread only.
 *
 * Level 4 holds the body only while a screen is open. From level 5 the madness also starts
 * episodes with no screen open. The player fights back with new presses of the movement,
 * jump and sneak keys, and with mouse movement; the client reports that effort. Level 10 holds the body at all times.
 */
class SselithGrip {

    /**
     * @param gapTicks time in control before the next random episode.
     * @param holdTicks time before the episode ends by itself; null = only a break-free ends it.
     * @param pressesToBreak struggle value that ends the episode.
     * @param decayPerTick struggle lost each tick.
     * @param idleGraceTicks time after an episode ends in which the idle trigger cannot start a new one.
     */
    private class Rule(
        val gapTicks: IntRange,
        val holdTicks: IntRange?,
        val pressesToBreak: Float,
        val decayPerTick: Float,
        val idleGraceTicks: Int,
    )

    /** True while an episode holds the body; the cue sounds follow this. */
    var episode = false
        private set

    /** 0..1: how near the player is to breaking free. */
    var struggleFraction = 0f
        private set

    /** Set from the client's report. A client that hides its screens only escapes the level 4 walk. */
    var screenOpen = false

    private var timer = 0
    private var timerLevel = -1
    private var struggle = 0f
    private var effortThisTick = 0f
    private var idleTicks = 0
    private var idleGrace = 0
    private var lastX = Double.NaN
    private var lastZ = Double.NaN

    /** Adds client-reported effort. The cap per tick is what a real player can do: all six keys and a fast mouse. */
    fun addEffort(effort: Float) {
        if (!effort.isFinite()) return
        effortThisTick = (effortThisTick + effort.coerceAtLeast(0f)).coerceAtMost(MAX_EFFORT_PER_TICK)
    }

    /** True while the madness drives the body. */
    fun tick(player: ServerPlayer): Boolean {
        val effort = effortThisTick
        effortThisTick = 0f
        val moved = moved(player)
        val level = SselithMadness.level(player)
        if (level < EPISODE_LEVEL) {
            episode = false
            struggleFraction = 0f
            timerLevel = -1
            return level >= MENU_LEVEL && screenOpen
        }
        if (level >= SselithMadness.MAX_LEVEL) {
            struggleFraction = 0f
            return true
        }
        val rule = RULES[level - EPISODE_LEVEL]
        if (timerLevel != level) {
            timerLevel = level
            timer = roll(player, if (episode) rule.holdTicks else rule.gapTicks)
        }
        if (episode) tickHeld(player, rule, effort) else tickFree(player, rule, level, moved)
        return episode || screenOpen
    }

    private fun tickFree(player: ServerPlayer, rule: Rule, level: Int, moved: Boolean) {
        // In a screen the madness moves the body, so a stop there is not the player's.
        val idle = !moved && !screenOpen
        idleTicks = if (idle) idleTicks + 1 else 0
        if (idleGrace > 0) idleGrace--
        val idleTooLong = level >= IDLE_LEVEL && idleGrace == 0 && idleTicks > IDLE_TRIGGER_TICKS
        if (--timer > 0 && !idleTooLong) return
        episode = true
        struggle = 0f
        timer = roll(player, rule.holdTicks)
    }

    private fun tickHeld(player: ServerPlayer, rule: Rule, effort: Float) {
        struggle = (struggle - rule.decayPerTick).coerceAtLeast(0f) + effort
        val timedOut = rule.holdTicks != null && --timer <= 0
        if (struggle < rule.pressesToBreak && !timedOut) {
            struggleFraction = struggle / rule.pressesToBreak
            return
        }
        episode = false
        struggleFraction = 0f
        idleTicks = 0
        idleGrace = rule.idleGraceTicks
        timer = roll(player, rule.gapTicks)
    }

    private fun roll(player: ServerPlayer, range: IntRange?): Int =
        if (range == null) 0 else range.first + player.random.nextInt(range.last - range.first + 1)

    private fun moved(player: ServerPlayer): Boolean {
        val dx = player.x - lastX
        val dz = player.z - lastZ
        lastX = player.x
        lastZ = player.z
        return !(dx * dx + dz * dz <= MOVED_EPSILON_SQ)
    }

    private companion object {
        const val MENU_LEVEL = 4
        const val EPISODE_LEVEL = 5
        const val IDLE_LEVEL = 6
        const val IDLE_TRIGGER_TICKS = 5 * 20
        const val MOVED_EPSILON_SQ = 1.0e-6
        const val MAX_EFFORT_PER_TICK = 6.5f

        /** Index 0 = level 5 ... index 4 = level 9. */
        val RULES = arrayOf(
            Rule(gapTicks = 3600..9600, holdTicks = null, pressesToBreak = 8f, decayPerTick = 0.05f, idleGraceTicks = 1200),
            Rule(gapTicks = 3600..9600, holdTicks = null, pressesToBreak = 8f, decayPerTick = 0.05f, idleGraceTicks = 1200),
            Rule(gapTicks = 1200..3600, holdTicks = null, pressesToBreak = 16f, decayPerTick = 0.1f, idleGraceTicks = 700),
            Rule(gapTicks = 400..1200, holdTicks = 400..1200, pressesToBreak = 24f, decayPerTick = 0.1f, idleGraceTicks = 400),
            Rule(gapTicks = 160..320, holdTicks = 1200..2400, pressesToBreak = 40f, decayPerTick = 0.15f, idleGraceTicks = 240),
        )
    }
}
