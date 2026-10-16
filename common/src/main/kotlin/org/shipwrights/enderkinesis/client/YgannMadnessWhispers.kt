package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKSounds
import kotlin.math.abs

object YgannMadnessWhispers {

    private const val BASE_VOLUME: Float = 0.3f
    private const val VOLUME_PER_LEVEL: Float = 0.125f
    private const val FADE_TICKS: Int = 40

    /** Client thread only. */
    private var loop: WhisperLoop? = null

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        loop?.let(Minecraft.getInstance().soundManager::stop)
        loop = null
    }

    private fun tick(level: ClientLevel) {
        if (targetVolume() <= 0f) return
        val sounds = Minecraft.getInstance().soundManager
        val current = loop
        // A level change stops all sounds and does not call stop() on the instance.
        if (current != null && !current.isStopped && sounds.isActive(current)) return
        loop = WhisperLoop().also(sounds::play)
    }

    /** 0 without the effect. */
    private fun targetVolume(): Float {
        val effect = Minecraft.getInstance().player?.getEffect(EKEffects.YGANN_MADNESS.get()) ?: return 0f
        return (BASE_VOLUME + VOLUME_PER_LEVEL * effect.amplifier).coerceAtMost(1f)
    }

    private class WhisperLoop : AbstractTickableSoundInstance(
        EKSounds.YGANN_MADNESS_WHISPERS.get(), SoundSource.AMBIENT, SoundInstance.createUnseededRandom(),
    ) {

        private var target = 0f
        private var step = 0f

        init {
            looping = true
            delay = 0
            volume = 0f
            relative = true
            attenuation = SoundInstance.Attenuation.NONE
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val next = targetVolume()
            // Each change of target fades over the same time, also a change of level.
            if (next != target) {
                target = next
                step = abs(target - volume) / FADE_TICKS
            }
            volume = if (volume < target) {
                (volume + step).coerceAtMost(target)
            } else {
                (volume - step).coerceAtLeast(target)
            }
            if (target <= 0f && volume <= 0f) stop()
        }
    }
}
