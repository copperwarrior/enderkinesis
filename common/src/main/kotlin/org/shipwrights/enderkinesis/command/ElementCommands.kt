package org.shipwrights.enderkinesis.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.architectury.event.events.common.CommandRegistrationEvent
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.shipwrights.enderkinesis.item.MundaneIncubation
import org.shipwrights.enderkinesis.spell.ElementalEnvironment
import java.util.Locale

/**
 * `/enderkinesis elements` shows the server element mix around the player who runs it, with its sources.
 * `/enderkinesis incubation skip <seconds>` moves that player's incubation on as if the time passed where they stand.
 */
object ElementCommands {

    private const val MAX_SKIP_SECONDS = MundaneIncubation.INCUBATION_TICKS / 20

    fun init() {
        CommandRegistrationEvent.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("enderkinesis")
                    .requires { it.hasPermission(2) }
                    .then(Commands.literal("elements").executes(::show))
                    .then(
                        Commands.literal("incubation").then(
                            Commands.literal("skip").then(
                                Commands.argument("seconds", IntegerArgumentType.integer(1, MAX_SKIP_SECONDS))
                                    .executes(::skip),
                            ),
                        ),
                    ),
            )
        }
    }

    private fun show(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val (values, why) = ElementalEnvironment.explain(player.serverLevel(), player)
        val threshold = ElementalEnvironment.CHARGE_THRESHOLD
        ctx.source.sendSuccess({
            Component.literal("Elements around ${player.name.string} (scrolls recharge above $threshold):")
        }, false)
        for ((element, value) in values.entries.sortedByDescending { it.value }) {
            val reasons = why[element].orEmpty().joinToString("; ")
            ctx.source.sendSuccess({
                element.displayName().append(Component.literal(" %.2f: %s".format(Locale.ROOT, value, reasons)))
            }, false)
        }
        return values.size
    }

    private fun skip(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val counted = MundaneIncubation.skip(player, IntegerArgumentType.getInteger(ctx, "seconds"))
        if (counted == 0) {
            ctx.source.sendFailure(Component.literal("No Mundane stone incubates in ${player.name.string} here."))
        } else {
            ctx.source.sendSuccess({ Component.literal("Skipped $counted s of incubation.") }, false)
        }
        return counted
    }
}
