package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.blockentity.WillfaerFlameBlockEntity

class WillfaerLanternBlock(properties: Properties) : LanternBlock(properties), EntityBlock {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WillfaerFlameBlockEntity(pos, state)

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) =
        level.playWillfaerFlame(pos, random)
}
