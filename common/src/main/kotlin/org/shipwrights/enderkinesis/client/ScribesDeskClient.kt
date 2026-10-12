package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import org.shipwrights.enderkinesis.block.ScribesDeskNetwork

object ScribesDeskClient {
    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, ScribesDeskNetwork.OPEN) { buf, ctx ->
            val pos = buf.readBlockPos()
            ctx.queue { Minecraft.getInstance().setScreen(ScribesDeskScreen(pos)) }
        }
    }
}
