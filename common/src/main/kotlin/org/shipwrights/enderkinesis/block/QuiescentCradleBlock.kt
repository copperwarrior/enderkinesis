package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.QuiescentCradleBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.util.ShipMoves

/** Sselith ship-holding block. Redstone level grips: powered from any side holds, unpowered releases. */
class QuiescentCradleBlock(properties: Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, POWERED)
    }

    // The control face, with the orrery, looks at the player who places it.
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState()
            .setValue(FACING, context.horizontalDirection.opposite)
            .setValue(POWERED, context.level.hasNeighborSignal(context.clickedPos))

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        SHAPES[state.getValue(FACING).get2DDataValue()]

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun isPathfindable(state: BlockState, level: BlockGetter, pos: BlockPos, type: PathComputationType): Boolean =
        false

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = QuiescentCradleBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (type != EKBlockEntities.QUIESCENT_CRADLE.get()) return null
        return BlockEntityTicker { lvl, _, st, be -> (be as? QuiescentCradleBlockEntity)?.tick(lvl, st) }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos, block: Block, fromPos: BlockPos, moved: Boolean,
    ) {
        super.neighborChanged(state, level, pos, block, fromPos, moved)
        if (level.isClientSide) return
        updatePowered(state, level, pos)
        (level.getBlockEntity(pos) as? QuiescentCradleBlockEntity)?.markShelvesDirty()
    }

    // Plain Block does not pass block events to its block entity.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun triggerEvent(state: BlockState, level: Level, pos: BlockPos, id: Int, param: Int): Boolean {
        super.triggerEvent(state, level, pos, id, param)
        return level.getBlockEntity(pos)?.triggerEvent(id, param) ?: false
    }

    // Placement by a command or a structure skips getStateForPlacement.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, moved: Boolean) {
        super.onPlace(state, level, pos, oldState, moved)
        if (!level.isClientSide && !oldState.`is`(state.block)) updatePowered(state, level, pos)
    }

    private fun updatePowered(state: BlockState, level: Level, pos: BlockPos) {
        val powered = level.hasNeighborSignal(pos)
        if (powered != state.getValue(POWERED)) level.setBlock(pos, state.setValue(POWERED, powered), UPDATE_CLIENTS)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        // VS2 removes each old block of an assembly; that is a move, and the copied block entity keeps the hold.
        if (!state.`is`(newState.block) && level is ServerLevel && !ShipMoves.isMovingBlocks(level)) {
            (level.getBlockEntity(pos) as? QuiescentCradleBlockEntity)?.release(level)
        }
        super.onRemove(state, level, pos, newState, moved)
    }

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING

        /** Redstone level grips: true holds, false releases. */
        val POWERED: BooleanProperty = BlockStateProperties.POWERED

        // Model boxes in pixels with the control face north; the 45° strut is its bounding box. The rings are not solid.
        private val NORTH_BOXES = arrayOf(
            doubleArrayOf(0.0, 0.0, 0.0, 16.0, 3.0, 16.0),
            doubleArrayOf(4.0, 3.0, 0.0, 12.0, 5.0, 16.0),
            doubleArrayOf(4.0, 5.0, 4.0, 12.0, 8.0, 16.0),
            doubleArrayOf(4.0, 8.0, 6.0, 12.0, 14.0, 15.0),
            doubleArrayOf(3.0, 3.0, 7.0, 13.0, 15.0, 14.0),
            doubleArrayOf(3.0, 15.0, 7.0, 7.0, 16.0, 14.0),
            doubleArrayOf(9.0, 15.0, 7.0, 13.0, 16.0, 14.0),
            doubleArrayOf(3.0, 16.0, 7.0, 5.0, 25.0, 14.0),
            doubleArrayOf(11.0, 16.0, 7.0, 13.0, 25.0, 14.0),
            doubleArrayOf(2.0, 3.0, 8.0, 3.0, 23.0, 13.0),
            doubleArrayOf(13.0, 3.0, 8.0, 14.0, 23.0, 13.0),
            doubleArrayOf(6.0, 1.0, 0.0, 10.0, 10.5, 8.0),
        )

        /** By [Direction.get2DDataValue]: south, west, north, east. */
        private val SHAPES: Array<VoxelShape> = Array(4) { i -> shapeFacing(Direction.from2DDataValue(i)) }

        private fun shapeFacing(facing: Direction): VoxelShape {
            val turns = when (facing) {
                Direction.EAST -> 1
                Direction.SOUTH -> 2
                Direction.WEST -> 3
                else -> 0
            }
            return NORTH_BOXES.fold(Shapes.empty()) { shape, b ->
                var x0 = b[0]
                var z0 = b[2]
                var x1 = b[3]
                var z1 = b[5]
                // Each turn maps (x, z) to (16 - z, x), as the blockstate turns the model by 90°.
                repeat(turns) {
                    val nx0 = 16.0 - z1
                    val nx1 = 16.0 - z0
                    z0 = x0
                    z1 = x1
                    x0 = nx0
                    x1 = nx1
                }
                Shapes.or(shape, box(x0, b[1], z0, x1, b[4], z1))
            }
        }
    }
}
