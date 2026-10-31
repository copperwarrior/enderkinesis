package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.InputConstants
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import dev.architectury.registry.client.keymappings.KeyMappingRegistry
import io.netty.buffer.Unpooled
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.InteractionHand
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.spell.SelfCasts

object SpellKeys {

    val SELF_CAST: KeyMapping = KeyMapping(
        "key.enderkinesis.self_cast",
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_V,
        "key.categories.enderkinesis",
    )

    fun init() {
        KeyMappingRegistry.register(SELF_CAST)
        ClientTickEvent.CLIENT_POST.register(ClientTickEvent.Client { mc -> tick(mc) })
    }

    private fun tick(mc: Minecraft) {
        while (SELF_CAST.consumeClick()) {
            val player = mc.player ?: return
            if (mc.screen != null) continue
            val hand = InteractionHand.entries.firstOrNull { MysticScrollItem.spellOf(player.getItemInHand(it)) != null } ?: continue
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeEnum(hand)
            NetworkManager.sendToServer(SelfCasts.SELF_CAST, buf)
        }
    }
}
