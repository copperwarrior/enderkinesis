package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/** Exists so the BER can draw the box and so the barnacle hull scan can find the quasar. */
class QuasarOfPurityBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.QUASAR_OF_PURITY.get(), pos, state)
