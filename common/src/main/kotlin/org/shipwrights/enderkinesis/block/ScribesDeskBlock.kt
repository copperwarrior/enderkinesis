package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.ScribesDeskBlockEntity

class ScribesDeskBlock(properties: Properties) : Block(properties), EntityBlock {

    private val shapes: Map<BlockState, VoxelShape> = getShapeForEachState(::shapeFor)

    init {
        registerDefaultState(
            stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(PAPER, false).setValue(PENFEATHER, false).setValue(INK, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, PAPER, PENFEATHER, INK)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    @Deprecated("Deprecated in Java")
    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    @Deprecated("Deprecated in Java")
    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    @Deprecated("Deprecated in Java")
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        shapes.getValue(state)

    @Deprecated("Deprecated in Java")
    override fun isPathfindable(state: BlockState, level: BlockGetter, pos: BlockPos, type: PathComputationType): Boolean =
        false

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ScribesDeskBlockEntity(pos, state)

    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val desk = level.getBlockEntity(pos) as? ScribesDeskBlockEntity ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)
        val slot = desk.slotFor(held)
        if (slot != null && desk.load(slot, held)) return InteractionResult.CONSUME
        if (desk.isReady && player is ServerPlayer) {
            ScribesDeskNetwork.open(player, pos)
        } else {
            player.displayClientMessage(Component.translatable(MISSING_KEY), true)
        }
        return InteractionResult.CONSUME
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? ScribesDeskBlockEntity)?.let { Containers.dropContents(level, pos, it) }
        }
        super.onRemove(state, level, pos, newState, moved)
    }

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING

        // The client draws the desk contents from these; the block entity keeps them current.
        val PAPER: BooleanProperty = BooleanProperty.create("paper")
        val PENFEATHER: BooleanProperty = BooleanProperty.create("penfeather")
        val INK: BooleanProperty = BooleanProperty.create("ink")

        const val MISSING_KEY = "message.enderkinesis.scribes_desk.missing"

        // Model elements in the FACING=NORTH frame. The brace and the rest have a 22.5 degree tilt in the model,
        // thus steps replace them here.
        private val BASE_BOXES = listOf(
            Box(1.0, 0.0, 1.0, 3.0, 12.0, 3.0),
            Box(13.0, 0.0, 1.0, 15.0, 12.0, 3.0),
            Box(1.0, 0.0, 13.0, 3.0, 12.0, 15.0),
            Box(13.0, 0.0, 13.0, 15.0, 12.0, 15.0),
            Box(3.0, 7.0, 13.0, 5.0, 9.0, 15.0),
            Box(5.0, 6.0, 13.0, 7.0, 8.0, 15.0),
            Box(7.0, 5.0, 13.0, 9.0, 7.0, 15.0),
            Box(9.0, 4.5, 13.0, 11.0, 6.5, 15.0),
            Box(11.0, 3.5, 13.0, 13.0, 5.5, 15.0),
            Box(0.0, 11.0, 0.0, 16.0, 14.0, 16.0),
            Box(1.0, 14.0, 10.0, 6.0, 15.0, 15.0),
            Box(6.0, 14.0, 11.0, 15.0, 14.75, 15.0),
            Box(6.0, 14.75, 13.0, 15.0, 15.5, 14.75),
        )
        private val SCROLL_BOX = Box(7.0, 14.0, 9.75, 14.6, 16.5, 12.25)
        private val INKWELL_BOX = Box(2.0, 15.0, 11.0, 5.0, 18.0, 14.0)

        private fun shapeFor(state: BlockState): VoxelShape {
            val facing = state.getValue(FACING)
            val boxes = BASE_BOXES +
                listOfNotNull(SCROLL_BOX.takeIf { state.getValue(PAPER) }, INKWELL_BOX.takeIf { state.getValue(INK) })
            return boxes.fold(Shapes.empty()) { shape, box -> Shapes.or(shape, box.shape(facing)) }
        }
    }

    private class Box(
        val x1: Double, val y1: Double, val z1: Double,
        val x2: Double, val y2: Double, val z2: Double,
    ) {
        // The blockstate turns the model clockwise from NORTH: 90 for EAST, 180 for SOUTH, 270 for WEST.
        fun shape(facing: Direction): VoxelShape = when (facing) {
            Direction.EAST -> Block.box(16.0 - z2, y1, x1, 16.0 - z1, y2, x2)
            Direction.SOUTH -> Block.box(16.0 - x2, y1, 16.0 - z2, 16.0 - x1, y2, 16.0 - z1)
            Direction.WEST -> Block.box(z1, y1, 16.0 - x2, z2, y2, 16.0 - x1)
            else -> Block.box(x1, y1, z1, x2, y2, z2)
        }
    }
}
