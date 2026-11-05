package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.client.OldPaperClient

class StrikeReadoutItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        val readout = readout(stack) ?: return InteractionResultHolder.pass(stack)
        if (level.isClientSide) {
            val book = TomeLore.bake(TITLE, readout.owner.ifEmpty { UNKNOWN_OWNER }, readout.pages())
            OldPaperClient.openStrikeReadout(book, readout)
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val readout = readout(stack) ?: return
        tooltip.add(readout.title.copy().withStyle(ChatFormatting.GRAY))
    }


    companion object {
        const val TAG = "readout"
        private const val TITLE = "Strike Readout"
        private const val UNKNOWN_OWNER = "Unknown"

        fun readout(stack: ItemStack): StrikeReadout? = stack.getTagElement(TAG)?.let(StrikeReadout::load)
    }
}
