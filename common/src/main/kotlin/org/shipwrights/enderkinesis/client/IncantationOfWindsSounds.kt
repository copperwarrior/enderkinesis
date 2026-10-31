package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.registry.EKSounds

object IncantationOfWindsSounds {

    /** Client thread only. */
    private val loops = HashMap<Player, CastLoop>()

    fun loop(player: Player) {
        val sounds = Minecraft.getInstance().soundManager
        val running = loops[player]
        // A level change stops all sounds and does not call stop() on the instance.
        if (running != null && !running.isStopped && sounds.isActive(running)) return
        loops[player] = CastLoop(player).also(sounds::play)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        loops.values.forEach(sounds::stop)
        loops.clear()
    }

    private class CastLoop(private val player: Player) : AbstractTickableSoundInstance(
        EKSounds.INCANTATION_OF_WINDS_LOOP.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom(),
    ) {

        init {
            looping = true
            delay = 0
            follow()
        }

        override fun tick() {
            if (!player.isRemoved && IncantationOfWindsClient.isCastingWinds(player, player.usedItemHand)) {
                follow()
                return
            }
            stop()
            // Drop the entry now, so a player who left is not held until the next cast.
            loops.remove(player, this)
        }

        private fun follow() {
            x = player.x
            y = player.y
            z = player.z
        }
    }
}
