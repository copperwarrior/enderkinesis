package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/** Holds no data: it exists so that the vent block gets a server ticker and a louvre renderer. */
class GasVentBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.GAS_VENT.get(), pos, state) {

    /** Client render state: the eased louvre opening, 0 to 1. */
    var clientOpen: Float = 0f
    var clientLastRenderMs: Long = -1L
}
