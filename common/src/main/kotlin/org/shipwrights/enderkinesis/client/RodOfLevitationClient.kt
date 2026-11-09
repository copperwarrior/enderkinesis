package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import org.shipwrights.enderkinesis.item.RodOfLevitationManager
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.util.lookedAtBody

/** Vanilla sends nothing for a left-click in the air, so a click on a VS body reaches the server here. */
object RodOfLevitationClient {
    fun init() {
        InteractionEvent.CLIENT_LEFT_CLICK_AIR.register { player, hand ->
            if (player.getItemInHand(hand).`is`(EKItems.ROD_OF_LEVITATION.get()) && player.lookedAtBody() != null) {
                NetworkManager.sendToServer(RodOfLevitationManager.NUDGE_BODY, FriendlyByteBuf(Unpooled.buffer()))
            }
        }
    }
}
