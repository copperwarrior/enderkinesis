package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource

/** A non-directional one-shot for the local player only. */
internal fun playFlat(event: SoundEvent, volume: Float, pitch: Float = 1f, source: SoundSource = SoundSource.AMBIENT) {
    Minecraft.getInstance().soundManager.play(
        SimpleSoundInstance(
            event.location, source, volume, pitch, SoundInstance.createUnseededRandom(),
            false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
        ),
    )
}

/** A non-directional loop that moves to [peak] × [target]. A full rise takes [fadeInTicks], a full fall [fadeOutTicks]. */
internal class FlatFadeLoop(
    event: SoundEvent,
    private val peak: Float,
    private val fadeInTicks: Int,
    private val fadeOutTicks: Int,
    private val target: () -> Float,
) : AbstractTickableSoundInstance(event, SoundSource.AMBIENT, SoundInstance.createUnseededRandom()) {

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
        val goal = peak * target().coerceIn(0f, 1f)
        volume = if (volume < goal) {
            (volume + peak / fadeInTicks).coerceAtMost(goal)
        } else {
            (volume - peak / fadeOutTicks).coerceAtLeast(goal)
        }
        if (goal <= 0f && volume <= 0f) stop()
    }

    // A level change stops all sounds and does not call stop() on the instance.
    fun isPlaying(): Boolean = !isStopped && Minecraft.getInstance().soundManager.isActive(this)
}
