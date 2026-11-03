package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.IncantationItem
import org.shipwrights.enderkinesis.item.IncantationOfTheAbyssItem
import org.shipwrights.enderkinesis.item.IncantationOfTheRiverItem
import org.shipwrights.enderkinesis.registry.EKSounds

object IncantationSounds {

    /** Client thread only. */
    private val loops = HashMap<Player, CastLoop>()
    private val aims = HashMap<Player, CastLoop>()

    fun loop(player: Player, item: IncantationItem) {
        keep(loops, player, item, item.loopSound.get(), null)
        when (item) {
            is IncantationOfTheRiverItem -> keep(aims, player, item, EKSounds.INCANTATION_OF_THE_RIVER_IMPACT_LOOP.get()) {
                val jet = IncantationOfTheRiverItem.trace(it.level(), it, 1f)
                jet.end.takeIf { jet.entity != null || jet.block != null }
            }
            is IncantationOfTheAbyssItem -> keep(aims, player, item, EKSounds.INCANTATION_OF_THE_ABYSS_SPHERE_LOOP.get()) {
                IncantationOfTheAbyssItem.focus(it.level(), it, 1f)
            }
            else -> {}
        }
    }

    private fun keep(
        map: HashMap<Player, CastLoop>,
        player: Player,
        item: IncantationItem,
        sound: SoundEvent,
        at: ((Player) -> Vec3?)?,
    ) {
        val sounds = Minecraft.getInstance().soundManager
        val running = map[player]
        // A level change stops all sounds and does not call stop() on the instance.
        if (running != null && running.item === item && !running.isStopped && sounds.isActive(running)) return
        running?.let(sounds::stop)
        map[player] = CastLoop(map, player, item, sound, at).also(sounds::play)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        loops.values.forEach(sounds::stop)
        aims.values.forEach(sounds::stop)
        loops.clear()
        aims.clear()
    }

    /** With [at], the loop sits at that point and goes silent while [at] gives null; without it, it follows the caster. */
    private class CastLoop(
        private val map: HashMap<Player, CastLoop>,
        private val player: Player,
        val item: IncantationItem,
        sound: SoundEvent,
        private val at: ((Player) -> Vec3?)?,
    ) : AbstractTickableSoundInstance(sound, SoundSource.PLAYERS, SoundInstance.createUnseededRandom()) {

        init {
            looping = true
            delay = 0
            follow()
        }

        override fun tick() {
            val hand = player.usedItemHand
            if (!player.isRemoved &&
                IncantationOfWindsClient.isCastingIncantation(player, hand) &&
                player.getItemInHand(hand).item === item
            ) {
                follow()
                return
            }
            stop()
            // Drop the entry now, so a player who left is not held until the next cast.
            map.remove(player, this)
        }

        private fun follow() {
            val point = if (at == null) player.position() else at.invoke(player)
            volume = if (point == null) 0f else 1f
            if (point == null) return
            x = point.x
            y = point.y
            z = point.z
        }
    }
}
