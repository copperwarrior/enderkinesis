package org.shipwrights.enderkinesis.block

import dev.architectury.core.fluid.ArchitecturyFlowingFluid
import dev.architectury.core.fluid.ArchitecturyFluidAttributes
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.Vec3

/** Flowing Ichaeric Ink. A puddle is one layer that has no source, does not spread and does not dry up. */
class IchaericInkFlowingFluid(attributes: ArchitecturyFluidAttributes) : ArchitecturyFlowingFluid.Flowing(attributes) {

    override fun tick(level: Level, pos: BlockPos, state: FluidState) {
        if (!isPuddle(state)) return super.tick(level, pos, state)
        val below = pos.below()
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        }
    }

    // A falling state next to a wall flows down in vanilla; a puddle must be still.
    override fun getFlow(level: BlockGetter, pos: BlockPos, state: FluidState): Vec3 =
        if (isPuddle(state)) Vec3.ZERO else super.getFlow(level, pos, state)

    companion object {
        // Vanilla never makes a falling state with amount 1, so that state marks a puddle.
        // It is LiquidBlock LEVEL 15, which IchaericInkBlock maps back to this state.
        const val PUDDLE_BLOCK_LEVEL = 15
        private const val PUDDLE_AMOUNT = 1

        fun isPuddle(state: FluidState): Boolean =
            state.type is IchaericInkFlowingFluid && state.amount == PUDDLE_AMOUNT && state.getValue(FALLING)

        fun puddleState(fluid: IchaericInkFlowingFluid): FluidState = fluid.getFlowing(PUDDLE_AMOUNT, true)
    }
}
