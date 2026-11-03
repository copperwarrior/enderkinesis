package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.EntityCollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.entity.PASSES_THROUGH_WOGOR_LEAVES

class WogorLeavesBlock(properties: BlockBehaviour.Properties) : LeavesBlock(properties) {
    @Deprecated("Deprecated in Java")
    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape {
        val passes = (context as? EntityCollisionContext)?.entity?.type?.`is`(PASSES_THROUGH_WOGOR_LEAVES) == true
        @Suppress("DEPRECATION")
        return if (passes) Shapes.empty() else super.getCollisionShape(state, level, pos, context)
    }
}
