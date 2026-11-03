package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.TorchBlock
import net.minecraft.world.level.block.WallTorchBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.blockentity.WillfaerFlameBlockEntity

// The flame is a volume drawn by the block entity, so neither torch spawns the vanilla flame or smoke.

class WillfaerTorchBlock(properties: Properties) : TorchBlock(properties, ParticleTypes.FLAME), EntityBlock {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WillfaerFlameBlockEntity(pos, state)

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) = Unit
}

class WillfaerWallTorchBlock(properties: Properties) : WallTorchBlock(properties, ParticleTypes.FLAME), EntityBlock {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WillfaerFlameBlockEntity(pos, state)

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) = Unit
}
