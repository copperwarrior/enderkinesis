package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.item.StaffOfCommandItem
import org.shipwrights.enderkinesis.construction.playerInsertClawhod
import org.shipwrights.enderkinesis.construction.playerTakeFromClawhod
import net.minecraft.server.level.ServerLevel
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import org.shipwrights.enderkinesis.blockentity.ClawhodBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.util.ShipMoves

class ClawhodBlock(props: BlockBehaviour.Properties) : Block(props), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(HEADING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, HEADING)
    }

    // Like a log: the column points away from the clicked face. The hands rest pointing away from the player.
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.clickedFace).setValue(HEADING, context.horizontalDirection)

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING))).setValue(HEADING, rotation.rotate(state.getValue(HEADING)))

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun mirror(state: BlockState, mirror: Mirror): BlockState {
        val facing = state.getValue(FACING)
        return state.rotate(mirror.getRotation(if (facing.axis.isVertical) state.getValue(HEADING) else facing))
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ClawhodBlockEntity(pos, state)

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES[state.getValue(FACING).ordinal]

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun use(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        // Block.use runs before Item.useOn: a pass lets the staff add or remove this Clawhod in its own list.
        val held = player.getItemInHand(hand)
        if (held.item is StaffOfCommandItem) return InteractionResult.PASS
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.sidedSuccess(level.isClientSide)
        val moved = if (held.isEmpty) {
            level.playerTakeFromClawhod(pos, player)
        } else {
            level.playerInsertClawhod(pos, player, held)
        }
        if (!moved) player.displayClientMessage(Component.translatable(if (held.isEmpty) EMPTY_KEY else FULL_KEY), true)
        return InteractionResult.CONSUME
    }

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide || type != EKBlockEntities.CLAWHOD.get()) return null
        return BlockEntityTicker { lvl, _, _, be ->
            if (be is ClawhodBlockEntity) {
                val server = lvl as ServerLevel
                be.ensureWorker().tick(server)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (level is ServerLevel && !newState.`is`(this) && !ShipMoves.isMovingBlocks(level)) {
            val be = level.getBlockEntity(pos) as? ClawhodBlockEntity
            if (be != null) {
                be.worker?.stop(level, "the clawhod block was removed")
                for (stack in be.takeAllItems()) popResource(level, pos, stack)
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    companion object {
        /** The way the column points. */
        val FACING: DirectionProperty = BlockStateProperties.FACING

        /** The way the hands rest when the column is vertical. */
        val HEADING: DirectionProperty = DirectionProperty.create("heading", Direction.Plane.HORIZONTAL)

        // The core model's boxes (px along the column from the base, then half-width): the 45-degree tier is its
        // square footprint. The shape for each facing turns the column onto that axis.
        private val BOXES = arrayOf(
            doubleArrayOf(0.0, 2.0, 7.0), doubleArrayOf(2.0, 4.0, 7.0),
            doubleArrayOf(4.0, 6.0, 3.5), doubleArrayOf(6.0, 13.0, 2.5),
        )

        private val SHAPES: Array<VoxelShape> = Array(Direction.values().size) { i -> shapeFor(Direction.values()[i]) }

        internal fun shapeFor(facing: Direction): VoxelShape {
            var shape = Shapes.empty()
            for ((h0, h1, half) in BOXES) {
                val lo = (8.0 - half) / PX
                val hi = (8.0 + half) / PX
                val a = if (facing.axisDirection == Direction.AxisDirection.POSITIVE) h0 / PX else 1.0 - h1 / PX
                val b = if (facing.axisDirection == Direction.AxisDirection.POSITIVE) h1 / PX else 1.0 - h0 / PX
                val box = when (facing.axis) {
                    Direction.Axis.X -> Shapes.box(a, lo, lo, b, hi, hi)
                    Direction.Axis.Y -> Shapes.box(lo, a, lo, hi, b, hi)
                    else -> Shapes.box(lo, lo, a, hi, hi, b)
                }
                shape = Shapes.or(shape, box)
            }
            return shape
        }

        private const val PX = 16.0
        private const val EMPTY_KEY = "message.enderkinesis.clawhod.empty"
        private const val FULL_KEY = "message.enderkinesis.clawhod.full"
    }
}
