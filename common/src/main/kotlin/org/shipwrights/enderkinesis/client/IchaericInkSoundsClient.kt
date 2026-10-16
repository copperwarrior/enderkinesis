package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import org.shipwrights.enderkinesis.registry.EKSounds

object IchaericInkSoundsClient {

    private const val UNDER_PEAK_VOLUME = 0.6f
    private const val UNDER_FADE_TICKS = 20

    /** Client thread only. */
    private var underLoop: FlatFadeLoop? = null

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        underLoop?.let(Minecraft.getInstance().soundManager::stop)
        underLoop = null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun tick(level: ClientLevel) {
        if (underLoop?.isPlaying() == true || underStrength() <= 0f) return
        underLoop = FlatFadeLoop(
            EKSounds.ICHAERIC_INK_UNDER_LOOP.get(), UNDER_PEAK_VOLUME, UNDER_FADE_TICKS, UNDER_FADE_TICKS,
            ::underStrength,
        ).also(Minecraft.getInstance().soundManager::play)
    }

    private fun underStrength(): Float = if (IchaericInkVisuals.cameraInInk()) 1f else 0f
}
