package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Tome of Transportation S2C network — describes a single dispatch so chunk-watching clients
 * can render the ghost-item visual locally without any server-side entity. The visual is
 * purely cosmetic; the actual stack move (extraction at SEND, insertion at RECEIVE) happens
 * server-side via [TransportationTomeOrbBehavior] on the delivery tick.
 *
 * The packet carries the **block positions** of both orbs (not their world coordinates), so
 * the client can re-resolve world-space endpoints each tick via
 * [org.shipwrights.enderkinesis.client.OrbClientGeometry]. That keeps the visual tracking
 * both orbs through ship motion — if a ship drifts mid-flight, the ghost's interpolated
 * position updates in step with the live ship transforms instead of clinging to a stale
 * world point captured at dispatch time.
 */
object TransportationNetwork {

    val DISPATCH: ResourceLocation = EnderkinesisMod.id("transportation/dispatch")

    /** Server-side: broadcast a dispatch packet to every player tracking the SEND orb's
     *  chunk. Players outside the chunk's view distance don't see the dispatch start; if
     *  they later enter range of the receiver, they'll just see whatever is in flight
     *  arrive — acceptable for a cosmetic visual. */
    fun broadcastDispatch(
        level: ServerLevel,
        sendPos: BlockPos,
        receiverPos: BlockPos,
        stack: ItemStack,
        totalTicks: Int,
    ) {
        val chunkPos = ChunkPos(sendPos)
        val players = level.chunkSource.chunkMap.getPlayers(chunkPos, false)
        if (players.isEmpty()) return
        for (player in players) {
            // Fresh buf per player — `NetworkManager.sendToPlayer` may consume the
            // underlying ByteBuf, so a single shared buf would fail after the first send.
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeBlockPos(sendPos)
            buf.writeBlockPos(receiverPos)
            buf.writeItem(stack)
            buf.writeVarInt(totalTicks)
            NetworkManager.sendToPlayer(player, DISPATCH, buf)
        }
    }
}
