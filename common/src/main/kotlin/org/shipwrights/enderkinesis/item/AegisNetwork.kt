package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod

object AegisNetwork {

    val S2C_RIPPLE: ResourceLocation = EnderkinesisMod.id("staff_of_aegis/ripple")

    private const val RIPPLE_RANGE_SQR: Double = 64.0 * 64.0

    fun broadcastRipple(level: ServerLevel, wielder: Player, hit: Vec3, halfSize: Float) {
        for (player in level.players()) {
            if (player.distanceToSqr(hit) > RIPPLE_RANGE_SQR) continue
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeVarInt(wielder.id)
            buf.writeDouble(hit.x)
            buf.writeDouble(hit.y)
            buf.writeDouble(hit.z)
            buf.writeFloat(halfSize)
            NetworkManager.sendToPlayer(player, S2C_RIPPLE, buf)
        }
    }
}
