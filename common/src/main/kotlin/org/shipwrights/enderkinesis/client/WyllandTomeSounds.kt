package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.registry.EKSounds

object WyllandTomeSounds {

    private const val HOLD_VOLUME: Float = 0.7f

    // The server can refuse a grab one tick after the press. The ramp prevents a blip.
    private const val HOLD_RAMP_TICKS: Int = 5

    /** Client thread only. */
    private var holdLoop: HoldLoop? = null

    fun tick() {
        if (!WyllandTomeClient.isGrabbing()) return
        if (holdLoop?.isStopped == false) return
        val loop = HoldLoop()
        Minecraft.getInstance().soundManager.play(loop)
        holdLoop = loop
    }

    private class HoldLoop : AbstractTickableSoundInstance(
        EKSounds.WYLLAND_HOLD_LOOP.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom(),
    ) {
        private var age = 0

        init {
            looping = true
            delay = 0
            relative = true
            attenuation = SoundInstance.Attenuation.NONE
            volume = rampVolume()
        }

        override fun tick() {
            if (!WyllandTomeClient.isGrabbing()) {
                stop()
                return
            }
            age++
            volume = rampVolume()
        }

        private fun rampVolume(): Float = HOLD_VOLUME * ((age + 1).toFloat() / HOLD_RAMP_TICKS).coerceAtMost(1f)
    }
}
