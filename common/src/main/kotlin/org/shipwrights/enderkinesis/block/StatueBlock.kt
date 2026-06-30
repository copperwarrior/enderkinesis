package org.shipwrights.enderkinesis.block

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.StatueBlockEntity

/**
 * Decorative statue block — one instance per [StatueKind]. The visible geometry is
 * drawn entirely by [org.shipwrights.enderkinesis.client.StatueBlockEntityRenderer]
 * using a Blockbench-style entity model; the block itself contributes only the
 * 12×10×12 pedestal collision shape so players can stand on it cleanly. The model's
 * head/decoration extends above the 1-block voxel — that's expected for statues and
 * matches how the source models export.
 */
class StatueBlock(val kind: StatueKind, properties: BlockBehaviour.Properties) :
    HorizontalDirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    @Deprecated("Deprecated in Java")
    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    @Deprecated("Deprecated in Java")
    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        StatueBlockEntity(pos, state)

    @Deprecated("Deprecated in Java")
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = STATUE_SHAPE

    @Deprecated("Deprecated in Java")
    override fun getCollisionShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = STATUE_SHAPE

    @Deprecated("Deprecated in Java")
    override fun getOcclusionShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape =
        Shapes.empty()

    override fun appendHoverText(
        stack: ItemStack, level: BlockGetter?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        tooltip.add(Component.translatable(kind.tooltipKey).withStyle(ChatFormatting.GRAY))
    }

    private companion object {
        /** Full statue hitbox — 12-wide pedestal extending 2 blocks tall so the figure
         *  itself (above the pedestal) is selectable and players can't walk through it.
         *  Voxel shapes outside [0,1] are accepted by MC and only affect collision /
         *  ray-trace selection inside the upper block above (the block itself stays
         *  rooted to the lower position). */
        val STATUE_SHAPE: VoxelShape = Shapes.box(
            2.0 / 16.0, 0.0, 2.0 / 16.0,
            14.0 / 16.0, 2.0, 14.0 / 16.0,
        )
    }
}
