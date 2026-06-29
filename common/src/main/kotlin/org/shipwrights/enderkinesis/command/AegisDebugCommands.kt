package org.shipwrights.enderkinesis.command

import com.mojang.brigadier.Command
import dev.architectury.event.events.common.CommandRegistrationEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.commands.Commands
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.AegisBox
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Debug commands for visualising the Staff-of-Aegis shield-box geometry
 *  without holding the staff. Useful for verifying box position, axes, and
 *  size against the world geometry around the player.
 *
 *  Currently provides:
 *
 *  - `/aegisbox` — snapshot the player's current [AegisBox.Frame] and spawn
 *    a persistent debug box at that pose on the player's client.
 *  - `/aegisbox clear` — wipe all persistent debug boxes on the player's
 *    client.
 *
 *  Both are op-gated (`hasPermission(2)`). The frame snapshot is sent to
 *  the player's own client via [ADD_PACKET]; the client renders it through
 *  the same path the live wielded box uses, so what you see is exactly
 *  what `AegisBox.forPlayer` produces at the snapshot moment.  */
object AegisDebugCommands {

    /** S2C: add a persistent debug box at the supplied frame. */
    val ADD_PACKET = EnderkinesisMod.id("aegis_debug/add")

    /** S2C: wipe all persistent debug boxes on the receiving client. */
    val CLEAR_PACKET = EnderkinesisMod.id("aegis_debug/clear")

    /** Server-side mirror of the per-player debug-box list — needed so the
     *  server's Sundering reflection logic can intersect with debug boxes
     *  too (the client list is purely visual and the server doesn't see it
     *  otherwise). Keyed by owner UUID so `/aegisbox clear` can target one
     *  player's boxes without nuking everyone else's. */
    private val serverBoxes: MutableMap<UUID, MutableList<AegisBox.Frame>> = ConcurrentHashMap()

    /** Snapshot every debug box across every player into one list — used
     *  by [org.shipwrights.enderkinesis.item.SunderingBeamTrace] as the
     *  shield set for ray intersection. Returns a fresh list so callers
     *  can iterate without worrying about concurrent map modification. */
    fun allDebugBoxes(): List<AegisBox.Frame> {
        if (serverBoxes.isEmpty()) return emptyList()
        val out = ArrayList<AegisBox.Frame>()
        for (list in serverBoxes.values) out.addAll(list)
        return out
    }

    fun init() {
        CommandRegistrationEvent.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("aegisbox")
                    .requires { source -> source.hasPermission(2) }
                    .executes { ctx ->
                        val player = ctx.source.playerOrException
                        val frame = AegisBox.forPlayer(player, 1f)
                        serverBoxes.getOrPut(player.uuid) { mutableListOf() }.add(frame)
                        sendAdd(player, frame)
                        ctx.source.sendSuccess(
                            { Component.literal("Spawned Aegis debug box at your current pose.") },
                            false,
                        )
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.literal("clear")
                            .executes { ctx ->
                                val player = ctx.source.playerOrException
                                serverBoxes.remove(player.uuid)
                                sendClear(player)
                                ctx.source.sendSuccess(
                                    { Component.literal("Cleared all Aegis debug boxes.") },
                                    false,
                                )
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
        }
    }

    /** Server-side: send the player's client one persistent box at the
     *  given [frame]. Encoded as 12 doubles (center 3 + 3 axes × 3). */
    fun sendAdd(player: ServerPlayer, frame: AegisBox.Frame) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeDouble(frame.center.x); buf.writeDouble(frame.center.y); buf.writeDouble(frame.center.z)
        buf.writeDouble(frame.axisX.x); buf.writeDouble(frame.axisX.y); buf.writeDouble(frame.axisX.z)
        buf.writeDouble(frame.axisY.x); buf.writeDouble(frame.axisY.y); buf.writeDouble(frame.axisY.z)
        buf.writeDouble(frame.axisZ.x); buf.writeDouble(frame.axisZ.y); buf.writeDouble(frame.axisZ.z)
        NetworkManager.sendToPlayer(player, ADD_PACKET, buf)
    }

    fun sendClear(player: ServerPlayer) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        NetworkManager.sendToPlayer(player, CLEAR_PACKET, buf)
    }
}
