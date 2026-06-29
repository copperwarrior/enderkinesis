package org.shipwrights.enderkinesis.command

import com.mojang.brigadier.context.CommandContext
import dev.architectury.event.events.common.CommandRegistrationEvent
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.sselith.SselithEclipse

/**
 * Debug commands for the Sselith Eclipse schedule. Op-only (`hasPermission(2)`).
 *
 *  - `/eclipse next` — trigger an eclipse on the sselith level right now, anchored to
 *    the level's current gameTime. The server immediately starts ramp-in and broadcasts
 *    the start tick to every client in the dim so visuals match the damage schedule.
 *    Auto-clears once the active window elapses; the natural cycle resumes after.
 */
object SselithEclipseDebugCommands {

    fun init() {
        CommandRegistrationEvent.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("eclipse")
                    .requires { it.hasPermission(2) }
                    .then(Commands.literal("next").executes(::runNext)),
            )
        }
    }

    private fun runNext(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val sselith = source.server.getLevel(SselithRepertory.LEVEL_KEY)
        if (sselith == null) {
            source.sendFailure(Component.literal("Sselith level is not loaded."))
            return 0
        }
        SselithEclipse.triggerNow(sselith)
        source.sendSuccess({ Component.literal("Sselith Eclipse triggered.") }, true)
        return 1
    }
}
