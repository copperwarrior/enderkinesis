package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Quaternionf
import org.joml.Quaternionfc
import org.joml.Vector3f
import org.shipwrights.enderkinesis.blockentity.VoidHookBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import java.util.EnumMap
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation

/**
 * Void Hook — directional, longer-effective-range cousin of [VoidHarnessBlock]. The hook
 * always pulls *other* ships within [VoidHookBlockEntity.PULL_RADIUS] of itself toward its
 * own world position, whether the hook is placed in the world or on another ship — so a
 * ship-mounted hook is a directional grappler that drags target ships in to dock with it,
 * not a self-thruster like the harness.
 *
 * The 6-way [FACING] uses dispenser placement convention: the face the player clicks
 * becomes the back; the hook's "open end" points the opposite way (toward whatever
 * direction the player was looking). Only ships in the half-space *in front of* the
 * hook (positive dot product against FACING) get pulled — the back-half is ignored, so
 * the hook genuinely projects in one direction rather than acting as an omni-pull.
 */
class VoidHookBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWER, 0)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, POWER)
    }

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.rotateFacing(rotation)

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.mirrorFacing(mirror)

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        // Dispenser convention: the clicked face becomes the back, so FACING points the
        // opposite way (toward the player's look direction).
        val facing = context.nearestLookingDirection.opposite
        val powered = context.level.getBestNeighborSignal(context.clickedPos)
        return defaultBlockState()
            .setValue(FACING, facing)
            .setValue(POWER, powered)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        VoidHookBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.VOID_HOOK.get()) return null
        return BlockEntityTicker { lvl, p, st, be ->
            (be as? VoidHookBlockEntity)?.serverTick(lvl as ServerLevel, p, st)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        neighborBlock: Block, neighborPos: BlockPos, isMoving: Boolean,
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, isMoving)
        if (level.isClientSide) return
        val signal = (level.getBlockEntity(pos) as? VoidHookBlockEntity)?.effectivePower(level, pos)
            ?: level.getBestNeighborSignal(pos)
        if (state.getValue(POWER) != signal) {
            level.setBlock(pos, state.setValue(POWER, signal), Block.UPDATE_CLIENTS)
        }
    }

    companion object {
        /** 6-direction facing (UP/DOWN/N/S/E/W) — same property type as a dispenser. */
        val FACING = BlockStateProperties.FACING

        /** Redstone power 0–15 — scales pull force linearly. */
        val POWER = BlockStateProperties.POWER
        const val MAX_POWER: Int = 15

        // Must agree with the x/y rotations in blockstates/void_hook.json.
        private val MODEL_ROTATIONS: Map<Direction, Quaternionfc> =
            EnumMap<Direction, Quaternionfc>(Direction::class.java).apply {
                put(Direction.UP, blockstateRotation(0, 0))
                put(Direction.DOWN, blockstateRotation(180, 0))
                put(Direction.SOUTH, blockstateRotation(270, 0))
                put(Direction.WEST, blockstateRotation(270, 90))
                put(Direction.NORTH, blockstateRotation(270, 180))
                put(Direction.EAST, blockstateRotation(270, 270))
            }

        /** Rotation about the block center from the up-facing model to [facing]. */
        fun modelRotation(facing: Direction): Quaternionfc = MODEL_ROTATIONS.getValue(facing)

        // Same quaternion as vanilla BlockModelRotation.
        private fun blockstateRotation(xDegrees: Int, yDegrees: Int): Quaternionfc =
            Quaternionf().rotateYXZ(
                Math.toRadians(-yDegrees.toDouble()).toFloat(),
                Math.toRadians(-xDegrees.toDouble()).toFloat(),
                0f,
            )

        // Up-facing boxes in pixels: minX, minY, minZ, maxX, maxY, maxZ.
        private val UP_BOXES: List<FloatArray> = listOf(
            floatArrayOf(0f, 0f, 0f, 16f, 2f, 16f),
            floatArrayOf(2f, 2f, 2f, 14f, 4f, 14f),
            floatArrayOf(6f, 4f, 3f, 10f, 16f, 9f),
            floatArrayOf(6.5f, 11f, 9f, 9.5f, 16f, 13f),
        )

        private val SHAPES: Map<Direction, VoxelShape> =
            EnumMap<Direction, VoxelShape>(Direction::class.java).apply {
                for (facing in Direction.entries) put(facing, shapeFor(modelRotation(facing)))
            }

        private fun shapeFor(rotation: Quaternionfc): VoxelShape = UP_BOXES
            .map { box ->
                val a = rotation.transform(Vector3f(box[0] - HALF, box[1] - HALF, box[2] - HALF))
                val b = rotation.transform(Vector3f(box[3] - HALF, box[4] - HALF, box[5] - HALF))
                box(
                    pixel(minOf(a.x, b.x)), pixel(minOf(a.y, b.y)), pixel(minOf(a.z, b.z)),
                    pixel(maxOf(a.x, b.x)), pixel(maxOf(a.y, b.y)), pixel(maxOf(a.z, b.z)),
                )
            }
            .reduce { acc, shape -> Shapes.or(acc, shape) }

        // Float rotation leaves residue such as 15.999999; snap it so the shape edges stay on the grid.
        private fun pixel(centered: Float): Double = Math.round((centered + HALF) * SNAP) / SNAP.toDouble()

        private const val HALF = 8f
        private const val SNAP = 1000f
    }
}
