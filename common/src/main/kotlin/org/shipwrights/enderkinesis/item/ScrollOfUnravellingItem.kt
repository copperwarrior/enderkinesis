package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level

/**
 * Identifier class for the Scroll of Unravelling — the actual right-click
 * handling lives in [ScrollOfUnravelling], registered against
 * [dev.architectury.event.events.common.InteractionEvent.RIGHT_CLICK_BLOCK].
 * Putting the logic in the event keeps it ahead of vanilla's
 * `LecternBlock.use` (which would otherwise open the book screen and
 * never give an `Item.useOn` override a chance to run).
 *
 * This class only carries the item type, rarity, and tooltip line.
 */
class ScrollOfUnravellingItem(properties: Properties) : Item(properties) {

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip.add(
            Component.translatable("item.enderkinesis.scroll_of_unravelling.tooltip")
                .withStyle(ChatFormatting.GRAY)
        )
    }
}
