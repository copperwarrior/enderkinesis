package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKSounds

/**
 * Sselith ambience — three layered systems, each independently scheduled:
 *
 *  - **Dimension loop** ([EKSounds.SSELITH_AMBIENT_LOOP]): continuous base
 *    track, plays the entire time the player is in Sselith's Repertory.
 *    Stopped immediately on dimension exit so it doesn't bleed.
 *  - **Dimension murmurs** ([EKSounds.SSELITH_MURMUR]): the original
 *    uncommon murmur cues, spaced 70–180 s apart, in-dimension only. The
 *    event holds 10 takes; the sound manager picks one each play.
 *  - **Madness whispers** ([EKSounds.SSELITH_WHISPERS]): driven entirely
 *    by the player's Sselith Madness level — *not* the dimension. At
 *    level 0 they're silent; from 1 to 5 the gap shrinks from rare
 *    (≈90–180 s) to near-constant (≈2–5 s). The event holds the five
 *    whisper takes; sound manager randomises which one plays.
 *
 * Whispers follow madness across dimension boundaries (decay outside
 * Sselith is intentional — the dimension follows you home). The loop
 * and murmurs are in-dimension only.
 *
 * Client-only, stateless on the world. All three layers are
 * non-directional ([SoundSource] AMBIENT, attenuation NONE) so they
 * surround the player.
 */
object SselithAmbience {

    // ── Dimension murmur cadence (unchanged from the original) ──────────
    private const val MURMUR_GAP_MIN: Long = 1400L
    private const val MURMUR_GAP_MAX: Long = 3600L
    private const val MURMUR_FIRST_MIN: Long = 200L
    private const val MURMUR_FIRST_MAX: Long = 900L
    private const val MURMUR_VOLUME: Float = 0.6f

    // ── Madness-keyed whisper cadence ───────────────────────────────────
    /** Per-madness-level [min..max) gap between whisper plays, in ticks
     *  (20/s). Index 0 = level 1 ... index 4 = level 5. Bands shrink as
     *  level rises so the cadence ramps from rare to near-constant. */
    private val WHISPER_GAP_BANDS = arrayOf(
        1800L to 3600L,   // L1 — 90–180 s
        900L to 1800L,    // L2 — 45–90 s
        400L to 800L,     // L3 — 20–40 s
        160L to 320L,     // L4 — 8–16 s
        40L to 100L,      // L5 — 2–5 s (near-constant)
    )

    /** Shorter delay for the first whisper after a madness step, so the
     *  player gets prompt feedback when the level changes. */
    private const val WHISPER_FIRST_MIN: Long = 100L
    private const val WHISPER_FIRST_MAX: Long = 400L

    private const val WHISPER_VOLUME: Float = 0.7f
    private const val LOOP_VOLUME: Float = 0.5f

    /** Per-cue pitch jitter (±) so repeats don't sound identical. Subtle;
     *  loop stays at pitch 1.0. */
    private const val PITCH_VARIANCE: Float = 0.05f

    private var loop: SoundInstance? = null

    private var murmurScheduled = false
    private var murmurNextTick = 0L
    private var currentMurmur: SoundInstance? = null

    private var currentWhisper: SoundInstance? = null
    private var nextWhisperTick = 0L
    /** Level the whisper schedule was last armed against. A mismatch means
     *  the level changed since we picked a gap → reroll with the new band
     *  on the next tick so the cadence reacts immediately. */
    private var whisperScheduledForLevel = -1

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val rng = level.random
        val now = level.gameTime
        val inSselith = level.dimension() == SselithRepertory.LEVEL_KEY

        // ── Loop: in-dimension only ─────────────────────────────────────
        if (inSselith) {
            if (loop.let { it == null || !mc.soundManager.isActive(it) }) {
                val l = SimpleSoundInstance(
                    EKSounds.SSELITH_AMBIENT_LOOP.get().location, SoundSource.AMBIENT,
                    LOOP_VOLUME, 1.0f, rng,
                    true, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
                )
                mc.soundManager.play(l)
                loop = l
            }
        } else {
            loop?.let { mc.soundManager.stop(it) }
            loop = null
        }

        // ── Murmurs: in-dimension only ──────────────────────────────────
        if (inSselith) {
            if (!murmurScheduled) {
                murmurNextTick = now + MURMUR_FIRST_MIN + rng.nextInt((MURMUR_FIRST_MAX - MURMUR_FIRST_MIN).toInt())
                murmurScheduled = true
            } else if (now >= murmurNextTick &&
                currentMurmur.let { it == null || !mc.soundManager.isActive(it) }
            ) {
                val pitch = 1.0f + (rng.nextFloat() - 0.5f) * 2.0f * PITCH_VARIANCE
                val s = SimpleSoundInstance(
                    EKSounds.SSELITH_MURMUR.get().location, SoundSource.AMBIENT, MURMUR_VOLUME, pitch, rng,
                    false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
                )
                mc.soundManager.play(s)
                currentMurmur = s
                murmurNextTick = now + MURMUR_GAP_MIN + rng.nextInt((MURMUR_GAP_MAX - MURMUR_GAP_MIN).toInt())
            }
        } else {
            // Re-arm the first-delay window on re-entry; don't carry over
            // a gap picked while in-dimension last visit.
            murmurScheduled = false
            currentMurmur = null
        }

        // ── Whispers: madness-keyed, any dimension ──────────────────────
        val amp = player.getEffect(EKEffects.SSELITH_MADNESS.get())?.amplifier ?: -1
        val madnessLevel = amp + 1
        if (madnessLevel <= 0) {
            currentWhisper?.let { if (!mc.soundManager.isActive(it)) currentWhisper = null }
            whisperScheduledForLevel = -1
            return
        }
        if (whisperScheduledForLevel != madnessLevel) {
            nextWhisperTick = now + WHISPER_FIRST_MIN + rng.nextInt((WHISPER_FIRST_MAX - WHISPER_FIRST_MIN).toInt())
            whisperScheduledForLevel = madnessLevel
            return
        }
        if (now < nextWhisperTick) return
        // At higher madness levels (gap < ~10 s) we deliberately do NOT
        // wait for the previous whisper to finish — that overlap is the
        // "near-constant" effect. At lower levels the gap is so long that
        // overlap can't happen anyway.
        val pitch = 1.0f + (rng.nextFloat() - 0.5f) * 2.0f * PITCH_VARIANCE
        val whisper = SimpleSoundInstance(
            EKSounds.SSELITH_WHISPERS.get().location, SoundSource.AMBIENT, WHISPER_VOLUME, pitch, rng,
            false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
        )
        mc.soundManager.play(whisper)
        currentWhisper = whisper

        // Clamp the madness-level lookup — a stray effect amplifier
        // above [SselithMadness.MAX_LEVEL] (foreign mod, command, save
        // from an older [WHISPER_GAP_BANDS] length, etc.) would
        // otherwise IOOBE us off the table.
        val bandIdx = (madnessLevel - 1).coerceIn(0, WHISPER_GAP_BANDS.size - 1)
        val (minGap, maxGap) = WHISPER_GAP_BANDS[bandIdx]
        nextWhisperTick = now + minGap + rng.nextInt((maxGap - minGap).toInt())
    }
}
