package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.EntityCollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.entity.PASSES_THROUGH_WOGOR_LEAVES

class WogorLeavesBlock(properties: BlockBehaviour.Properties) : LeavesBlock(properties) {
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape {
        val passes = (context as? EntityCollisionContext)?.entity?.type?.`is`(PASSES_THROUGH_WOGOR_LEAVES) == true
        @Suppress("DEPRECATION")
        if (!passes) return super.getCollisionShape(state, level, pos, context)
        // Same rule as LiquidBlock: a fluid walker stands on the water in a waterlogged leaf as on open water.
        val fluid = state.fluidState
        return if (!fluid.isEmpty && context.isAbove(LiquidBlock.STABLE_SHAPE, pos, true) &&
            context.canStandOnFluid(level.getFluidState(pos.above()), fluid)
        ) {
            LiquidBlock.STABLE_SHAPE
        } else {
            Shapes.empty()
        }
    }
}
