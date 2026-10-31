package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds

object EideticBookmarkSounds {

    private const val VOLUME: Float = 1f
    private const val FADE_OUT_TICKS: Int = 3
    /** The use time of a full charge in EideticBookmarkItem. */
    private const val CHARGE_TICKS: Int = 40

    /** Client thread only. A new draw gets a new number, so a fading sound of the old draw cannot come back. */
    private var draw: Int = 0
    private var drawing: Boolean = false
    private var readyStarted: Boolean = false

    fun init() {
        ClientTickEvent.CLIENT_POST.register { tick() }
    }

    private fun tick() {
        val usedTicks = usedTicks()
        if (usedTicks < 0) {
            drawing = false
            return
        }
        val sounds = Minecraft.getInstance().soundManager
        if (!drawing) {
            drawing = true
            readyStarted = false
            draw++
            sounds.play(HoldSound(EKSounds.EIDETIC_BOOKMARK_DRAW.get(), looping = false, draw))
        }
        if (!readyStarted && usedTicks >= CHARGE_TICKS) {
            readyStarted = true
            sounds.play(HoldSound(EKSounds.EIDETIC_BOOKMARK_READY_LOOP.get(), looping = true, draw))
        }
    }

    /** The use time of the bookmark that the local player draws, or -1 when it draws none. */
    private fun usedTicks(): Int {
        val player = Minecraft.getInstance().player ?: return -1
        if (!player.isUsingItem || !player.useItem.`is`(EKItems.EIDETIC_BOOKMARK.get())) return -1
        return player.ticksUsingItem
    }

    private class HoldSound(event: SoundEvent, looping: Boolean, private val owner: Int) :
        AbstractTickableSoundInstance(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom()) {

        init {
            this.looping = looping
            delay = 0
            relative = true
            attenuation = SoundInstance.Attenuation.NONE
            volume = VOLUME
        }

        override fun tick() {
            if (drawing && owner == draw) return
            volume -= VOLUME / FADE_OUT_TICKS
            if (volume <= 0f) stop()
        }
    }
}
