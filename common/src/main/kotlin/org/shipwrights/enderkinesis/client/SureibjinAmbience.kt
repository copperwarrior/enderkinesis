package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import org.shipwrights.enderkinesis.dimension.Sureibjin
import org.shipwrights.enderkinesis.registry.EKSounds

/**
 * Sureibjin dream-coast ambience: a single continuous looping track that
 * plays whenever the player is in-dimension, with the sound *source* pinned
 * to the coast meridian (world X ≈ 0). The player's Z is tracked so the
 * source is always the nearest point of the coast — yielding a consistent
 * east-or-west directional cue regardless of how far the player has wandered
 * into the dunes or out over the ocean.
 *
 * Implementation: [CoastAmbienceSound] is a [AbstractTickableSoundInstance]
 * whose `tick()` updates `x/y/z` each frame. The looping [SoundEvent] is
 * built via [SoundEvent.createFixedRangeEvent] with [WIDE_RANGE_BLOCKS] —
 * that's the only knob 1.20.1's `SoundInstance` actually exposes for
 * spatial range, and it keeps the loop audible at any realistic traversal
 * distance while still letting OpenAL's linear distance model spatialize
 * direction.
 */
object SureibjinAmbience {

    private const val LOOP_VOLUME: Float = 0.55f

    /** Fed to `SoundEvent.createFixedRangeEvent` — the maximum world
     *  distance at which the source is still audible. 1600 keeps the
     *  loop softly audible across realistic dune walks. */
    private const val WIDE_RANGE_BLOCKS: Float = 1600.0f

    private var loop: CoastAmbienceSound? = null

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(
            ClientTickEvent.ClientLevel { level -> tick(level) }
        )
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()

        if (level.dimension() != Sureibjin.LEVEL_KEY) {
            loop?.let { if (mc.soundManager.isActive(it)) mc.soundManager.stop(it) }
            loop = null
            return
        }

        val current = loop
        if (current == null || !mc.soundManager.isActive(current)) {
            val l = CoastAmbienceSound(level.random)
            mc.soundManager.play(l)
            loop = l
        }
    }

    /** Tickable loop whose source position locks to the coast meridian
     *  at the player's current Z. Stops itself when the player leaves
     *  Sureibjin, so the outer tick loop can rebuild it on re-entry. */
    private class CoastAmbienceSound(random: RandomSource) : AbstractTickableSoundInstance(
        // Fixed-range SoundEvent is the only handle 1.20.1's SoundInstance
        // gives us for spatial range — there's no getAttenuationDistance()
        // to override on SoundInstance itself.
        SoundEvent.createFixedRangeEvent(
            EKSounds.SUREIBJIN_AMBIENCE_LOOP.get().location,
            WIDE_RANGE_BLOCKS,
        ),
        SoundSource.AMBIENT,
        random,
    ) {
        init {
            looping = true
            delay = 0
            relative = false
            attenuation = SoundInstance.Attenuation.LINEAR
            volume = LOOP_VOLUME
            pitch = 1.0f
            x = 0.0
            y = 64.0
            z = 0.0
        }

        override fun tick() {
            val mc = Minecraft.getInstance()
            val player = mc.player
            val level = mc.level
            if (player == null || level == null || level.dimension() != Sureibjin.LEVEL_KEY) {
                stop()
                return
            }
            // West of the coast: source pinned to the meridian (X=0) so
            // the ocean reads as coming from the east. East of the coast
            // (the player is in / over the water): source follows the
            // player's X so the ambience surrounds them instead of
            // pulling back toward the beach. `max(0, player.x)` collapses
            // both cases.
            x = Math.max(0.0, player.x)
            // Vertical match avoids a phantom "from below" cue when the
            // player climbs a tower or the western pile.
            y = player.y
            // Z-track the player so the source is always the nearest
            // coast point; direction collapses to pure ±X.
            z = player.z
        }
    }
}
