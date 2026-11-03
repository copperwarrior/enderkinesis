package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/** Holds no data. It exists only so a renderer can draw the Willfaer flame of a lantern or torch. */
class WillfaerFlameBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.WILLFAER_FLAME.get(), pos, state)
