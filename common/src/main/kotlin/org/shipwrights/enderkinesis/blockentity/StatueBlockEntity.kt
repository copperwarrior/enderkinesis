package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/** Marker BE — exists solely so the statue block can register a
 *  [org.shipwrights.enderkinesis.client.StatueBlockEntityRenderer]. The visible kind
 *  is carried by the block (see [org.shipwrights.enderkinesis.block.StatueBlock.kind]);
 *  no per-BE state is needed. */
class StatueBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.STATUE.get(), pos, state)
