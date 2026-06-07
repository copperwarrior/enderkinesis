package org.shipwrights.enderkinesis.item

import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.block.CrepusculiteLatticeBlock
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity

/**
 * The **Guide to Stratus**. A tide-table for the crepusculite lattice's virtual ocean: aim it at a
 * lattice and right-click to raise that lattice's sea level a step, left-click to lower it.
 *
 * Right-click on a lattice goes through [useOn] below (an item-level override is more idiomatic
 * than an [InteractionEvent.RIGHT_CLICK_BLOCK] handler, and importantly runs *after* vanilla's
 * `state.use` — so the empty-lectern → `state.use → PASS → item.useOn` lectern-placement path
 * inherited from [TomeItem] stays intact when the clicked block is anything other than a lattice).
 * Left-click stays on [GuideToStratus]'s event handler since `Item` has no comparable hook that
 * returns a per-click `InteractionResult` (only the all-or-nothing `canAttackBlock`).
 */
class GuideToStratusItem(properties: Properties) : TomeItem(properties) {

    /** Blocks of sea-level change per right-click. Left-click negates this. */
    private val step: Double = STEP

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        if (level.getBlockState(pos).block is CrepusculiteLatticeBlock) {
            if (!level.isClientSide) {
                val be = level.getBlockEntity(pos) as? CrepusculiteLatticeBlockEntity
                    ?: return InteractionResult.PASS
                val newLevel = be.nudgeSeaLevel(step)
                if (newLevel != null) {
                    context.player?.displayClientMessage(
                        Component.translatable(
                            "message.enderkinesis.stratus.raised", String.format("%.1f", newLevel),
                        ), true,
                    )
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        // Non-lattice (lecterns, anything else): delegate to TomeItem's lectern-placement flow.
        return super.useOn(context)
    }

    /** Placeholder lore until proper Guide to Stratus pages are written: a single blank page on
     *  the lectern. The book's actual function lives in [useOn] (right-click) and [GuideToStratus]
     *  (left-click). */
    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.blank("Guide to Stratus", "The Tidekeeper")

    override fun appendHoverText(
        stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        tooltip.add(Component.translatable("item.enderkinesis.guide_to_stratus.tooltip.raise"))
        tooltip.add(Component.translatable("item.enderkinesis.guide_to_stratus.tooltip.lower"))
    }

    companion object {
        /** Blocks of sea-level change per click. */
        const val STEP: Double = 1.0
    }
}
