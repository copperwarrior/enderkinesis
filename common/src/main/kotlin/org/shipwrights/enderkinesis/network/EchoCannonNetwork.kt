package org.shipwrights.enderkinesis.network

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * S2C broadcast for an Echo Cannon fire event. The server traces and
 * executes the shot; this packet tells nearby clients to render the
 * screech-particle beam + fading wireframe box around it.
 *
 *  Wire format:
 *   - cannon block pos (3 ints)
 *   - host ship id (long, 0 = world-placed)
 *   - segment count (varInt)
 *   - per segment: 6 doubles (start xyz, end xyz)
 *
 *  When `shipId != 0` the segment endpoints are in **ship-LOCAL** coords.
 *  The client transforms them to world space each render frame using
 *  the ship's live `renderTransform.shipToWorld`, so the entire beam
 *  visual stays glued to the ship's current pose — translates as the
 *  ship moves, rotates as it turns.
 *
 *  When `shipId == 0` the endpoints are world coords as before.
 */
object EchoCannonNetwork {

    val FIRE: ResourceLocation = EnderkinesisMod.id("echo_cannon/fire")

    /** Per-segment endpoints as the wire carries them — coordinate
     *  space depends on the message's `shipId`. */
    data class Segment(val start: Vec3, val end: Vec3)

    /** Broadcast the fire event to every player in the same level. */
    fun broadcastFire(
        level: ServerLevel, cannon: BlockPos, shipId: Long, segments: List<Segment>,
    ) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(cannon)
        buf.writeLong(shipId)
        buf.writeVarInt(segments.size)
        for (seg in segments) {
            buf.writeDouble(seg.start.x); buf.writeDouble(seg.start.y); buf.writeDouble(seg.start.z)
            buf.writeDouble(seg.end.x);   buf.writeDouble(seg.end.y);   buf.writeDouble(seg.end.z)
        }
        for (player in level.players()) sendCopy(player, buf)
    }

    data class FireMessage(
        val cannon: BlockPos,
        /** Host ship id, or `0` for world-placed cannons. */
        val shipId: Long,
        /** Endpoints — ship-local if [shipId] != 0, world otherwise. */
        val segments: List<Segment>,
    )

    fun decode(buf: FriendlyByteBuf): FireMessage {
        val pos = buf.readBlockPos()
        val shipId = buf.readLong()
        val n = buf.readVarInt()
        val out = ArrayList<Segment>(n)
        repeat(n) {
            val a = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val b = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
            out += Segment(a, b)
        }
        return FireMessage(pos, shipId, out)
    }

    private fun sendCopy(player: ServerPlayer, buf: FriendlyByteBuf) {
        NetworkManager.sendToPlayer(player, FIRE, FriendlyByteBuf(buf.copy()))
    }
}
