package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Ancrite Beam — a pillar-style block sized like a wall post (8×16×8 px), rotated along the
 * [BlockStateProperties.AXIS] property like vanilla logs. Used by the crepusculite geode
 * generator: the raw-ancrite spike at the crystal's centre extends outward, transitions to a
 * beam past the crystal's width, and continues until it hits the geode's end-stone shell.
 */
class AncriteBeamBlock(properties: BlockBehaviour.Properties) : RotatedPillarBlock(properties) {

    @Deprecated("Deprecated in Java")
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = when (state.getValue(BlockStateProperties.AXIS)) {
        Direction.Axis.X -> SHAPE_X
        Direction.Axis.Z -> SHAPE_Z
        else            -> SHAPE_Y
    }

    companion object {
        /** Matches vanilla `template_wall_post` (4..12 in X/Z, 0..16 in Y) — the same volume the
         *  ancrite_wall block uses for its post — rotated per axis. */
        private val SHAPE_Y: VoxelShape = Block.box(4.0, 0.0, 4.0, 12.0, 16.0, 12.0)
        private val SHAPE_X: VoxelShape = Block.box(0.0, 4.0, 4.0, 16.0, 12.0, 12.0)
        private val SHAPE_Z: VoxelShape = Block.box(4.0, 4.0, 0.0, 12.0, 12.0, 16.0)
    }
}
