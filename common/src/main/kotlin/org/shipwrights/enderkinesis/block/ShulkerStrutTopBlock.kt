package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DirectionalBlock
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.ShulkerStrutTopBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Lid half of the [ShulkerStrutBlock]. Lives exclusively inside the 1×1×1 VS2 ship the
 * strut's BE assembles — never hand-placeable (no [BlockItem] registered for it), no
 * outline / interaction shape. Its only contribution is the FACING-aware half-cube
 * collision that complements [ShulkerStrutBlock]'s opposite-FACING half-cube; at
 * extension=0 the ship sits coincident with the base block and the two halves union into
 * a full 1×1×1 cube of collision without overlap.
 *
 * Rendering belongs to the *base* block's BER (it knows the strut's full FACING + ship
 * lookup), so this block's own model is a transparent placeholder.
 */
class ShulkerStrutTopBlock(properties: BlockBehaviour.Properties) :
    DirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.FACING, Direction.UP))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BlockStateProperties.FACING)
    }

    /** Pure renderer hook — see [ShulkerStrutTopBlockEntity]. The block model is a
     *  transparent placeholder; the BER draws the vanilla `ShulkerModel.lid` part at this
     *  block's ship-local position, FACING-rotated. */
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ShulkerStrutTopBlockEntity(pos, state)

    /** Half-cube on the FACING side — mirrors [ShulkerStrutBlock.getShape]'s opposite-FACING
     *  half so the closed pair unions into a complete block. Lives on `getShape` (not just
     *  `getCollisionShape`) because VS2's ship-block collision reads `getShape` for solid
     *  blocks. */
    @Deprecated("Deprecated in Java")
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = topHalfCube(state.getValue(BlockStateProperties.FACING))

    @Deprecated("Deprecated in Java")
    override fun getCollisionShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = topHalfCube(state.getValue(BlockStateProperties.FACING))

    /** No raycast targeting — player should target / break the *base* block, never the
     *  lid directly. Empty interaction shape skips this block during world clip. */
    @Deprecated("Deprecated in Java")
    override fun getInteractionShape(
        state: BlockState, level: BlockGetter, pos: BlockPos,
    ): VoxelShape = Shapes.empty()

    /** Cascade-destroy the base when the lid is removed (player attack on a SHULKER_STRUT_TOP
     *  somehow, explosion, neighbour broke its supporting block, etc.) — `destroyBlock` with
     *  `dropBlock=true` drops the base item naturally. The base's own `onRemove` will then
     *  re-enter `releaseAndDestroyTop`, which calls `setBlock(lidPos, AIR)`, which is a
     *  no-op because the lid is already gone. The mutual-cascade guard is just "is the
     *  partner still our block?" — once one side is already air, the other side's cascade
     *  short-circuits. */
    @Deprecated("Deprecated in Java")
    override fun onRemove(
        state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean,
    ) {
        if (!state.`is`(newState.block)) {
            val basePos = (level.getBlockEntity(pos) as? ShulkerStrutTopBlockEntity)?.basePos
            if (basePos != null &&
                level.getBlockState(basePos).`is`(EKBlocks.SHULKER_STRUT.get())) {
                level.destroyBlock(basePos, true)
            }
            super.onRemove(state, level, pos, newState, isMoving)
        }
    }

    companion object {
        private val TOP_HALF_CUBE: Map<Direction, VoxelShape> = Direction.values().associateWith { d ->
            val n = d.normal
            // Mirror of [ShulkerStrutBlock]'s base shape — same axes, swapped 0/8 vs 8/16 so
            // the FACING half is filled instead of the opposite.
            val xMin = if (n.x > 0) 8.0 else if (n.x < 0) 0.0 else 0.0
            val xMax = if (n.x > 0) 16.0 else if (n.x < 0) 8.0 else 16.0
            val yMin = if (n.y > 0) 8.0 else if (n.y < 0) 0.0 else 0.0
            val yMax = if (n.y > 0) 16.0 else if (n.y < 0) 8.0 else 16.0
            val zMin = if (n.z > 0) 8.0 else if (n.z < 0) 0.0 else 0.0
            val zMax = if (n.z > 0) 16.0 else if (n.z < 0) 8.0 else 16.0
            Block.box(xMin, yMin, zMin, xMax, yMax, zMax)
        }

        private fun topHalfCube(facing: Direction): VoxelShape =
            TOP_HALF_CUBE[facing] ?: Shapes.block()
    }
}
