package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.player.Input
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import org.shipwrights.enderkinesis.dimension.SselithMadness

/**
 * Decides each client tick if the Sselith Madness holds the local player's body.
 *
 * Level 4 holds the body only while a screen is open. From level 5 the madness also starts
 * episodes with no screen open. The player fights back with new presses of the movement,
 * jump and sneak keys, and with mouse movement; a held key does not count. Level 10 holds the body at all times.
 */
object SselithControlLoss {

    private const val MENU_LEVEL = 4
    private const val EPISODE_LEVEL = 5
    private const val IDLE_LEVEL = 6
    private const val IDLE_TRIGGER_TICKS = 5 * 20
    private const val MOVED_EPSILON_SQ = 1.0e-6

    /** Cursor travel that counts as one key press, and the most that one tick can add. */
    private const val MOUSE_PIXELS_PER_PRESS = 800.0
    private const val MOUSE_MAX_PER_TICK = 0.5f

    /** Intensity of the tome-summon shaders while the madness holds the body. 1 = the full effect. */
    private const val VIGNETTE_MAX = 0.2f
    private const val VIGNETTE_FADE_TICKS = 40
    private const val VIGNETTE_STEP = VIGNETTE_MAX / VIGNETTE_FADE_TICKS

    /** The vignette follows the struggle faster than the plain fade, so each press shows. */
    private const val VIGNETTE_STRUGGLE_STEP = VIGNETTE_STEP * 4

    /**
     * @param gapTicks time in control before the next random episode.
     * @param holdTicks time before the episode ends by itself; null = only a break-free ends it.
     * @param pressesToBreak struggle value that ends the episode.
     * @param decayPerTick struggle lost each tick.
     * @param idleGraceTicks time after an episode ends in which the idle trigger cannot start a new one.
     */
    private class Grip(
        val gapTicks: IntRange,
        val holdTicks: IntRange?,
        val pressesToBreak: Float,
        val decayPerTick: Float,
        val idleGraceTicks: Int,
    )

    /** Index 0 = level 5 ... index 4 = level 9. */
    private val GRIPS = arrayOf(
        Grip(gapTicks = 3600..9600, holdTicks = null, pressesToBreak = 8f, decayPerTick = 0.05f, idleGraceTicks = 1200),
        Grip(gapTicks = 3600..9600, holdTicks = null, pressesToBreak = 8f, decayPerTick = 0.05f, idleGraceTicks = 1200),
        Grip(gapTicks = 1200..3600, holdTicks = null, pressesToBreak = 16f, decayPerTick = 0.1f, idleGraceTicks = 700),
        Grip(gapTicks = 400..1200, holdTicks = 400..1200, pressesToBreak = 24f, decayPerTick = 0.1f, idleGraceTicks = 400),
        Grip(gapTicks = 160..320, holdTicks = 1200..2400, pressesToBreak = 40f, decayPerTick = 0.15f, idleGraceTicks = 240),
    )

    private var held = false
    private var timer = 0
    private var timerLevel = -1
    private var struggle = 0f
    private var struggleFraction = 0f
    private var idleTicks = 0
    private var idleGrace = 0
    private var lastKeys = 0
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var vignette = 0f
    private var vignetteO = 0f

    fun onDisconnect() {
        held = false
        timer = 0
        timerLevel = -1
        struggle = 0f
        struggleFraction = 0f
        idleTicks = 0
        idleGrace = 0
        lastKeys = 0
        vignette = 0f
        vignetteO = 0f
    }

    /** Call one time each tick after `Input.tick` and before the caller overwrites the input. */
    fun tick(player: LocalPlayer): Boolean {
        val keys = keyMask(player.input)
        val effort = Integer.bitCount(keys and lastKeys.inv()) + mouseEffort()
        lastKeys = keys

        val level = SselithMadness.level(player)
        if (level < EPISODE_LEVEL) {
            held = false
            struggleFraction = 0f
            timerLevel = -1
            return level >= MENU_LEVEL && Minecraft.getInstance().screen != null
        }
        if (level >= SselithMadness.MAX_LEVEL) {
            struggleFraction = 0f
            return true
        }

        val grip = GRIPS[level - EPISODE_LEVEL]
        if (timerLevel != level) {
            timerLevel = level
            timer = roll(player, if (held) grip.holdTicks else grip.gapTicks)
        }
        if (held) tickHeld(player, grip, effort) else tickFree(player, grip, level)
        return held || Minecraft.getInstance().screen != null
    }

    /** Call one time each tick with the result of [tick]. */
    fun tickVignette(lost: Boolean) {
        vignetteO = vignette
        val target = if (lost) VIGNETTE_MAX * (1f - struggleFraction) else 0f
        val step = if (struggleFraction > 0f) VIGNETTE_STRUGGLE_STEP else VIGNETTE_STEP
        vignette += (target - vignette).coerceIn(-step, step)
    }

    fun vignetteIntensity(partialTick: Float): Float = Mth.lerp(partialTick, vignetteO, vignette)

    private fun tickFree(player: LocalPlayer, grip: Grip, level: Int) {
        // In a screen the madness moves the body, so a stop there is not the player's.
        val idle = !moved(player) && Minecraft.getInstance().screen == null
        idleTicks = if (idle) idleTicks + 1 else 0
        if (idleGrace > 0) idleGrace--
        val idleTooLong = level >= IDLE_LEVEL && idleGrace == 0 && idleTicks > IDLE_TRIGGER_TICKS
        if (--timer > 0 && !idleTooLong) return
        held = true
        struggle = 0f
        timer = roll(player, grip.holdTicks)
    }

    private fun tickHeld(player: LocalPlayer, grip: Grip, effort: Float) {
        struggle = (struggle - grip.decayPerTick).coerceAtLeast(0f) + effort
        val timedOut = grip.holdTicks != null && --timer <= 0
        if (struggle < grip.pressesToBreak && !timedOut) {
            struggleFraction = struggle / grip.pressesToBreak
            return
        }
        held = false
        struggleFraction = 0f
        idleTicks = 0
        idleGrace = grip.idleGraceTicks
        timer = roll(player, grip.gapTicks)
    }

    private fun roll(player: LocalPlayer, range: IntRange?): Int =
        if (range == null) 0 else range.first + player.random.nextInt(range.last - range.first + 1)

    // The cursor position stays live while the mouse is grabbed, and the walk never writes it.
    private fun mouseEffort(): Float {
        val mouse = Minecraft.getInstance().mouseHandler
        val dx = mouse.xpos() - lastMouseX
        val dy = mouse.ypos() - lastMouseY
        lastMouseX = mouse.xpos()
        lastMouseY = mouse.ypos()
        return (Math.sqrt(dx * dx + dy * dy) / MOUSE_PIXELS_PER_PRESS).toFloat().coerceAtMost(MOUSE_MAX_PER_TICK)
    }

    private fun moved(player: LocalPlayer): Boolean {
        val dx = player.x - player.xo
        val dz = player.z - player.zo
        return dx * dx + dz * dz > MOVED_EPSILON_SQ
    }

    private fun keyMask(input: Input): Int =
        (if (input.up) 1 else 0) or
            (if (input.down) 2 else 0) or
            (if (input.left) 4 else 0) or
            (if (input.right) 8 else 0) or
            (if (input.jumping) 16 else 0) or
            (if (input.shiftKeyDown) 32 else 0)
}
