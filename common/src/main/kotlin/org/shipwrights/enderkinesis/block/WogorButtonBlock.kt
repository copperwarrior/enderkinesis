package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/** Wogor button — purely cosmetic floor-pad shape, no press / power /
 *  state machine. Wohlon regrowth uses it as the nearest match for any
 *  destroyed `ButtonBlock`. */
class WogorButtonBlock(properties: Properties) : Block(properties) {

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape = SHAPE
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape = Shapes.empty()

    companion object {
        private val SHAPE: VoxelShape = box(5.0, 0.0, 5.0, 11.0, 2.0, 11.0)
    }
}
