package org.shipwrights.enderkinesis.command

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
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
import org.shipwrights.enderkinesis.block.OarscribePenPose

/** `/enderkinesis oarscribe pen ...` tunes the held pen pose on the client of the player who runs it. */
object OarscribeCommands {

    val PEN_POSE_PACKET: ResourceLocation = EnderkinesisMod.id("oarscribe/pen_pose")

    // Server thread only. A debug value, so it is not saved.
    private var pose = OarscribePenPose.DEFAULT

    fun init() {
        CommandRegistrationEvent.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("enderkinesis")
                    .requires { it.hasPermission(2) }
                    .then(Commands.literal("oarscribe").then(penNode())),
            )
        }
    }

    private fun penNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("pen")
            .executes { send(it, pose) }
            .then(Commands.literal("reset").executes { send(it, OarscribePenPose.DEFAULT) })
            .then(
                Commands.literal("centre").then(xyz { ctx, x, y, z ->
                    send(ctx, pose.copy(centreX = x, centreY = y, centreZ = z))
                }),
            )
            .then(
                Commands.literal("rotation").then(xyz { ctx, x, y, z ->
                    send(ctx, pose.copy(rotX = x, rotY = y, rotZ = z))
                }),
            )
            .then(
                Commands.literal("spin").then(
                    Commands.argument("degrees", FloatArgumentType.floatArg())
                        .executes { send(it, pose.copy(spin = FloatArgumentType.getFloat(it, "degrees"))) },
                ),
            )
            .then(
                Commands.literal("length").then(
                    Commands.argument("pixels", FloatArgumentType.floatArg(1f))
                        .executes { send(it, pose.copy(length = FloatArgumentType.getFloat(it, "pixels"))) },
                ),
            )

    private fun xyz(
        apply: (CommandContext<CommandSourceStack>, Float, Float, Float) -> Int,
    ): ArgumentBuilder<CommandSourceStack, *> =
        Commands.argument("x", FloatArgumentType.floatArg()).then(
            Commands.argument("y", FloatArgumentType.floatArg()).then(
                Commands.argument("z", FloatArgumentType.floatArg()).executes {
                    apply(
                        it,
                        FloatArgumentType.getFloat(it, "x"),
                        FloatArgumentType.getFloat(it, "y"),
                        FloatArgumentType.getFloat(it, "z"),
                    )
                },
            ),
        )

    private fun send(ctx: CommandContext<CommandSourceStack>, next: OarscribePenPose): Int {
        val player = ctx.source.playerOrException
        pose = next
        val buf = FriendlyByteBuf(Unpooled.buffer())
        next.write(buf)
        NetworkManager.sendToPlayer(player, PEN_POSE_PACKET, buf)
        ctx.source.sendSuccess({ Component.literal("Oarscribe pen: ${next.describe()}") }, false)
        return 1
    }
}
