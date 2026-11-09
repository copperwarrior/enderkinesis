package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DirectionalBlock
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.EchoCannonBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Echo Cannon — sculk-faced block that fires a warden-sonic beam when
 * powered by redstone.
 *
 *  Block behaviour is intentionally thin. The cannon is a
 *  [DirectionalBlock] with FACING = the front (the sculk-catalyst-bottom
 *  face the beam exits from). All the timing / firing / damage logic
 *  lives in [EchoCannonBlockEntity]; this class just wires placement,
 *  the redstone neighbour-change hook, and the BE ticker.
 *
 *  Texturing: every face is `minecraft:block/sculk` except the front,
 *  which uses `minecraft:block/sculk_catalyst_bottom` so the cannon
 *  reads as a sculk catalyst that's been pointed somewhere.
 */
class EchoCannonBlock(properties: BlockBehaviour.Properties) :
    DirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.rotateFacing(rotation)

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.mirrorFacing(mirror)

    /** Union of the full block cube + the barrel's 4-unit protrusion out of
     *  the FACING face. Selection outlines and collision include the barrel
     *  so the player can click / bump into it as its own shape. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: net.minecraft.core.BlockPos, ctx: CollisionContext,
    ): VoxelShape = SHAPE_BY_FACING[state.getValue(FACING).ordinal]

    /** The base model's own boxes, without the BER barrel. A full cube here culls
     *  neighbour faces behind the model's gaps, and the player sees through the world. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getOcclusionShape(
        state: BlockState, level: BlockGetter, pos: net.minecraft.core.BlockPos,
    ): VoxelShape = MODEL_SHAPE_BY_FACING[state.getValue(FACING).ordinal]

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun useShapeForLightOcclusion(state: BlockState): Boolean = false

    /** Vanilla gives a non-full occlusion shape a light block of 1; the cannon stops all light. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getLightBlock(state: BlockState, level: BlockGetter, pos: BlockPos): Int = level.maxLightLevel

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        // Player places the cannon with its front pointing where they're
        // looking — same shape as a dispenser / observer / piston: the
        // face you click toward becomes the FACING value.
        return defaultBlockState().setValue(FACING, context.nearestLookingDirection.opposite)
    }

    /** Redstone hook. Vanilla calls this whenever a neighbour changes;
     *  we route the powered-state edge into the BE so the BE owns the
     *  charge / fire state machine. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        block: Block, fromPos: BlockPos, isMoving: Boolean,
    ) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving)
        if (level.isClientSide) return
        val be = level.getBlockEntity(pos) as? EchoCannonBlockEntity ?: return
        be.onNeighborSignal(level.hasNeighborSignal(pos))
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        EchoCannonBlockEntity(pos, state)

    companion object {
        /** Barrel-protrusion box in model space (2..14 X, 2..14 Y, -4..0 Z)
         *  for the NORTH facing — the block-relative extent of the barrel
         *  that sticks out of the block. Rotated per facing below. */
        private fun barrelBoxFor(facing: net.minecraft.core.Direction): VoxelShape {
            val a = 2.0 / 16.0
            val b = 14.0 / 16.0
            val protrusion = 4.0 / 16.0
            return when (facing) {
                net.minecraft.core.Direction.NORTH -> Shapes.box(a, a, -protrusion, b, b, 0.0)
                net.minecraft.core.Direction.SOUTH -> Shapes.box(a, a, 1.0, b, b, 1.0 + protrusion)
                net.minecraft.core.Direction.WEST  -> Shapes.box(-protrusion, a, a, 0.0, b, b)
                net.minecraft.core.Direction.EAST  -> Shapes.box(1.0, a, a, 1.0 + protrusion, b, b)
                net.minecraft.core.Direction.DOWN  -> Shapes.box(a, -protrusion, a, b, 0.0, b)
                net.minecraft.core.Direction.UP    -> Shapes.box(a, 1.0, a, b, 1.0 + protrusion, b)
            }
        }

        /** Per-facing shape = full block cube ∪ barrel protrusion. */
        private val SHAPE_BY_FACING: Array<VoxelShape> = net.minecraft.core.Direction.entries.let { dirs ->
            Array(dirs.size) { i -> Shapes.or(Shapes.block(), barrelBoxFor(dirs[i])) }
        }

        /** echo_cannon.json boxes as (u, v, depth from the front) in pixels; the set is symmetric in u and v. */
        private val MODEL_BOXES: List<IntArray> = listOf(
            intArrayOf(0, 0, 0, 6, 6, 16),
            intArrayOf(10, 0, 0, 16, 6, 16),
            intArrayOf(0, 10, 0, 6, 16, 16),
            intArrayOf(10, 10, 0, 16, 16, 16),
            intArrayOf(1, 1, 1, 15, 15, 15),
            intArrayOf(2, 2, 1, 14, 14, 16),
        )

        private fun modelBox(b: IntArray, facing: Direction): VoxelShape {
            val u1 = b[0].toDouble()
            val v1 = b[1].toDouble()
            val w1 = b[2].toDouble()
            val u2 = b[3].toDouble()
            val v2 = b[4].toDouble()
            val w2 = b[5].toDouble()
            return when (facing) {
                Direction.NORTH -> Block.box(u1, v1, w1, u2, v2, w2)
                Direction.SOUTH -> Block.box(u1, v1, 16 - w2, u2, v2, 16 - w1)
                Direction.WEST -> Block.box(w1, v1, u1, w2, v2, u2)
                Direction.EAST -> Block.box(16 - w2, v1, u1, 16 - w1, v2, u2)
                Direction.DOWN -> Block.box(u1, w1, v1, u2, w2, v2)
                Direction.UP -> Block.box(u1, 16 - w2, v1, u2, 16 - w1, v2)
            }
        }

        private val MODEL_SHAPE_BY_FACING: Array<VoxelShape> = Direction.entries.let { dirs ->
            Array(dirs.size) { i ->
                MODEL_BOXES.map { modelBox(it, dirs[i]) }.reduce { acc, box -> Shapes.or(acc, box) }
            }
        }
    }

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.ECHO_CANNON.get()) return null
        return BlockEntityTicker { lvl, p, st, be ->
            (be as? EchoCannonBlockEntity)?.serverTick(lvl, p, st)
        }
    }
}
