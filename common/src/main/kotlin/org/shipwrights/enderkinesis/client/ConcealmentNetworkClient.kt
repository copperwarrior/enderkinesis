package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import org.shipwrights.enderkinesis.item.ConcealmentNetwork

object ConcealmentNetworkClient {
    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, ConcealmentNetwork.S2C_CLOAK_UPDATE) { buf, ctx ->
            val shipId = buf.readLong()
            val cloaking = buf.readBoolean()
            ctx.queue { ClientShipCloakingState.onServerUpdate(shipId, cloaking) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, ConcealmentNetwork.S2C_CLOAK_TRANSFER) { buf, ctx ->
            val fromShipId = buf.readLong()
            val toShipId = buf.readLong()
            ctx.queue { ClientShipCloakingState.onTransfer(fromShipId, toShipId) }
        }
    }
}
