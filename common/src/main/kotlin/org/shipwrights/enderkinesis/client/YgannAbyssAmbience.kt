package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import org.shipwrights.enderkinesis.registry.EKSounds

/**
 * Ygann Abyss ambience: a continuous looping base track that runs the whole time you're in-dimension,
 * plus drone and chant cues that surface uncommonly on top of it — in the spirit of vanilla cave
 * sounds: rare, spaced, unsettling. The chants self-fade in their audio files; cues are short enough
 * that the engine's natural start/stop is fine.
 *
 * Client-only and stateless on the world. A per-session timer schedules the next cue a random gap
 * ([GAP_MIN]..[GAP_MAX] ticks) ahead, with a shorter first delay after entering. A new cue never
 * starts while the previous one is still playing (the long chants would otherwise stack). Everything
 * is non-directional (attenuation NONE, [SoundSource.AMBIENT]) so it surrounds the player.
 */
object YgannAbyssAmbience {

    /** Spacing between cue plays (ticks; 20/s) — ~70–180 s, so cues read as "uncommon". */
    private const val GAP_MIN: Long = 1400L
    private const val GAP_MAX: Long = 3600L

    /** Shorter delay for the first cue after entering the dimension, for prompt feedback. */
    private const val FIRST_MIN: Long = 200L
    private const val FIRST_MAX: Long = 900L

    /** Fraction of cues that are the (rarer, creepier) chanting rather than the drone. */
    private const val CHANT_CHANCE: Double = 0.3

    private const val LOOP_VOLUME: Float = 0.45f
    private const val DRONE_VOLUME: Float = 0.65f
    private const val CHANT_VOLUME: Float = 0.5f

    /** Per-cue pitch jitter (±) so repeats don't sound identical. Kept subtle; loop stays at pitch 1. */
    private const val PITCH_VARIANCE: Float = 0.06f

    private var scheduled = false
    private var nextPlayTick = 0L
    private var current: SoundInstance? = null
    private var loop: SoundInstance? = null

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        if (level.dimension() != YgannAbyss.LEVEL_KEY) {
            // Leaving: stop the continuous loop and any in-flight cue; re-arm the first delay.
            loop?.let { mc.soundManager.stop(it) }
            current?.let { mc.soundManager.stop(it) }
            loop = null
            current = null
            scheduled = false
            return
        }
        if (mc.player == null) return
        val rng = level.random

        // Keep the continuous base loop running while in-dimension.
        if (loop.let { it == null || !mc.soundManager.isActive(it) }) {
            val l = SimpleSoundInstance(
                EKSounds.YGANN_AMBIENCE_LOOP.get().location, SoundSource.AMBIENT, LOOP_VOLUME, 1.0f, rng,
                true, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
            )
            mc.soundManager.play(l)
            loop = l
        }

        val now = level.gameTime
        if (!scheduled) {
            nextPlayTick = now + FIRST_MIN + rng.nextInt((FIRST_MAX - FIRST_MIN).toInt())
            scheduled = true
            return
        }
        if (now < nextPlayTick) return
        // Wait for any in-progress cue to finish before starting the next — no stacking.
        current?.let { if (mc.soundManager.isActive(it)) return }

        val chant = rng.nextDouble() < CHANT_CHANCE
        val event = if (chant) EKSounds.YGANN_CHANT.get() else EKSounds.YGANN_DRONE.get()
        val volume = if (chant) CHANT_VOLUME else DRONE_VOLUME
        val pitch = 1.0f + (rng.nextFloat() - 0.5f) * 2.0f * PITCH_VARIANCE
        val sound = SimpleSoundInstance(
            event.location, SoundSource.AMBIENT, volume, pitch, rng,
            false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
        )
        mc.soundManager.play(sound)
        current = sound
        nextPlayTick = now + GAP_MIN + rng.nextInt((GAP_MAX - GAP_MIN).toInt())
    }
}
