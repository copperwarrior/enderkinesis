package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.ClientShipCloakingState

/** Staff of Concealment networking — one S2C packet.
 *
 *  [S2C_CLOAK_UPDATE] tells every tracking client "ship `<shipId>` is now (cloaking |
 *  uncloaking)". The client maintains its own fade timer and renders the visual; the
 *  server only sends transitions, not per-tick progress. */
object ConcealmentNetwork {

    val S2C_CLOAK_UPDATE: ResourceLocation = EnderkinesisMod.id("staff_of_concealment/cloak_update")
    val S2C_CLOAK_TRANSFER: ResourceLocation = EnderkinesisMod.id("staff_of_concealment/cloak_transfer")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, S2C_CLOAK_UPDATE) { buf, ctx ->
            val shipId = buf.readLong()
            val cloaking = buf.readBoolean()
            ctx.queue { ClientShipCloakingState.onServerUpdate(shipId, cloaking) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, S2C_CLOAK_TRANSFER) { buf, ctx ->
            val fromShipId = buf.readLong()
            val toShipId = buf.readLong()
            ctx.queue { ClientShipCloakingState.onTransfer(fromShipId, toShipId) }
        }
    }

    /** The cloaked block moved from [fromShipId] to [toShipId] (VS2 split or weld). */
    fun broadcastCloakTransfer(level: ServerLevel, fromShipId: Long, toShipId: Long) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(fromShipId)
        buf.writeLong(toShipId)
        for (player in level.players()) {
            NetworkManager.sendToPlayer(player, S2C_CLOAK_TRANSFER, FriendlyByteBuf(buf.copy()))
        }
    }

    fun sendCloakUpdate(player: net.minecraft.server.level.ServerPlayer, shipId: Long, cloaking: Boolean) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(shipId)
        buf.writeBoolean(cloaking)
        NetworkManager.sendToPlayer(player, S2C_CLOAK_UPDATE, buf)
    }

    /** Broadcast a cloak-state update to every player currently in [level]. Players in
     *  other dimensions don't get notified — their clients can't see the ship anyway. */
    fun broadcastCloakUpdate(level: ServerLevel, shipId: Long, cloaking: Boolean) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(shipId)
        buf.writeBoolean(cloaking)
        for (player in level.players()) {
            // copy() because NetworkManager retains the buffer past send.
            NetworkManager.sendToPlayer(player, S2C_CLOAK_UPDATE, FriendlyByteBuf(buf.copy()))
        }
    }
}
