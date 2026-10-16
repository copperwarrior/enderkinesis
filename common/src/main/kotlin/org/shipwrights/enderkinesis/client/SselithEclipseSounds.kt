package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.sselith.SselithEclipse

object SselithEclipseSounds {

    private const val CUE_VOLUME: Float = 0.8f
    private const val LOOP_VOLUME: Float = 0.6f

    /** Client thread only. Below 0 until the first tick in Sselith, so a mid-eclipse arrival plays no cue. */
    private var lastIntensity = -1f
    private var loop: EclipseLoop? = null

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        loop?.let(Minecraft.getInstance().soundManager::stop)
        loop = null
        lastIntensity = -1f
    }

    /** [SselithAmbience] holds back new murmurs while this is true. */
    fun isLoopPlaying(): Boolean {
        val current = loop ?: return false
        // A level change stops all sounds and does not call stop() on the instance.
        return !current.isStopped && Minecraft.getInstance().soundManager.isActive(current)
    }

    private fun tick(level: ClientLevel) {
        if (level.dimension() != SselithRepertory.LEVEL_KEY) {
            lastIntensity = -1f
            return
        }
        val intensity = intensity(level)
        val last = lastIntensity
        lastIntensity = intensity
        if (last >= 0f) {
            if (last <= 0f && intensity > 0f) playFlat(EKSounds.SSELITH_ECLIPSE_BEGIN.get(), CUE_VOLUME)
            // The plateau is exactly 1, so the first value below 1 after it is the start of the ramp-out.
            if (last >= 1f && intensity < 1f) playFlat(EKSounds.SSELITH_ECLIPSE_END.get(), CUE_VOLUME)
        }
        if (intensity > 0f && !isLoopPlaying()) loop = EclipseLoop().also(Minecraft.getInstance().soundManager::play)
    }

    private fun intensity(level: ClientLevel): Float = SselithEclipse.intensity(level.gameTime)

    private class EclipseLoop : AbstractTickableSoundInstance(
        EKSounds.SSELITH_ECLIPSE_LOOP.get(), SoundSource.AMBIENT, SoundInstance.createUnseededRandom(),
    ) {

        init {
            looping = true
            delay = 0
            volume = 0f
            relative = true
            attenuation = SoundInstance.Attenuation.NONE
        }

        // The ramp starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val level = Minecraft.getInstance().level
            val intensity = if (level != null && level.dimension() == SselithRepertory.LEVEL_KEY) {
                intensity(level)
            } else {
                0f
            }
            volume = LOOP_VOLUME * intensity
            if (intensity <= 0f) stop()
        }
    }
}
