package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block

class EnderLinkageBlockItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip.add(
            Component.translatable("item.enderkinesis.ender_linkage.tooltip")
                .withStyle(ChatFormatting.GRAY)
        )
    }
}
