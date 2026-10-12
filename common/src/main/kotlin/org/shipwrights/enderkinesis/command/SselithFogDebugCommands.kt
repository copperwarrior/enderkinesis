package org.shipwrights.enderkinesis.command

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.architectury.event.events.common.CommandRegistrationEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import java.util.Locale

/** Op-only live design tools for the Sselith fog on every client. Nothing is saved.
 *  - `/sselithfog <RRGGBB>` | `reset` — fog colour.
 *  - `/sselithfogrange <start> <end>` | `reset` — fade distances in blocks. */
object SselithFogDebugCommands {
    val COLOR_PACKET: ResourceLocation = EnderkinesisMod.id("sselith_fog_debug")
    val RANGE_PACKET: ResourceLocation = EnderkinesisMod.id("sselith_fog_range_debug")
    const val NO_OVERRIDE: Int = -1
    private const val MAX_FOG_DISTANCE = 1024f

    fun init() {
        CommandRegistrationEvent.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("sselithfog")
                    .requires { it.hasPermission(2) }
                    .then(Commands.literal("reset").executes { broadcast(it, NO_OVERRIDE) })
                    .then(
                        Commands.argument("color", StringArgumentType.word()).executes { ctx ->
                            val text = StringArgumentType.getString(ctx, "color").removePrefix("#")
                            val rgb = if (text.length == 6) text.toIntOrNull(16) else null
                            if (rgb == null) {
                                ctx.source.sendFailure(Component.literal("Expected a hex colour such as #FFE066."))
                                0
                            } else {
                                broadcast(ctx, rgb)
                            }
                        },
                    ),
            )
            dispatcher.register(
                Commands.literal("sselithfogrange")
                    .requires { it.hasPermission(2) }
                    .then(Commands.literal("reset").executes { broadcastRange(it, Float.NaN, Float.NaN) })
                    .then(
                        Commands.argument("start", FloatArgumentType.floatArg(0f, MAX_FOG_DISTANCE)).then(
                            Commands.argument("end", FloatArgumentType.floatArg(0f, MAX_FOG_DISTANCE)).executes { ctx ->
                                val start = FloatArgumentType.getFloat(ctx, "start")
                                val end = FloatArgumentType.getFloat(ctx, "end")
                                if (end <= start) {
                                    ctx.source.sendFailure(Component.literal("End must be greater than start."))
                                    0
                                } else {
                                    broadcastRange(ctx, start, end)
                                }
                            },
                        ),
                    ),
            )
        }
    }

    private fun broadcastRange(ctx: CommandContext<CommandSourceStack>, start: Float, end: Float): Int {
        for (player in ctx.source.server.playerList.players) {
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeFloat(start)
            buf.writeFloat(end)
            NetworkManager.sendToPlayer(player, RANGE_PACKET, buf)
        }
        val message = if (start.isNaN()) {
            "Sselith fog range reset to the built-in distances."
        } else {
            String.format(Locale.ROOT, "Sselith fog range %.1ff to %.1ff", start, end)
        }
        ctx.source.sendSuccess({ Component.literal(message) }, true)
        return 1
    }

    private fun broadcast(ctx: CommandContext<CommandSourceStack>, rgb: Int): Int {
        for (player in ctx.source.server.playerList.players) {
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeInt(rgb)
            NetworkManager.sendToPlayer(player, COLOR_PACKET, buf)
        }
        val message = if (rgb == NO_OVERRIDE) {
            "Sselith fog reset to the built-in colour."
        } else {
            String.format(
                Locale.ROOT, "Sselith fog #%06X = %.2ff, %.2ff, %.2ff",
                rgb, (rgb shr 16 and 0xFF) / 255f, (rgb shr 8 and 0xFF) / 255f, (rgb and 0xFF) / 255f,
            )
        }
        ctx.source.sendSuccess({ Component.literal(message) }, true)
        return 1
    }
}
