package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.scrying.ScryingSessionManager

/**
 * Server↔client networking for the scrying-orb remote-view effect.
 *
 *  - **S2C [BEGIN_SCRYING]** — sent when the player right-clicks a scrying orb and the
 *    server resolves a valid target orb. Carries TWO block positions in shipyard frame:
 *    the SOURCE orb (the one the player clicked) and the TARGET orb (the one the camera
 *    moves to). Client transforms each through the local VS2 ship transform every frame
 *    so the camera and the proximity check both track moving ships.
 *
 *  - **C2S [END_SCRYING]** — client-initiated, fires when the player sneaks or walks too
 *    far from the source orb. Lets the server forget any per-player view state (currently
 *    none, but the round-trip exists so server-side hooks can be added later).
 */
object ScryingClientNetwork {

    val BEGIN_SCRYING: ResourceLocation = EnderkinesisMod.id("scrying/begin")
    val END_SCRYING: ResourceLocation = EnderkinesisMod.id("scrying/end")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, END_SCRYING) { _, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { ScryingSessionManager.unmount(player) }
        }
    }

    /** Send the begin-scrying packet to one player. Source and target are shipyard-frame
     *  block positions — when the orb sits on a VS2 ship, that pos is in the ship's local
     *  storage region, and the client transforms it to world coords via the ship's render
     *  transform each frame. [viewDistance] is the chunk-radius the server has loaded
     *  around the target; the client uses this exact value to size its camera-chunk
     *  storage so every server-streamed chunk passes the in-range gate. */
    fun sendBeginScrying(player: ServerPlayer, source: BlockPos, target: BlockPos, viewDistance: Int) {
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        buf.writeBlockPos(source)
        buf.writeBlockPos(target)
        buf.writeVarInt(viewDistance)
        NetworkManager.sendToPlayer(player, BEGIN_SCRYING, buf)
    }

    fun sendEndScrying() {
        // Guard against the client no longer being in-game. The end-
        // scrying path runs from a CLIENT_POST tick, which keeps
        // firing through disconnect (level drops to null one tick,
        // we request a fade-out, the fade completes a tick or two
        // later, and only then do we get here). By that point
        // `NetworkManager.sendToServer` throws "not in game". The
        // server-side handler is a no-op for a disconnected player
        // anyway — it unmounts on disconnect via the normal path.
        if (net.minecraft.client.Minecraft.getInstance().connection == null) return
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        NetworkManager.sendToServer(END_SCRYING, buf)
    }
}
