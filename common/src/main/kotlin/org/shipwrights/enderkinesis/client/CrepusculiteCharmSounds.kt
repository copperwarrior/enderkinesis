package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.registry.EKSounds

object CrepusculiteCharmSounds {

    private const val HOVER_MAX_VOLUME: Float = 0.6f
    private const val FADE_IN_TICKS: Int = 10
    private const val FADE_OUT_TICKS: Int = 15

    /** Client thread only. */
    private var hover: HoverLoop? = null

    /** Call one time for each client tick in which the lift is active. */
    fun onLiftTick(player: Player) {
        val sounds = Minecraft.getInstance().soundManager
        val existing = hover
        // A level change stops all sounds and does not call stop() on the instance.
        if (existing != null && existing.player === player && !existing.isStopped && sounds.isActive(existing)) {
            existing.lifting = true
            return
        }
        existing?.let(sounds::stop)
        hover = HoverLoop(player).also(sounds::play)
    }

    private class HoverLoop(val player: Player) : AbstractTickableSoundInstance(
        EKSounds.CREPUSCULITE_CHARM_HOVER.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom(),
    ) {

        var lifting: Boolean = true

        init {
            looping = true
            delay = 0
            volume = 0f
            moveToPlayer()
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            if (player.isRemoved) {
                stop()
                return
            }
            moveToPlayer()
            if (lifting) {
                lifting = false
                volume = (volume + HOVER_MAX_VOLUME / FADE_IN_TICKS).coerceAtMost(HOVER_MAX_VOLUME)
            } else {
                volume -= HOVER_MAX_VOLUME / FADE_OUT_TICKS
                if (volume <= 0f) stop()
            }
        }

        private fun moveToPlayer() {
            x = player.x
            y = player.y
            z = player.z
        }
    }
}
