package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod

/** The vanilla menu-button packet sends a byte, too small for a row index. */
object SatchelOfShelvingNetwork {

    val SCROLL: ResourceLocation = EnderkinesisMod.id("satchel_of_shelving/scroll")
    val OPEN: ResourceLocation = EnderkinesisMod.id("satchel_of_shelving/open")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SCROLL) { buf, ctx ->
            val containerId = buf.readVarInt()
            val row = buf.readVarInt()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                val menu = player.containerMenu as? SatchelOfShelvingMenu ?: return@queue
                if (menu.containerId != containerId || !menu.stillValid(player)) return@queue
                menu.scrollTo(row)
                menu.broadcastChanges()
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, OPEN) { _, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { if (!player.isSpectator) SatchelOfShelvingItem.openFirst(player) }
        }
    }

    fun sendOpen() {
        NetworkManager.sendToServer(OPEN, FriendlyByteBuf(Unpooled.buffer()))
    }

    fun sendScroll(containerId: Int, row: Int) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(containerId)
        buf.writeVarInt(row)
        NetworkManager.sendToServer(SCROLL, buf)
    }
}
