package org.shipwrights.enderkinesis.block

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.blockentity.AncriteEyeBlockEntity

/**
 * Left-click handler for the Ancrite Eye. Empty-handed left-click on the block jumps
 * its power level to max — the "button press" mode of Create's analog lever.
 *
 * Runs through Architectury's [InteractionEvent.LEFT_CLICK_BLOCK] (Fabric
 * `AttackBlockCallback`, Forge `PlayerInteractEvent.LeftClickBlock`); both fire
 * server-side on the swing. For non-Ancrite-Eye blocks or any non-empty hand we
 * return [EventResult.pass] so vanilla attack behaviour is preserved; a successful
 * press returns [EventResult.interruptTrue] to cancel the break attempt.
 */
object AncriteEye {

    fun init() {
        InteractionEvent.LEFT_CLICK_BLOCK.register { player, hand, pos, _ -> press(player, hand, pos) }
    }

    private fun press(player: Player, hand: InteractionHand, pos: BlockPos): EventResult {
        // Empty hand only — left-clicking the eye with a tool keeps the vanilla break
        // path so survival players can still mine the block off.
        if (!player.getItemInHand(hand).isEmpty) return EventResult.pass()
        val level = player.level()
        val state = level.getBlockState(pos)
        if (state.block !is AncriteEyeBlock) return EventResult.pass()
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? AncriteEyeBlockEntity ?: return EventResult.pass()
            AncriteEyeBlock.pressButton(level, pos, state, be)
        }
        return EventResult.interruptTrue()
    }
}
