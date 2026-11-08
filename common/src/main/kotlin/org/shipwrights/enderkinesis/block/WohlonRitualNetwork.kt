package org.shipwrights.enderkinesis.block

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Ritual ignite visuals: the candle position at ignite, then the root tips when the tree job is ready. */
object WohlonRitualNetwork {

    val S2C_IGNITE: ResourceLocation = EnderkinesisMod.id("wohlon_ritual/ignite")
    val S2C_ROOTS: ResourceLocation = EnderkinesisMod.id("wohlon_ritual/roots")
    val S2C_GHOST: ResourceLocation = EnderkinesisMod.id("wohlon_ritual/ghost")
    val S2C_ROOT_GHOST: ResourceLocation = EnderkinesisMod.id("wohlon_ritual/root_ghost")
    val S2C_ROOT_FRONT: ResourceLocation = EnderkinesisMod.id("wohlon_ritual/root_front")
    val S2C_CALL: ResourceLocation = EnderkinesisMod.id("wohlon_ritual/call")

    /** Holds the root tips, up to about 45 blocks out, with room to see them arrive. */
    const val SEND_RANGE: Double = 96.0
    private const val MAX_TIPS = 16

    fun sendIgnite(level: ServerLevel, candlePos: BlockPos, sacrifice: Vec3, candles: List<BlockPos>) {
        for (player in level.players()) {
            if (!inRange(player.x, player.y, player.z, candlePos)) continue
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeLong(candlePos.asLong())
            buf.writeDouble(sacrifice.x)
            buf.writeDouble(sacrifice.y)
            buf.writeDouble(sacrifice.z)
            buf.writeVarInt(candles.size)
            candles.forEach { buf.writeLong(it.asLong()) }
            NetworkManager.sendToPlayer(player, S2C_IGNITE, buf)
        }
    }

    /** To every player on the server, like vanilla global level events (wither spawn, end portal). */
    fun sendCall(level: ServerLevel, candlePos: BlockPos) {
        for (player in level.server.playerList.players) {
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeLong(candlePos.asLong())
            NetworkManager.sendToPlayer(player, S2C_CALL, buf)
        }
    }

    fun sendRoots(level: ServerLevel, portalPos: BlockPos, tips: LongArray) {
        if (tips.isEmpty()) return
        val count = minOf(tips.size, MAX_TIPS)
        for (player in level.players()) {
            if (!inRange(player.x, player.y, player.z, portalPos)) continue
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeLong(portalPos.asLong())
            buf.writeVarInt(count)
            for (i in 0 until count) buf.writeLong(tips[i])
            NetworkManager.sendToPlayer(player, S2C_ROOTS, buf)
        }
    }

    /** The tree skeleton for the client growth ghost, relative to the candle. */
    fun sendGhost(level: ServerLevel, portalPos: BlockPos, candlePos: BlockPos, segments: FloatArray, tips: IntArray) {
        if (segments.isEmpty()) return
        // Encoded one time. Each segment's last float is a 0/1 root flag, so it goes as a byte.
        val stride = WohlonnogondoniaTreeGrower.GHOST_SEGMENT_FLOATS
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(portalPos.asLong())
        buf.writeLong(candlePos.asLong())
        buf.writeVarInt(segments.size / stride)
        for (i in segments.indices) {
            if (i % stride == stride - 1) buf.writeBoolean(segments[i] != 0f) else buf.writeFloat(segments[i])
        }
        buf.writeVarInt(tips.size)
        for (v in tips) buf.writeShort(v)
        for (player in level.players()) {
            if (!inRange(player.x, player.y, player.z, portalPos)) continue
            NetworkManager.sendToPlayer(player, S2C_GHOST, FriendlyByteBuf(buf.copy()))
        }
    }

    /** A reaching root's skeleton, in [WohlonnogondoniaTreeGrower.GHOST_SEGMENT_FLOATS] floats a segment from [anchor]. */
    fun sendRootGhost(player: ServerPlayer, key: Long, anchor: BlockPos, segments: FloatArray) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(key)
        buf.writeLong(anchor.asLong())
        buf.writeVarInt(segments.size)
        for (f in segments) buf.writeFloat(f)
        NetworkManager.sendToPlayer(player, S2C_ROOT_GHOST, buf)
    }

    /** Blocks from the anchor that the root has grown, or [ROOT_DONE]. */
    fun sendRootFront(player: ServerPlayer, key: Long, front: Float) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(key)
        buf.writeFloat(front)
        NetworkManager.sendToPlayer(player, S2C_ROOT_FRONT, buf)
    }

    const val ROOT_DONE = -1f

    private fun inRange(x: Double, y: Double, z: Double, pos: BlockPos): Boolean =
        pos.distToCenterSqr(x, y, z) <= SEND_RANGE * SEND_RANGE
}
