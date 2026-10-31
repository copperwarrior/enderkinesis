package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKItems

/** Wey'ye fruit growing straight from a wogor trunk, cocoa-style. Vanilla cocoa only accepts jungle logs. */
class WeyyeFruitBlock(properties: Properties) : CocoaBlock(properties) {
    @Deprecated("Deprecated in Java")
    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean {
        val support = level.getBlockState(pos.relative(state.getValue(FACING)))
        return support.`is`(EKBlocks.WOGOR_WOOD.get()) || support.`is`(EKBlocks.WOGOR_LOG.get())
    }

    override fun getCloneItemStack(level: BlockGetter, pos: BlockPos, state: BlockState): ItemStack =
        ItemStack(EKItems.WEYYE_FRUIT.get())
}
