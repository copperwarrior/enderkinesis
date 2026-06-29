package org.shipwrights.enderkinesis.body

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * S2C sync for the Orb of Potential body identity table.
 *
 *  Only anchor positions go on the wire — the per-orb fractal
 *  pattern is derived deterministically from the anchor on the
 *  client via [org.shipwrights.enderkinesis.block.FractalProjectorBlock.computeFractalType].
 *
 *  Server senders + packet IDs live here. The client-side receivers
 *  live in `OrbBodyNetworkClient` so this file stays free of any
 *  `net.minecraft.client.*` references.
 */
object OrbBodyNetwork {

    val ORB_ADDED: ResourceLocation     = EnderkinesisMod.id("orb_body/added")
    val ORB_REMOVED: ResourceLocation   = EnderkinesisMod.id("orb_body/removed")
    val ORB_FULL_LIST: ResourceLocation = EnderkinesisMod.id("orb_body/full_list")

    fun broadcastAdded(level: ServerLevel, bodyId: Long, anchor: Vector3dc) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(bodyId)
        buf.writeDouble(anchor.x())
        buf.writeDouble(anchor.y())
        buf.writeDouble(anchor.z())
        for (player in level.players()) NetworkManager.sendToPlayer(player, ORB_ADDED, FriendlyByteBuf(buf.copy()))
    }

    fun broadcastRemoved(level: ServerLevel, bodyId: Long) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(bodyId)
        for (player in level.players()) NetworkManager.sendToPlayer(player, ORB_REMOVED, FriendlyByteBuf(buf.copy()))
    }

    fun sendFullList(player: ServerPlayer, level: ServerLevel) {
        val snapshot = OrbWorldData.get(level).snapshot()
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(snapshot.size)
        for ((id, anchor) in snapshot) {
            buf.writeLong(id)
            buf.writeDouble(anchor.x)
            buf.writeDouble(anchor.y)
            buf.writeDouble(anchor.z)
        }
        NetworkManager.sendToPlayer(player, ORB_FULL_LIST, buf)
    }
}

/** Client-side mirror of the server's per-dim orb registry. Populated
 *  exclusively by [OrbBodyNetwork] / [OrbBodyNetworkClient] packets.
 *  The fractal pattern is derived from the anchor at registration
 *  time — once set it never changes. */
object ClientOrbRegistry {

    data class Entry(val anchor: org.joml.Vector3d, val fractalType: Int)

    private val backing: MutableMap<Long, Entry> = LinkedHashMap()

    @Synchronized fun add(bodyId: Long, anchor: Vector3dc, fractalType: Int) {
        backing[bodyId] = Entry(org.joml.Vector3d(anchor), fractalType)
    }

    @Synchronized fun remove(bodyId: Long) {
        backing.remove(bodyId)
    }

    @Synchronized fun replaceForDim(entries: List<Pair<Long, Entry>>) {
        backing.clear()
        for ((id, entry) in entries) backing[id] = entry
    }

    @Synchronized fun snapshot(): List<Long> = backing.keys.toList()

    @Synchronized fun isEmpty(): Boolean = backing.isEmpty()

    @Synchronized fun anchorFor(bodyId: Long): Vector3dc? = backing[bodyId]?.anchor

    @Synchronized fun fractalTypeFor(bodyId: Long): Int? = backing[bodyId]?.fractalType
}
