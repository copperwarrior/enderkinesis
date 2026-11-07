package org.shipwrights.enderkinesis.ritual

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Tells clients near a projectile that fired a rite to draw winding roots behind it. */
object RootTrail {

    val S2C_TRAIL: ResourceLocation = EnderkinesisMod.id("ritual/root_trail")
    private const val SEND_RANGE = 192.0

    fun send(level: ServerLevel, projectile: Entity) {
        for (player in level.players()) {
            if (player.distanceToSqr(projectile) > SEND_RANGE * SEND_RANGE) continue
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeVarInt(projectile.id)
            NetworkManager.sendToPlayer(player, S2C_TRAIL, buf)
        }
    }
}
