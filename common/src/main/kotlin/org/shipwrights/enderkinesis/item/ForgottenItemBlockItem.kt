package org.shipwrights.enderkinesis.item

import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

class ForgottenItemBlockItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    override fun canPlace(context: BlockPlaceContext, state: BlockState): Boolean =
        context.player?.isCreative == true && super.canPlace(context, state)
}
