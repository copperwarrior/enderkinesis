package org.shipwrights.enderkinesis.item

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.block.CrepusculiteLatticeBlock
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Left-click side of the Guide to Stratus: aim at a crepusculite lattice and left-click to lower
 * its sea level a step (right-click to raise is handled item-side in [GuideToStratusItem.useOn]).
 *
 * Left-click runs through Architectury's [InteractionEvent.LEFT_CLICK_BLOCK] (Fabric
 * `AttackBlockCallback`, Forge `PlayerInteractEvent.LeftClickBlock`), both of which fire on the
 * *server* side of the swing. For non-lattice blocks the handler returns [EventResult.pass] so the
 * book never breaks anything it isn't aimed at. Hitting a lattice returns [EventResult.interruptTrue]
 * (→ `InteractionResult.SUCCESS`), which cancels the vanilla attack on both loaders.
 */
object GuideToStratus {

    /** Blocks of sea-level change per click — negated for the lower direction. */
    private val STEP: Double = GuideToStratusItem.STEP

    fun init() {
        InteractionEvent.LEFT_CLICK_BLOCK.register { player, hand, pos, _ -> nudgeLower(player, hand, pos) }
    }

    private fun nudgeLower(player: Player, hand: InteractionHand, pos: BlockPos): EventResult {
        if (!player.getItemInHand(hand).`is`(EKItems.GUIDE_TO_STRATUS.get())) return EventResult.pass()
        val level = player.level()
        // Only ever act on (and consume the click over) an actual lattice — other blocks behave
        // normally so the book can still be carried/used elsewhere without hijacking clicks.
        if (level.getBlockState(pos).block !is CrepusculiteLatticeBlock) return EventResult.pass()
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? CrepusculiteLatticeBlockEntity ?: return EventResult.pass()
            val newLevel = be.nudgeSeaLevel(-STEP)
            if (newLevel != null) {
                player.displayClientMessage(
                    Component.translatable(
                        "message.enderkinesis.stratus.lowered", String.format("%.1f", newLevel),
                    ), true,
                )
            }
        }
        return EventResult.interruptTrue()
    }
}
