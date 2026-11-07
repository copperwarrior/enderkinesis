package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.item.MundaneStoneItem
import org.shipwrights.enderkinesis.registry.EKSounds

object MundaneStoneSounds {
    fun init() {
        ClientTickEvent.CLIENT_POST.register { chokeSounds() }
    }

    private fun chokeSounds() {
        val level = Minecraft.getInstance().level ?: return
        for (player in level.players()) {
            if (!player.isUsingItem) continue
            val item = player.useItem.item as? MundaneStoneItem ?: continue
            IncantationSounds.hold(player, item, EKSounds.MUNDANE_STONE_CHOKE.get(), SoundSource.PLAYERS, 1f)
        }
    }
}
