package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.EnderkinesisMod

object FusionNetwork {

    val S2C_BUBBLE: ResourceLocation = EnderkinesisMod.id("staff_of_fusion/bubble")
    val S2C_TETHERS: ResourceLocation = EnderkinesisMod.id("staff_of_fusion/tethers")
    val S2C_WELD: ResourceLocation = EnderkinesisMod.id("staff_of_fusion/weld")

    private const val SYNC_RANGE: Double = 128.0 + FusionManager.RADIUS

    fun broadcastBubble(level: ServerLevel, wielder: Player, surface: Vec3, center: Vec3, pulling: Boolean) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(wielder.id)
        buf.writeDouble(surface.x)
        buf.writeDouble(surface.y)
        buf.writeDouble(surface.z)
        buf.writeDouble(center.x)
        buf.writeDouble(center.y)
        buf.writeDouble(center.z)
        buf.writeBoolean(pulling)
        forNearby(level, center) { player -> NetworkManager.sendToPlayer(player, S2C_BUBBLE, FriendlyByteBuf(buf.copy())) }
    }

    /** Each neighbor pair once: both ship IDs and their nearest blocks, in shipyard frames. */
    fun broadcastTethers(
        level: ServerLevel, wielder: Player, center: Vec3, neighbors: Long2ObjectOpenHashMap<FusionManager.Neighbor>,
    ) {
        val pairs = ArrayList<Pair<Long, FusionManager.Neighbor>>()
        for (entry in neighbors.long2ObjectEntrySet()) {
            val reverse = neighbors.get(entry.value.otherId)
            // A mutual pair is sent from the lower ship ID only.
            if (reverse != null && reverse.otherId == entry.longKey && entry.longKey > entry.value.otherId) continue
            pairs.add(entry.longKey to entry.value)
        }
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(wielder.id)
        buf.writeVarInt(pairs.size)
        for ((shipId, neighbor) in pairs) {
            buf.writeLong(shipId)
            writeVector(buf, neighbor.ownBlock)
            buf.writeLong(neighbor.otherId)
            writeVector(buf, neighbor.otherBlock)
        }
        // Sent again every refresh even when unchanged: the client drops tethers not refreshed in 10 ticks.
        forNearby(level, center) { player -> NetworkManager.sendToPlayer(player, S2C_TETHERS, FriendlyByteBuf(buf.copy())) }
    }

    fun broadcastWeld(level: ServerLevel, point: Vector3dc) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        writeVector(buf, point)
        forNearby(level, Vec3(point.x(), point.y(), point.z())) { player ->
            NetworkManager.sendToPlayer(player, S2C_WELD, FriendlyByteBuf(buf.copy()))
        }
    }

    private inline fun forNearby(level: ServerLevel, center: Vec3, send: (ServerPlayer) -> Unit) {
        for (player in level.players()) {
            if (player.distanceToSqr(center) <= SYNC_RANGE * SYNC_RANGE) send(player)
        }
    }

    private fun writeVector(buf: FriendlyByteBuf, v: Vector3dc) {
        buf.writeDouble(v.x())
        buf.writeDouble(v.y())
        buf.writeDouble(v.z())
    }
}
