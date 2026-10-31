package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.TallGrassBlock
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlocks

// Wohlon fern and grass. Vanilla TallGrassBlock hard-codes its bonemeal result to LARGE_FERN or TALL_GRASS.
class WohlonUndergrowthBlock(properties: Properties) : TallGrassBlock(properties) {
    override fun performBonemeal(level: ServerLevel, random: RandomSource, pos: BlockPos, state: BlockState) {
        val tall = EKBlocks.WOHLON_TALL_FERN.get().defaultBlockState()
        if (tall.canSurvive(level, pos) && level.isEmptyBlock(pos.above())) {
            DoublePlantBlock.placeAt(level, tall, pos, Block.UPDATE_CLIENTS)
        }
    }
}
