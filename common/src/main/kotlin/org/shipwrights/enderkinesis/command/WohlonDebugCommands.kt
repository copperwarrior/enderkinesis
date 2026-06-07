package org.shipwrights.enderkinesis.command

import com.mojang.brigadier.Command
import dev.architectury.event.events.common.CommandRegistrationEvent
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import org.shipwrights.enderkinesis.block.WohlonnogondoniaSpreader

/**
 * Debug commands for poking the Wohlonnogondonia spread system from
 * a creative / flatworld test setup. Registered behind the standard
 * op-permission gate (`hasPermission(2)`) so they're never reachable
 * in a non-op survival context.
 *
 * Currently provides:
 *
 *  - `/wohlon setcell` — flip the single 4×4×4 biome cell containing
 *    the command source's position to Wohlonnogondonia. Goes through
 *    the spreader's normal write path so the chunk is taint-tracked
 *    + section-masked + resynced, **unlike** vanilla `/fillbiome`
 *    which writes the cell directly and leaves the spreader unaware
 *    that the chunk has Wohlon content (causing zero spread + zero
 *    block conversion until something else taints the chunk).
 */
object WohlonDebugCommands {

    fun init() {
        CommandRegistrationEvent.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("wohlon")
                    .requires { source -> source.hasPermission(2) }
                    .then(
                        Commands.literal("setcell")
                            .executes { ctx ->
                                val source = ctx.source
                                val level = source.level
                                val pos = BlockPos.containing(source.position)
                                val changed = WohlonnogondoniaSpreader.convertSingleCellToWohlon(level, pos)
                                if (changed) {
                                    val qx = pos.x shr 2
                                    val qy = pos.y shr 2
                                    val qz = pos.z shr 2
                                    source.sendSuccess(
                                        {
                                            Component.literal(
                                                "Set biome cell ($qx, $qy, $qz) → Wohlonnogondonia at $pos in ${level.dimension().location()}"
                                            )
                                        },
                                        true,
                                    )
                                    Command.SINGLE_SUCCESS
                                } else {
                                    source.sendFailure(
                                        Component.literal(
                                            "No change at $pos — chunk unloaded, biome lookup failed, or cell already Wohlonnogondonia."
                                        )
                                    )
                                    0
                                }
                            }
                    )
            )
        }
    }
}
