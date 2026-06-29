package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.shipObjectWorld

/** Network packets for the Staff of Density.
 *
 *  - [BEGIN] (C2S): client raycast hit a ship; ask the server what its current
 *    density multiplier is so the drag accumulator starts there. The server
 *    responds with a [STATE] back to that single player.
 *  - [STATE] (S2C): the ship's current multiplier (validated server-side).
 *  - [COMMIT] (C2S): on right-click release, apply the chosen multiplier. */
object DensityNetwork {

    val BEGIN: ResourceLocation = EnderkinesisMod.id("staff_of_density/begin")
    val STATE: ResourceLocation = EnderkinesisMod.id("staff_of_density/state")
    val COMMIT: ResourceLocation = EnderkinesisMod.id("staff_of_density/commit")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, BEGIN) { buf, ctx ->
            val shipId = buf.readLong()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                val ship = player.level().shipObjectWorld.allShips.getById(shipId)
                    as? LoadedServerShip ?: return@queue
                val current = DensityManager.getCurrentMultiplier(ship)
                val reply = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
                reply.writeLong(shipId)
                reply.writeDouble(current)
                NetworkManager.sendToPlayer(player, STATE, reply)
            }
        }

        NetworkManager.registerReceiver(NetworkManager.Side.C2S, COMMIT) { buf, ctx ->
            val shipId = buf.readLong()
            val mul = buf.readDouble()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { DensityManager.commitMultiplier(player, shipId, mul) }
        }
    }
}
